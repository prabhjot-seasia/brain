#!/usr/bin/env python3
import os
import sys

import aws_cdk as cdk

import config
from stacks import NetworkStack, Neo4jStack, EcrStack, EcsStack

app = cdk.App()

account_id = os.environ.get("AWS_ACCOUNT_ID")
region = os.environ.get("AWS_REGION")
if not account_id or not region:
    sys.exit("AWS_ACCOUNT_ID and AWS_REGION environment variables are required.")

config.env = {"account": account_id, "region": region}

ecr = EcrStack(app)

for environment in config.environments:
    config.environment = environment
    network = NetworkStack(app)
    neo4j = Neo4jStack(app, network=network)
    EcsStack(app, network=network, neo4j=neo4j, ecr=ecr)

config.environment = None
app.synth()
