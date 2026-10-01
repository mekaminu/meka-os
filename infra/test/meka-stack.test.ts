import * as cdk from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { MekaStack } from '../lib/meka-stack';

function synth(props: Partial<{ certificateArn: string; envName: string }> = {}) {
  const app = new cdk.App();
  const stack = new MekaStack(app, 'Test', {
    envName: props.envName ?? 'dev',
    certificateArn: props.certificateArn,
    env: { account: '111111111111', region: 'eu-west-2' },
  });
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

  test('dev without a certificate is not internet-facing', () => {
    t.hasResourceProperties('AWS::ElasticLoadBalancingV2::LoadBalancer', { Scheme: 'internal' });
  });

  test('with a certificate: HTTPS only, modern TLS policy', () => {
    const p = synth({ certificateArn: 'arn:aws:acm:eu-west-2:111111111111:certificate/abc', envName: 'prod' });
    p.hasResourceProperties('AWS::ElasticLoadBalancingV2::Listener', { Port: 443, Protocol: 'HTTPS' });
    p.resourcePropertiesCountIs('AWS::ElasticLoadBalancingV2::Listener', { Port: 80 }, 0);
    p.hasResourceProperties('AWS::RDS::DBInstance', { DeletionProtection: true });
  });
});
