# AWS network: VPC with private cluster + data subnets, tiny public subnets (load balancer / NAT only),
# gateway endpoints for S3, interface endpoints for every AWS API the platform calls, so AWS traffic
# never touches a NAT gateway. NAT is optional (var.egress) and only carries third-party SaaS traffic.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 6.0, < 7.0" }
  }
}

data "aws_availability_zones" "this" {
  state = "available"
}

locals {
  # EKS wants subnets in at least two zones even when the workload runs in one (zone_count = 1): the second is empty and free.
  azs = slice(data.aws_availability_zones.this.names, 0, max(var.zone_count, 2))
  # egress "none": no NAT gateway (USD 33/month) and no interface endpoints (~USD 7/month each): nodes get public
  # addresses and reach the internet through the gateway; inbound stays closed (default-deny groups, Cloudflare Tunnel).
  public_nodes = var.egress == "none"
  tags         = merge(var.context.tags, { service = "platform", module = "network" })
  # /16 -> cluster /19 x3 (8k IPs each for pods with prefix delegation), data /22 x3, public /24 x3
  cluster_cidrs = [for i, _ in local.azs : cidrsubnet(var.cidr_block, 3, i)]
  data_cidrs    = [for i, _ in local.azs : cidrsubnet(var.cidr_block, 6, 32 + i)]
  public_cidrs  = [for i, _ in local.azs : cidrsubnet(var.cidr_block, 8, 160 + i)]
  nat_count     = var.egress == "none" ? 0 : (var.egress == "single" ? 1 : length(local.azs))
  # Interface endpoints that keep EKS, ECR pulls, IRSA, Karpenter, ESO and logging off the NAT.
  interface_endpoints = ["ecr.api", "ecr.dkr", "sts", "ec2", "eks", "eks-auth", "secretsmanager", "kms", "logs", "sqs", "elasticloadbalancing"]
}

resource "aws_vpc" "this" {
  cidr_block           = var.cidr_block
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(local.tags, { Name = var.context.name })
}

# Lock down the default security group (no rules = no traffic).
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(local.tags, { Name = "${var.context.name}-default-deny" })
}

resource "aws_subnet" "cluster" {
  count                   = length(local.azs)
  vpc_id                  = aws_vpc.this.id
  availability_zone       = local.azs[count.index]
  cidr_block              = local.cluster_cidrs[count.index]
  map_public_ip_on_launch = local.public_nodes
  tags = merge(local.tags, {
    Name                              = "${var.context.name}-cluster-${local.azs[count.index]}"
    tier                              = "cluster"
    "kubernetes.io/role/internal-elb" = "1"
    "karpenter.sh/discovery"          = var.context.name
  })
}

resource "aws_subnet" "data" {
  count             = length(local.azs)
  vpc_id            = aws_vpc.this.id
  availability_zone = local.azs[count.index]
  cidr_block        = local.data_cidrs[count.index]
  tags              = merge(local.tags, { Name = "${var.context.name}-data-${local.azs[count.index]}", tier = "data" })
}

resource "aws_subnet" "public" {
  count                   = length(local.azs)
  vpc_id                  = aws_vpc.this.id
  availability_zone       = local.azs[count.index]
  cidr_block              = local.public_cidrs[count.index]
  map_public_ip_on_launch = false
  tags = merge(local.tags, {
    Name                     = "${var.context.name}-public-${local.azs[count.index]}"
    tier                     = "public"
    "kubernetes.io/role/elb" = "1"
  })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(local.tags, { Name = var.context.name })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = merge(local.tags, { Name = "${var.context.name}-public" })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count          = length(local.azs)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  count  = local.nat_count
  domain = "vpc"
  tags   = merge(local.tags, { Name = "${var.context.name}-nat-${count.index}" })
}

resource "aws_nat_gateway" "this" {
  count         = local.nat_count
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  tags          = merge(local.tags, { Name = "${var.context.name}-nat-${count.index}" })
  depends_on    = [aws_internet_gateway.this]
}

# One private route table per AZ so per-zone NAT keeps traffic in-zone.
resource "aws_route_table" "private" {
  count  = length(local.azs)
  vpc_id = aws_vpc.this.id
  tags   = merge(local.tags, { Name = "${var.context.name}-private-${local.azs[count.index]}" })
}

resource "aws_route" "private_nat" {
  count                  = local.nat_count == 0 ? 0 : length(local.azs)
  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[local.nat_count == 1 ? 0 : count.index].id
}

resource "aws_route" "private_igw" {
  count                  = local.public_nodes ? length(local.azs) : 0
  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "cluster" {
  count          = length(local.azs)
  subnet_id      = aws_subnet.cluster[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

resource "aws_route_table_association" "data" {
  count          = length(local.azs)
  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# Gateway endpoint: S3 traffic (ECR layers, media, exports) is free and never hits NAT.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.context.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.private[*].id
  tags              = merge(local.tags, { Name = "${var.context.name}-s3" })
}

resource "aws_security_group" "endpoints" {
  name        = "${var.context.name}-vpc-endpoints"
  description = "HTTPS from inside the VPC to AWS interface endpoints"
  vpc_id      = aws_vpc.this.id
  tags        = local.tags
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_https" {
  security_group_id = aws_security_group.endpoints.id
  description       = "HTTPS from the VPC"
  cidr_ipv4         = var.cidr_block
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_endpoint" "interface" {
  for_each            = local.public_nodes ? toset([]) : toset(local.interface_endpoints)
  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${var.context.region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = aws_subnet.cluster[*].id
  security_group_ids  = [aws_security_group.endpoints.id]
  tags                = merge(local.tags, { Name = "${var.context.name}-${each.key}" })
}
