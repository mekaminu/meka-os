import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import * as kms from 'aws-cdk-lib/aws-kms';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as acm from 'aws-cdk-lib/aws-certificatemanager';
import { Construct } from 'constructs';

export interface MekaStackProps extends cdk.StackProps {
  /** e.g. "dev" | "prod". Becomes part of every resource name for isolation (ADR-004). */
  readonly envName: string;
  /** ACM certificate ARN for the sync endpoint. When absent (dev synth), the listener is HTTP-only and not internet-facing. */
  readonly certificateArn?: string;
  /** Container image tag in the stack's ECR repository. */
  readonly imageTag?: string;
}

/**
 * MEKA OS backend (ADR-004). One Fargate service + Postgres + SQS + S3, isolated from trading systems by
 * dedicated KMS key, IAM roles, secrets prefix, database and VPC. No NAT gateway.
 */
export class MekaStack extends cdk.Stack {
  readonly key: kms.Key;
  readonly db: rds.DatabaseInstance;
  readonly service: ecs.FargateService;

  constructor(scope: Construct, id: string, props: MekaStackProps) {
    super(scope, id, props);
    const prefix = `meka-os-${props.envName}`;
    cdk.Tags.of(this).add('app', 'meka-os');
    cdk.Tags.of(this).add('env', props.envName);

    this.key = new kms.Key(this, 'DataKey', {
      alias: `alias/${prefix}-data`,
      enableKeyRotation: true,
      description: 'MEKA OS data encryption key. Never shared with trading systems.',
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    // Public subnets for the ALB and tasks (public IP, SG-restricted), isolated subnets for the database.
    // natGateways: 0 avoids ~£30/month of idle NAT cost (ADR-004).
    const vpc = new ec2.Vpc(this, 'Vpc', {
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [
        { name: 'public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 },
        { name: 'data', subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
    });

    const dbSg = new ec2.SecurityGroup(this, 'DbSg', { vpc, allowAllOutbound: false, description: 'Postgres: sync service only' });
    const appSg = new ec2.SecurityGroup(this, 'AppSg', { vpc, allowAllOutbound: true, description: 'Sync service tasks' });
    const albSg = new ec2.SecurityGroup(this, 'AlbSg', { vpc, allowAllOutbound: false, description: 'Public HTTPS' });
    dbSg.addIngressRule(appSg, ec2.Port.tcp(5432), 'sync service');
    albSg.addEgressRule(appSg, ec2.Port.tcp(8080), 'to tasks');
    appSg.addIngressRule(albSg, ec2.Port.tcp(8080), 'from ALB');

    this.db = new rds.DatabaseInstance(this, 'Db', {
      engine: rds.DatabaseInstanceEngine.postgres({ version: rds.PostgresEngineVersion.VER_17 }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO),
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [dbSg],
      databaseName: 'meka',
      credentials: rds.Credentials.fromGeneratedSecret('meka', { secretName: `${prefix}/db`, encryptionKey: this.key }),
      storageEncrypted: true,
      storageEncryptionKey: this.key,
      allocatedStorage: 20,
      maxAllocatedStorage: 50,
      backupRetention: cdk.Duration.days(14),
      deletionProtection: props.envName === 'prod',
      publiclyAccessible: false,
      iamAuthentication: true,
      removalPolicy: props.envName === 'prod' ? cdk.RemovalPolicy.SNAPSHOT : cdk.RemovalPolicy.DESTROY,
    });

    const dlq = new sqs.Queue(this, 'JobsDlq', {
      queueName: `${prefix}-jobs-dlq`,
      encryption: sqs.QueueEncryption.KMS,
      encryptionMasterKey: this.key,
      retentionPeriod: cdk.Duration.days(14),
    });
    const jobs = new sqs.Queue(this, 'Jobs', {
      queueName: `${prefix}-jobs`,
      encryption: sqs.QueueEncryption.KMS,
      encryptionMasterKey: this.key,
      deadLetterQueue: { queue: dlq, maxReceiveCount: 5 },
    });

    const blobs = new s3.Bucket(this, 'Blobs', {
      encryption: s3.BucketEncryption.KMS,
      encryptionKey: this.key,
      bucketKeyEnabled: true,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      versioned: true,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const repo = new ecr.Repository(this, 'Repo', {
      repositoryName: `${prefix}-sync`,
      imageScanOnPush: true,
      encryption: ecr.RepositoryEncryption.KMS,
      encryptionKey: this.key,
      lifecycleRules: [{ maxImageCount: 10 }],
    });

    const cluster = new ecs.Cluster(this, 'Cluster', { vpc, clusterName: prefix, containerInsightsV2: ecs.ContainerInsights.DISABLED });
    const task = new ecs.FargateTaskDefinition(this, 'Task', {
      cpu: 256,
      memoryLimitMiB: 512,
      runtimePlatform: { cpuArchitecture: ecs.CpuArchitecture.ARM64, operatingSystemFamily: ecs.OperatingSystemFamily.LINUX },
    });
    const dbSecret = this.db.secret!;
    task.addContainer('sync', {
      image: ecs.ContainerImage.fromEcrRepository(repo, props.imageTag ?? 'latest'),
      portMappings: [{ containerPort: 8080 }],
      environment: {
        PORT: '8080',
        MEKA_DB_URL: `jdbc:postgresql://${this.db.dbInstanceEndpointAddress}:${this.db.dbInstanceEndpointPort}/meka?sslmode=require`,
        MEKA_JOBS_QUEUE_URL: jobs.queueUrl,
        MEKA_BLOB_BUCKET: blobs.bucketName,
      },
      secrets: {
        MEKA_DB_USER: ecs.Secret.fromSecretsManager(dbSecret, 'username'),
        MEKA_DB_PASSWORD: ecs.Secret.fromSecretsManager(dbSecret, 'password'),
      },
      logging: ecs.LogDrivers.awsLogs({
        streamPrefix: 'sync',
        logGroup: new logs.LogGroup(this, 'Logs', { retention: logs.RetentionDays.ONE_MONTH, encryptionKey: this.key }),
      }),
      healthCheck: { command: ['CMD-SHELL', 'wget -qO- http://localhost:8080/health || exit 1'] },
      readonlyRootFilesystem: true,
    });
    jobs.grantSendMessages(task.taskRole);
    jobs.grantConsumeMessages(task.taskRole);
    blobs.grantReadWrite(task.taskRole);
    // The KMS key policy lets the service use the key for exactly these resources; no wildcard grants elsewhere.
    this.key.grantEncryptDecrypt(task.taskRole);

    this.service = new ecs.FargateService(this, 'Service', {
      cluster,
      taskDefinition: task,
      desiredCount: 1,
      assignPublicIp: true, // no NAT: tasks egress via IGW; inbound is restricted to the ALB security group
      securityGroups: [appSg],
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      circuitBreaker: { rollback: true },
      minHealthyPercent: 100,
    });

    const alb = new elbv2.ApplicationLoadBalancer(this, 'Alb', {
      vpc,
      internetFacing: props.certificateArn !== undefined,
      securityGroup: albSg,
      dropInvalidHeaderFields: true,
    });
    const listener = props.certificateArn
      ? alb.addListener('Https', {
          port: 443,
          certificates: [acm.Certificate.fromCertificateArn(this, 'Cert', props.certificateArn)],
          sslPolicy: elbv2.SslPolicy.RECOMMENDED_TLS,
          open: true,
        })
      : alb.addListener('Http', { port: 80, open: false });
    listener.addTargets('Sync', {
      port: 8080,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targets: [this.service],
      healthCheck: { path: '/health', healthyHttpCodes: '200' },
      deregistrationDelay: cdk.Duration.seconds(10),
    });

    new cdk.CfnOutput(this, 'SyncEndpoint', { value: alb.loadBalancerDnsName });
    new cdk.CfnOutput(this, 'EcrRepository', { value: repo.repositoryUri });
  }
}
