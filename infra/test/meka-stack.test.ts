import * as cdk from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { MekaRegistryStack, MekaStack } from '../lib/meka-stack';

function synth(props: Partial<{ envName: string; deployedCommit: string }> = {}) {
  const app = new cdk.App();
  const env = { account: '111111111111', region: 'eu-west-2' };
  const envName = props.envName ?? 'dev';
  const registry = new MekaRegistryStack(app, 'Reg', { envName, env });
  const stack = new MekaStack(app, 'Test', { envName, env, repo: registry.repo, imageTag: 'abc123', deployedCommit: props.deployedCommit });
  return Template.fromStack(stack);
}

describe('MekaStack security and cost invariants (ADR-004)', () => {
  const t = synth();

  test('no NAT gateways (idle cost)', () => {
    t.resourceCountIs('AWS::EC2::NatGateway', 0);
  });

  test('database is encrypted, private, and only reachable from the sync service', () => {
    t.hasResourceProperties('AWS::RDS::DBInstance', {
      StorageEncrypted: true,
      PubliclyAccessible: false,
      BackupRetentionPeriod: 14,
    });
    t.hasResourceProperties('AWS::EC2::SecurityGroupIngress', { FromPort: 5432, ToPort: 5432 });
  });

  test('all data stores use the dedicated MEKA KMS key with rotation', () => {
    t.hasResourceProperties('AWS::KMS::Key', { EnableKeyRotation: true });
    t.hasResourceProperties('AWS::S3::Bucket', {
      BucketEncryption: { ServerSideEncryptionConfiguration: [Match.objectLike({ ServerSideEncryptionByDefault: { SSEAlgorithm: 'aws:kms' } })] },
      PublicAccessBlockConfiguration: { BlockPublicAcls: true, BlockPublicPolicy: true, IgnorePublicAcls: true, RestrictPublicBuckets: true },
    });
    t.allResourcesProperties('AWS::SQS::Queue', { KmsMasterKeyId: Match.anyValue() });
  });

  test('CloudWatch Logs may use the key, but only for this account and region log groups', () => {
    t.hasResourceProperties('AWS::KMS::Key', {
      KeyPolicy: { Statement: Match.arrayWith([Match.objectLike({
        Principal: { Service: 'logs.eu-west-2.amazonaws.com' },
        Condition: { ArnLike: { 'kms:EncryptionContext:aws:logs:arn': 'arn:aws:logs:eu-west-2:111111111111:log-group:*' } },
      })]) },
    });
  });

  test('bucket policy denies non-TLS access', () => {
    t.hasResourceProperties('AWS::S3::BucketPolicy', {
      PolicyDocument: { Statement: Match.arrayWith([Match.objectLike({ Effect: 'Deny', Condition: { Bool: { 'aws:SecureTransport': 'false' } } })]) },
    });
  });

  test('every taggable resource is tagged app=meka-os for IAM scoping', () => {
    const tagged = ['AWS::RDS::DBInstance', 'AWS::S3::Bucket', 'AWS::SQS::Queue', 'AWS::KMS::Key', 'AWS::ECS::Cluster'];
    for (const type of tagged) {
      t.allResourcesProperties(type, { Tags: Match.arrayWith([{ Key: 'app', Value: 'meka-os' }]) });
    }
  });

  test('secrets live under the meka-os/ prefix (no sharing with trading secrets)', () => {
    t.hasResourceProperties('AWS::SecretsManager::Secret', { Name: 'meka-os-dev/db' });
  });

  test('task runs ARM64 with a read-only root filesystem', () => {
    t.hasResourceProperties('AWS::ECS::TaskDefinition', {
      RuntimePlatform: { CpuArchitecture: 'ARM64' },
      ContainerDefinitions: [Match.objectLike({ ReadonlyRootFilesystem: true })],
    });
  });

  test('no IAM policy grants wildcard actions', () => {
    const policies = t.findResources('AWS::IAM::Policy');
    for (const [, p] of Object.entries(policies)) {
      for (const s of (p as any).Properties.PolicyDocument.Statement) {
        const actions = ([] as string[]).concat(s.Action);
        expect(actions).not.toContain('*');
        expect(actions.filter((a) => a.endsWith(':*'))).toEqual([]);
      }
    }
  });

  test('public entry is CloudFront HTTPS-only; ALB only accepts CloudFront with the secret header', () => {
    t.hasResourceProperties('AWS::CloudFront::Distribution', {
      DistributionConfig: Match.objectLike({
        DefaultCacheBehavior: Match.objectLike({ ViewerProtocolPolicy: 'https-only' }),
        Origins: [Match.objectLike({ OriginCustomHeaders: [Match.objectLike({ HeaderName: 'X-Origin-Verify' })] })],
      }),
    });
    // Default action refuses; only the header-matched rule forwards to the service.
    t.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', {
      DefaultActions: [Match.objectLike({ Type: 'fixed-response', FixedResponseConfig: Match.objectLike({ StatusCode: '403' }) })],
    });
    t.hasResourceProperties('AWS::ElasticLoadBalancingV2::ListenerRule', {
      Conditions: [Match.objectLike({ Field: 'http-header', HttpHeaderConfig: Match.objectLike({ HttpHeaderName: 'X-Origin-Verify' }) })],
    });
    // No ingress from the whole internet.
    const sgs = t.findResources('AWS::EC2::SecurityGroup');
    for (const [, sg] of Object.entries(sgs)) {
      for (const rule of ((sg as any).Properties.SecurityGroupIngress ?? [])) expect(rule.CidrIp).not.toBe('0.0.0.0/0');
    }
  });

  test('enrolment code is generated in Secrets Manager and injected, never in plain env', () => {
    t.hasResourceProperties('AWS::SecretsManager::Secret', { Name: 'meka-os-dev/enrol-token' });
    t.hasResourceProperties('AWS::ECS::TaskDefinition', {
      ContainerDefinitions: [Match.objectLike({ Secrets: Match.arrayWith([Match.objectLike({ Name: 'MEKA_ENROL_TOKEN' })]) })],
    });
  });

  test('OAuth app credentials live under the env prefix and the service can only read them', () => {
    t.hasResourceProperties('AWS::SecretsManager::Secret', { Name: 'meka-os-dev/oauth/google' });
    t.hasResourceProperties('AWS::SecretsManager::Secret', { Name: 'meka-os-dev/oauth/microsoft' });
    t.hasResourceProperties('AWS::SecretsManager::Secret', { Name: 'meka-os-dev/ai/anthropic' });
    const policies = t.findResources('AWS::IAM::Policy');
    const actions = Object.values(policies).flatMap((p: any) => p.Properties.PolicyDocument.Statement.flatMap((s: any) => ([] as string[]).concat(s.Action)));
    expect(actions.filter((a) => a.startsWith('secretsmanager:') && !/^secretsmanager:(GetSecretValue|DescribeSecret)$/.test(a))).toEqual([]);
  });

  test('records the deployed commit only when CI passes one (deploy.yml diffs against it)', () => {
    const sha = '29720ce900d5aa07156abbdf1f420121003b40b9';
    synth({ deployedCommit: sha }).hasOutput('DeployedCommit', { Value: sha });
    expect(Object.keys(t.findOutputs('DeployedCommit'))).toEqual([]);
  });

  test('prod keeps deletion protection', () => {
    synth({ envName: 'prod' }).hasResourceProperties('AWS::RDS::DBInstance', { DeletionProtection: true });
  });
});
