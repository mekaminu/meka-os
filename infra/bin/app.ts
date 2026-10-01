#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { MekaStack } from '../lib/meka-stack';

const app = new cdk.App();
const envName = app.node.tryGetContext('env') ?? 'dev';

new MekaStack(app, `MekaOs-${envName}`, {
  envName,
  certificateArn: app.node.tryGetContext('certificateArn'),
  imageTag: app.node.tryGetContext('imageTag'),
  // Region/account come from the deploying credentials. London keeps data in the UK.
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION ?? 'eu-west-2' },
});
