from aws_cdk import Stack, Tags, aws_ec2 as ec2
from constructs import Construct

import config


class NetworkStack(Stack):
    def __init__(self, scope: Construct, **kwargs) -> None:
        environment = config.environment
        super().__init__(
            scope,
            f"CDK-{environment.name.upper()}-BRAIN-NETWORK",
            env=config.env,
            **kwargs,
        )

        prefix = f"{environment.name}-brain"
        self.vpc = ec2.Vpc.from_lookup(self, "VPC", vpc_id=environment.vpc.id)

        self.private_subnets = []
        for index, cfg in enumerate(environment.vpc.subnets):
            az = f"{config.env['region']}{chr(ord('a') + index)}"
            subnet = ec2.PrivateSubnet(
                self, f"Subnet{index}",
                availability_zone=az,
                cidr_block=cfg.cidr,
                vpc_id=self.vpc.vpc_id,
            )
            subnet.node.try_remove_child("RouteTable")
            for child in subnet.node.children:
                if isinstance(child, ec2.CfnSubnetRouteTableAssociation):
                    child.add_override("Properties.RouteTableId", cfg.route_table)
                    break
            Tags.of(subnet).add("Name", f"{prefix}-subnet-{index}")
            self.private_subnets.append(subnet)

        self.subnet_selection = ec2.SubnetSelection(subnets=self.private_subnets)

        self.alb_sg = ec2.SecurityGroup(
            self, "AlbSg",
            vpc=self.vpc,
            security_group_name=f"{prefix}-alb",
            description=f"{prefix} ALB",
            allow_all_outbound=False,
        )
        self.alb_sg.add_ingress_rule(ec2.Peer.any_ipv4(), ec2.Port.tcp(443), "HTTPS")
        Tags.of(self.alb_sg).add("Name", f"{prefix}-alb-sg")

        self.ecs_sg = ec2.SecurityGroup(
            self, "EcsSg",
            vpc=self.vpc,
            security_group_name=f"{prefix}-ecs",
            description=f"{prefix} ECS tasks",
            allow_all_outbound=True,
        )
        self.ecs_sg.add_ingress_rule(self.alb_sg, ec2.Port.tcp(8080), "ALB -> ECS")
        Tags.of(self.ecs_sg).add("Name", f"{prefix}-ecs-sg")

        self.alb_sg.add_egress_rule(self.ecs_sg, ec2.Port.tcp(8080), "ALB -> ECS")

        self.neo4j_sg = ec2.SecurityGroup(
            self, "Neo4jSg",
            vpc=self.vpc,
            security_group_name=f"{prefix}-neo4j",
            description=f"{prefix} Neo4j",
            allow_all_outbound=True,
        )
        self.neo4j_sg.add_ingress_rule(self.ecs_sg, ec2.Port.tcp(7687), "Bolt")
        if environment.name == "dev":
            self.neo4j_sg.add_ingress_rule(self.ecs_sg, ec2.Port.tcp(7474), "HTTP browser")
        Tags.of(self.neo4j_sg).add("Name", f"{prefix}-neo4j-sg")
