from aws_cdk import Stack, Tags, aws_ec2 as ec2, aws_iam as iam, aws_secretsmanager as secretsmanager
from constructs import Construct

import config
from .network_stack import NetworkStack

NEO4J_VERSION = "5.24.0"
APOC_VERSION = "5.24.0"
APOC_SHA256 = "f47e93a97aa6c6c2ab58e19e78e8b45e4a8f54bf4cfa8ef0bf0ed3e20e76e982"

USER_DATA = r"""#!/bin/bash
set -euxo pipefail

dnf install -y java-21-amazon-corretto-headless jq awscli

rpm --import https://debian.neo4j.com/neotechnology.gpg.key
cat > /etc/yum.repos.d/neo4j.repo <<'EOF'
[neo4j]
name=Neo4j RPM Repository
baseurl=https://yum.neo4j.com/stable/5
enabled=1
gpgcheck=1
gpgkey=https://debian.neo4j.com/neotechnology.gpg.key
EOF

dnf install -y neo4j-__NEO4J_VERSION__

curl -fsSL -o /tmp/apoc.jar \
  https://github.com/neo4j/apoc/releases/download/__APOC_VERSION__/apoc-__APOC_VERSION__-core.jar
echo "__APOC_SHA256__  /tmp/apoc.jar" | sha256sum --check
mv /tmp/apoc.jar /var/lib/neo4j/plugins/apoc-__APOC_VERSION__-core.jar

if [ -b /dev/nvme1n1 ]; then
  mkfs.xfs /dev/nvme1n1
  mkdir -p /data
  mount /dev/nvme1n1 /data
  echo '/dev/nvme1n1 /data xfs defaults,nofail 0 2' >> /etc/fstab
  mkdir -p /data/neo4j
  chown -R neo4j:neo4j /data/neo4j
fi

sed -i 's|^#server.directories.data=.*|server.directories.data=/data/neo4j|' /etc/neo4j/neo4j.conf
sed -i 's|^#server.default_listen_address=.*|server.default_listen_address=0.0.0.0|' /etc/neo4j/neo4j.conf
sed -i 's|^#server.bolt.listen_address=.*|server.bolt.listen_address=0.0.0.0:7687|' /etc/neo4j/neo4j.conf
sed -i 's|^#server.http.listen_address=.*|server.http.listen_address=__HTTP_LISTEN__|' /etc/neo4j/neo4j.conf

cat >> /etc/neo4j/neo4j.conf <<'CONF'
dbms.security.procedures.unrestricted=apoc.meta.*,apoc.path.*
dbms.security.procedures.allowlist=apoc.meta.*,apoc.path.*
CONF

NEO4J_PASSWORD=$(aws secretsmanager get-secret-value \
  --region "__REGION__" \
  --secret-id "__SECRET_ARN__" \
  --query SecretString --output text | jq -r '.password')

neo4j-admin dbms set-initial-password "$NEO4J_PASSWORD"

systemctl enable neo4j
systemctl start neo4j
"""


class Neo4jStack(Stack):
    def __init__(self, scope: Construct, network: NetworkStack, **kwargs) -> None:
        environment = config.environment
        super().__init__(
            scope,
            f"CDK-{environment.name.upper()}-BRAIN-NEO4J",
            env=config.env,
            **kwargs,
        )

        prefix = f"{environment.name}-brain"

        self.secret = secretsmanager.Secret(
            self, "Neo4jSecret",
            secret_name=f"{prefix}/neo4j/master",
            description=f"{prefix} Neo4j password",
            generate_secret_string=secretsmanager.SecretStringGenerator(
                secret_string_template='{"username":"neo4j"}',
                generate_string_key="password",
                exclude_punctuation=True,
                password_length=32,
            ),
        )

        instance_role = iam.Role(
            self, "Neo4jInstanceRole",
            assumed_by=iam.ServicePrincipal("ec2.amazonaws.com"),
            managed_policies=[
                iam.ManagedPolicy.from_aws_managed_policy_name("AmazonSSMManagedInstanceCore"),
            ],
        )
        self.secret.grant_read(instance_role)

        http_listen = "0.0.0.0:7474" if environment.name == "dev" else "127.0.0.1:7474"

        user_data_script = (
            USER_DATA
            .replace("__NEO4J_VERSION__", NEO4J_VERSION)
            .replace("__APOC_VERSION__", APOC_VERSION)
            .replace("__APOC_SHA256__", APOC_SHA256)
            .replace("__SECRET_ARN__", self.secret.secret_arn)
            .replace("__REGION__", config.env["region"])
            .replace("__HTTP_LISTEN__", http_listen)
        )
        user_data = ec2.UserData.for_linux()
        user_data.add_commands(user_data_script)

        data_volume = ec2.BlockDevice(
            device_name="/dev/sdb",
            volume=ec2.BlockDeviceVolume.ebs(
                volume_size=environment.neo4j.ebs_size_gb or 100,
                volume_type=ec2.EbsDeviceVolumeType.GP3,
                encrypted=True,
                delete_on_termination=(environment.name != "prod"),
            ),
        )

        self.instance = ec2.Instance(
            self, "Neo4jInstance",
            instance_type=ec2.InstanceType(environment.neo4j.instance_type or "t3.medium"),
            machine_image=ec2.MachineImage.latest_amazon_linux2023(),
            vpc=network.vpc,
            vpc_subnets=network.subnet_selection,
            security_group=network.neo4j_sg,
            role=instance_role,
            block_devices=[data_volume],
            user_data=user_data,
        )
        Tags.of(self.instance).add("Name", f"{prefix}-neo4j")

        self.bolt_uri = f"bolt://{self.instance.instance_private_dns_name}:7687"
