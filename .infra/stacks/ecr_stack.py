from aws_cdk import Stack, Tags, aws_ecr as ecr
from constructs import Construct

import config


class EcrStack(Stack):
    def __init__(self, scope: Construct, **kwargs) -> None:
        super().__init__(scope, "CDK-BRAIN-ECR", env=config.env, **kwargs)

        self.repository = ecr.Repository(
            self, "Repository",
            repository_name="project-brain",
            image_scan_on_push=True,
            image_tag_mutability=ecr.TagMutability.IMMUTABLE,
            lifecycle_rules=[
                ecr.LifecycleRule(max_image_count=10),
            ],
        )
        Tags.of(self.repository).add("Name", "project-brain-ecr")
