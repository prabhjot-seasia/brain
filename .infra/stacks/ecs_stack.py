from aws_cdk import (
    Duration, Stack, Tags,
    aws_ec2 as ec2, aws_ecs as ecs, aws_elasticloadbalancingv2 as elbv2,
    aws_iam as iam, aws_logs as logs, aws_certificatemanager as acm,
)
from constructs import Construct

import config
from .ecr_stack import EcrStack
from .neo4j_stack import Neo4jStack
from .network_stack import NetworkStack


class EcsStack(Stack):
    def __init__(
        self,
        scope: Construct,
        network: NetworkStack,
        neo4j: Neo4jStack,
        ecr: EcrStack,
        **kwargs,
    ) -> None:
        environment = config.environment
        super().__init__(
            scope,
            f"CDK-{environment.name.upper()}-BRAIN-ECS",
            env=config.env,
            **kwargs,
        )

        prefix = f"{environment.name}-brain"

        cluster = ecs.Cluster(
            self, "Cluster",
            cluster_name=f"{prefix}-cluster",
            vpc=network.vpc,
            container_insights=True,
        )

        log_group = logs.LogGroup(
            self, "LogGroup",
            log_group_name=f"/ecs/project-brain-{environment.name}",
            retention=logs.RetentionDays.ONE_MONTH,
        )

        execution_role = iam.Role(
            self, "TaskExecutionRole",
            role_name=f"{prefix}-task-execution",
            assumed_by=iam.ServicePrincipal("ecs-tasks.amazonaws.com"),
            managed_policies=[
                iam.ManagedPolicy.from_aws_managed_policy_name(
                    "service-role/AmazonECSTaskExecutionRolePolicy"
                ),
            ],
        )

        bedrock_arns = [
            f"arn:aws:bedrock:*::foundation-model/{mid}"
            for mid in [
                environment.bedrock.embedding_model or "amazon.titan-embed-text-v2:0",
                environment.bedrock.plan_model or "anthropic.claude-sonnet-4-5-v1:0",
                environment.bedrock.extract_model or "anthropic.claude-haiku-4-5-v1:0",
            ]
        ]

        task_role = iam.Role(
            self, "TaskRole",
            role_name=f"{prefix}-task",
            assumed_by=iam.ServicePrincipal("ecs-tasks.amazonaws.com"),
            inline_policies={
                "bedrock-invoke": iam.PolicyDocument(
                    statements=[
                        iam.PolicyStatement(
                            actions=["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"],
                            resources=bedrock_arns,
                        ),
                    ]
                ),
            },
        )

        neo4j.secret.grant_read(task_role)
        neo4j.secret.grant_read(execution_role)

        image_tag = self.node.try_get_context("image_tag")
        if not image_tag:
            raise ValueError("image_tag context is required: cdk deploy -c image_tag=0.1.0")

        task_def = ecs.FargateTaskDefinition(
            self, "TaskDef",
            family=prefix,
            cpu=environment.ecs.task_cpu or 1024,
            memory_limit_mib=environment.ecs.task_memory or 2048,
            execution_role=execution_role,
            task_role=task_role,
            runtime_platform=ecs.RuntimePlatform(
                operating_system_family=ecs.OperatingSystemFamily.LINUX,
                cpu_architecture=ecs.CpuArchitecture.X86_64,
            ),
        )

        env_vars = {
            **dict(environment.env_vars),
            "SPRING_NEO4J_URI": neo4j.bolt_uri,
            "SPRING_NEO4J_AUTHENTICATION_USERNAME": "neo4j",
            "AWS_REGION": config.env["region"],
            "SPRING_AI_BEDROCK_AWS_REGION": config.env["region"],
        }

        secrets = {
            "SPRING_NEO4J_AUTHENTICATION_PASSWORD": ecs.Secret.from_secrets_manager(
                neo4j.secret, field="password"
            ),
        }

        container = task_def.add_container(
            "App",
            container_name="project-brain",
            image=ecs.ContainerImage.from_ecr_repository(ecr.repository, tag=image_tag),
            environment=env_vars,
            secrets=secrets,
            logging=ecs.LogDriver.aws_logs(stream_prefix="brain", log_group=log_group),
            health_check=ecs.HealthCheck(
                command=["CMD-SHELL", "wget -q --spider http://localhost:8080/project-brain-backend/actuator/health || exit 1"],
                interval=Duration.seconds(30),
                timeout=Duration.seconds(5),
                retries=3,
                start_period=Duration.seconds(120),
            ),
        )
        container.add_port_mappings(ecs.PortMapping(container_port=8080))

        self.service = ecs.FargateService(
            self, "Service",
            cluster=cluster,
            task_definition=task_def,
            service_name=prefix,
            desired_count=environment.ecs.desired_count or 1,
            vpc_subnets=network.subnet_selection,
            security_groups=[network.ecs_sg],
            assign_public_ip=False,
            health_check_grace_period=Duration.seconds(180),
            enable_execute_command=(environment.name == "dev"),
            min_healthy_percent=100,
            max_healthy_percent=200,
        )

        alb = elbv2.ApplicationLoadBalancer(
            self, "Alb",
            load_balancer_name=f"{prefix}-alb",
            vpc=network.vpc,
            internet_facing=True,
            security_group=network.alb_sg,
            vpc_subnets=ec2.SubnetSelection(subnet_type=ec2.SubnetType.PUBLIC),
        )

        certificate = acm.Certificate.from_certificate_arn(
            self, "AlbCert", environment.api.certificate
        )

        alb.add_listener(
            "HttpRedirect",
            port=80,
            default_action=elbv2.ListenerAction.redirect(
                protocol="HTTPS",
                port="443",
                permanent=True,
            ),
        )

        listener = alb.add_listener(
            "HttpsListener",
            port=443,
            certificates=[certificate],
            ssl_policy=elbv2.SslPolicy.RECOMMENDED,
        )

        listener.add_targets(
            "EcsTarget",
            port=8080,
            protocol=elbv2.ApplicationProtocol.HTTP,
            targets=[self.service],
            health_check=elbv2.HealthCheck(
                path="/project-brain-backend/actuator/health",
                interval=Duration.seconds(30),
                timeout=Duration.seconds(5),
                healthy_threshold_count=2,
                unhealthy_threshold_count=3,
            ),
            deregistration_delay=Duration.seconds(30),
        )

        Tags.of(self.service).add("Name", f"{prefix}-ecs-service")
        Tags.of(alb).add("Name", f"{prefix}-alb")
