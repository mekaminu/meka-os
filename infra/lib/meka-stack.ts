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
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as cr from 'aws-cdk-lib/custom-resources';
import { Construct } from 'constructs';

/**
 * Image registry, deployed first so CI can push the sync image before the service stack references it.
 * Separate stack, same isolation (dedicated account, dedicated KMS-free AES256 at rest is fine for images).
 */
export class MekaRegistryStack extends cdk.Stack {
  readonly repo: ecr.Repository;
  constructor(scope: Construct, id: string, props: cdk.StackProps & { envName: string }) {
    super(scope, id, props);
    cdk.Tags.of(this).add('app', 'meka-os');
    cdk.Tags.of(this).add('env', props.envName);
    this.repo = new ecr.Repository(this, 'Repo', {
      repositoryName: `meka-os-${props.envName}-sync`,
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      lifecycleRules: [{ maxImageCount: 10 }],
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });
    new cdk.CfnOutput(this, 'RepositoryUri', { value: this.repo.repositoryUri });
  }
}

export interface MekaStackProps extends cdk.StackProps {
  /** e.g. "dev" | "prod". Becomes part of every resource name for isolation (ADR-004). */
  readonly envName: string;
  /** Reserved for a custom domain later (CloudFront alias + us-east-1 certificate). Unused today. */
  readonly certificateArn?: string;
  /** Container image tag in the registry stack's ECR repository (CI passes the git SHA). */
  readonly imageTag?: string;
  /**
   * The full git commit being deployed (CI passes it). Kept as the `DeployedCommit` output so the next deploy can
   * tell whether anything server-side changed since the last successful one (deploy.yml, `changes` job).
   */
  readonly deployedCommit?: string;
  /** From MekaRegistryStack. */
  readonly repo: ecr.IRepository;
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
    // CloudWatch Logs encrypts with this key itself, so it needs an explicit key-policy grant, scoped to this
    // account and region's log groups via the encryption context.
    this.key.addToResourcePolicy(new cdk.aws_iam.PolicyStatement({
      principals: [new cdk.aws_iam.ServicePrincipal(`logs.${this.region}.amazonaws.com`)],
      actions: ['kms:Encrypt', 'kms:Decrypt', 'kms:ReEncrypt*', 'kms:GenerateDataKey*', 'kms:Describe*'],
      resources: ['*'],
      conditions: {
        ArnLike: { 'kms:EncryptionContext:aws:logs:arn': `arn:aws:logs:${this.region}:${this.account}:log-group:*` },
      },
    }));

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
    const albSg = new ec2.SecurityGroup(this, 'AlbSg', { vpc, allowAllOutbound: false, description: 'CloudFront origin only' });
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
      // Callers' recordings (call assistant polish 8c): the service deletes one when Meka taps Done; this is the
      // backstop, so nothing under voice/ outlives 30 days (and no older version lingers past a day).
      lifecycleRules: [{
        id: 'VoiceRecordings',
        prefix: 'voice/',
        expiration: cdk.Duration.days(30),
        noncurrentVersionExpiration: cdk.Duration.days(1),
      }],
    });

    const repo = props.repo;

    // One-time device enrolment code (ADR-005 M0). Read it in the Secrets Manager console; rotate to revoke.
    const enrolToken = new secretsmanager.Secret(this, 'EnrolToken', {
      secretName: `${prefix}/enrol-token`,
      encryptionKey: this.key,
      generateSecretString: { passwordLength: 48, excludePunctuation: true, includeSpace: false },
      description: 'MEKA OS device enrolment code',
    });
    // Shared secret proving a request came through CloudFront (the ALB rejects anything without it).
    const originVerify = new secretsmanager.Secret(this, 'OriginVerify', {
      secretName: `${prefix}/origin-verify`,
      encryptionKey: this.key,
      generateSecretString: { passwordLength: 48, excludePunctuation: true, includeSpace: false },
    });

    // OAuth app credentials for calendar/email providers (ADR-008). Created with a placeholder; the owner pastes the
    // real client id and secret in the Secrets Manager console. The service reads them at use time, so no redeploy.
    const oauthSecret = (provider: string, label: string) => new secretsmanager.Secret(this, `OAuth${label}`, {
      secretName: `${prefix}/oauth/${provider}`,
      encryptionKey: this.key,
      description: `MEKA OS ${label} OAuth client: {"client_id": "...", "client_secret": "..."}`,
      generateSecretString: { secretStringTemplate: JSON.stringify({ client_id: '' }), generateStringKey: 'client_secret', excludePunctuation: true },
    });
    const oauthGoogle = oauthSecret('google', 'Google');
    const oauthMicrosoft = oauthSecret('microsoft', 'Microsoft');
    // The AI layer's API key (ADR-006). Empty until the owner pastes it in; the service treats empty as "AI off".
    // The monthly spend cap lives in the Anthropic Console, so a leaked key can't spend past it.
    const aiKey = new secretsmanager.Secret(this, 'AiKey', {
      secretName: `${prefix}/ai/anthropic`,
      encryptionKey: this.key,
      description: 'MEKA OS AI key: {"api_key": "sk-ant-..."}',
      secretStringValue: cdk.SecretValue.unsafePlainText(JSON.stringify({ api_key: '' })),
    });
    // Firebase Cloud Messaging service account (push notifications). '{}' until the owner pastes the JSON key file.
    const fcmKey = new secretsmanager.Secret(this, 'FcmKey', {
      secretName: `${prefix}/fcm/service-account`,
      encryptionKey: this.key,
      description: 'MEKA OS push: the Firebase service-account JSON (Project settings → Service accounts → Generate new private key)',
      secretStringValue: cdk.SecretValue.unsafePlainText('{}'),
    });

    // Hands-free phone updates: GitHub's release-only publisher. Its P-256 private key is readable ONLY by the GitHub
    // deploy role (an explicit deny for every other principal, the service included); the publish job makes the key
    // pair the first time and writes the public half to the second secret, which the service reads to check its
    // signatures. Both start as '{}'. The publisher may call only the release latest/upload routes (backend tests).
    const deployRoleArn = `arn:aws:iam::${this.account}:role/meka-os-github-deploy`;
    const publisherKey = new secretsmanager.Secret(this, 'ReleasePublisherKey', {
      secretName: `${prefix}/release-publisher`,
      encryptionKey: this.key,
      description: 'MEKA OS phone updates: the GitHub publisher\'s private key (written by the deploy workflow; readable only by the deploy role)',
      secretStringValue: cdk.SecretValue.unsafePlainText('{}'),
    });
    publisherKey.addToResourcePolicy(new cdk.aws_iam.PolicyStatement({
      effect: cdk.aws_iam.Effect.DENY,
      principals: [new cdk.aws_iam.AnyPrincipal()],
      actions: ['secretsmanager:GetSecretValue'],
      resources: ['*'],
      conditions: { ArnNotEquals: { 'aws:PrincipalArn': deployRoleArn } },
    }));
    const publisherPublic = new secretsmanager.Secret(this, 'ReleasePublisherPublic', {
      secretName: `${prefix}/release-publisher-public`,
      encryptionKey: this.key,
      description: 'MEKA OS phone updates: {"public_key": ...} of the GitHub publisher (not secret; written by the deploy workflow)',
      secretStringValue: cdk.SecretValue.unsafePlainText('{}'),
    });

    // The call assistant's phone service (Twilio, approved by Meka 2026-10-07): '{}' until he pastes the account's
    // auth token, which the service uses only to check that webhooks really come from Twilio. Read at use time.
    const voiceTwilio = new secretsmanager.Secret(this, 'VoiceTwilio', {
      secretName: `${prefix}/voice/twilio`,
      encryptionKey: this.key,
      description: 'MEKA OS call assistant: {"auth_token": "..."} from the Twilio console (Account info → Auth token)',
      secretStringValue: cdk.SecretValue.unsafePlainText('{}'),
    });

    const cluster = new ecs.Cluster(this, 'Cluster', { vpc, clusterName: prefix, containerInsightsV2: ecs.ContainerInsights.DISABLED });
    const task = new ecs.FargateTaskDefinition(this, 'Task', {
      cpu: 256,
      memoryLimitMiB: 512,
      runtimePlatform: { cpuArchitecture: ecs.CpuArchitecture.ARM64, operatingSystemFamily: ecs.OperatingSystemFamily.LINUX },
    });
    const dbSecret = this.db.secret!;
    // Read-only root filesystem, plus a scratch /tmp for the JVM and Netty's native libraries.
    task.addVolume({ name: 'tmp' });
    const container = task.addContainer('sync', {
      image: ecs.ContainerImage.fromEcrRepository(repo, props.imageTag ?? 'latest'),
      portMappings: [{ containerPort: 8080 }],
      environment: {
        PORT: '8080',
        MEKA_DB_URL: `jdbc:postgresql://${this.db.dbInstanceEndpointAddress}:${this.db.dbInstanceEndpointPort}/meka?sslmode=require`,
        MEKA_JOBS_QUEUE_URL: jobs.queueUrl,
        MEKA_BLOB_BUCKET: blobs.bucketName,
        MEKA_KMS_KEY_ID: this.key.keyArn,
        MEKA_OAUTH_GOOGLE_SECRET: oauthGoogle.secretArn,
        MEKA_OAUTH_MICROSOFT_SECRET: oauthMicrosoft.secretArn,
        MEKA_AI_SECRET: aiKey.secretArn,
        MEKA_FCM_SECRET: fcmKey.secretArn,
        MEKA_RELEASE_PUBLISHER_SECRET: publisherPublic.secretArn,
        MEKA_VOICE_TWILIO_SECRET: voiceTwilio.secretArn,
        // MEKA's voice (build plan V1, Weather and a voice: Amazon Polly in MEKA's own account, approved 2026-10-08).
        MEKA_SPEECH_ENGINE: 'polly',
      },
      secrets: {
        MEKA_DB_USER: ecs.Secret.fromSecretsManager(dbSecret, 'username'),
        MEKA_DB_PASSWORD: ecs.Secret.fromSecretsManager(dbSecret, 'password'),
        MEKA_ENROL_TOKEN: ecs.Secret.fromSecretsManager(enrolToken),
      },
      logging: ecs.LogDrivers.awsLogs({
        streamPrefix: 'sync',
        logGroup: new logs.LogGroup(this, 'Logs', { retention: logs.RetentionDays.ONE_MONTH, encryptionKey: this.key }),
      }),
      // Health is judged by the load balancer's /health check, so the image needs no shell tools.
      readonlyRootFilesystem: true,
    });
    container.addMountPoints({ containerPath: '/tmp', sourceVolume: 'tmp', readOnly: false });
    jobs.grantSendMessages(task.taskRole);
    jobs.grantConsumeMessages(task.taskRole);
    blobs.grantReadWrite(task.taskRole);
    // The KMS key policy lets the service use the key for exactly these resources; no wildcard grants elsewhere.
    this.key.grantEncryptDecrypt(task.taskRole);
    // Read-only: the service never writes OAuth app credentials. Integration refresh tokens are KMS-encrypted in Postgres.
    oauthGoogle.grantRead(task.taskRole);
    oauthMicrosoft.grantRead(task.taskRole);
    aiKey.grantRead(task.taskRole);
    fcmKey.grantRead(task.taskRole);
    publisherPublic.grantRead(task.taskRole); // the public half only; the private key is never granted to the service
    voiceTwilio.grantRead(task.taskRole);
    // MEKA's voice: turn MEKA's own reply text into speech and list the voices, nothing else (no lexicons, no S3
    // speech tasks). Polly's synthesis actions have no resource-level scoping, so the resource is '*'.
    task.taskRole.addToPrincipalPolicy(new cdk.aws_iam.PolicyStatement({
      actions: ['polly:SynthesizeSpeech', 'polly:DescribeVoices'],
      resources: ['*'],
    }));

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

    // Public HTTPS without owning a domain: CloudFront (*.cloudfront.net, TLS) → ALB over HTTP inside AWS.
    // The ALB accepts traffic only from CloudFront's origin-facing IP ranges AND only with the secret header.
    // Resolved at deploy time (ID differs per region), so synth needs no AWS credentials.
    const cfPrefixList = new cr.AwsCustomResource(this, 'CloudFrontOriginPrefixList', {
      onUpdate: {
        service: 'EC2',
        action: 'describeManagedPrefixLists',
        parameters: { Filters: [{ Name: 'prefix-list-name', Values: ['com.amazonaws.global.cloudfront.origin-facing'] }] },
        physicalResourceId: cr.PhysicalResourceId.of('cloudfront-origin-facing'),
        outputPaths: ['PrefixLists.0.PrefixListId'],
      },
      policy: cr.AwsCustomResourcePolicy.fromSdkCalls({ resources: cr.AwsCustomResourcePolicy.ANY_RESOURCE }),
      installLatestAwsSdk: false,
    });
    albSg.addIngressRule(
      ec2.Peer.prefixList(cfPrefixList.getResponseField('PrefixLists.0.PrefixListId')),
      ec2.Port.tcp(80),
      'CloudFront origin-facing only',
    );

    const alb = new elbv2.ApplicationLoadBalancer(this, 'Alb', {
      vpc,
      internetFacing: true,
      securityGroup: albSg,
      dropInvalidHeaderFields: true,
    });
    const listener = alb.addListener('Http', {
      port: 80,
      open: false,
      defaultAction: elbv2.ListenerAction.fixedResponse(403, { contentType: 'text/plain', messageBody: 'forbidden' }),
    });
    listener.addTargets('Sync', {
      priority: 10,
      conditions: [elbv2.ListenerCondition.httpHeader('X-Origin-Verify', [originVerify.secretValue.unsafeUnwrap()])],
      port: 8080,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targets: [this.service],
      healthCheck: { path: '/health', healthyHttpCodes: '200' },
      deregistrationDelay: cdk.Duration.seconds(10),
    });

    const distribution = new cloudfront.Distribution(this, 'Cdn', {
      comment: `${prefix} sync API`,
      priceClass: cloudfront.PriceClass.PRICE_CLASS_100,
      minimumProtocolVersion: cloudfront.SecurityPolicyProtocol.TLS_V1_2_2021,
      defaultBehavior: {
        origin: new origins.LoadBalancerV2Origin(alb, {
          protocolPolicy: cloudfront.OriginProtocolPolicy.HTTP_ONLY,
          customHeaders: { 'X-Origin-Verify': originVerify.secretValue.unsafeUnwrap() },
        }),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.HTTPS_ONLY,
        allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
        cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
        originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER,
      },
    });

    // The OAuth redirect URIs registered with Google/Microsoft are derived from this public URL.
    container.addEnvironment('MEKA_PUBLIC_URL', `https://${distribution.distributionDomainName}`);
    new cdk.CfnOutput(this, 'SyncUrl', { value: `https://${distribution.distributionDomainName}` });
    new cdk.CfnOutput(this, 'EnrolTokenSecret', { value: enrolToken.secretName });
    if (props.deployedCommit) new cdk.CfnOutput(this, 'DeployedCommit', { value: props.deployedCommit });
  }
}
