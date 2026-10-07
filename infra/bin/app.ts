#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { MekaRegistryStack, MekaStack } from '../lib/meka-stack';

const app = new cdk.App();
const envName = app.node.tryGetContext('env') ?? 'dev';
// Region/account come from the deploying credentials. London keeps data in the UK.
const env = { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION ?? 'eu-west-2' };

const registry = new MekaRegistryStack(app, `MekaOs-${envName}-Registry`, { envName, env });
new MekaStack(app, `MekaOs-${envName}`, {
  envName,
  env,
  repo: registry.repo,
  imageTag: app.node.tryGetContext('imageTag'),
  deployedCommit: app.node.tryGetContext('commit'),
});
