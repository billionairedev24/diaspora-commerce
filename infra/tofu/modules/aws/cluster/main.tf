# AWS cluster: EKS with
#   - "general" managed node group: Graviton (arm64) on-demand, the steady baseline (commit Savings Plans here)
#   - "spot" managed node group: Graviton spot, tainted afrotransact.com/spot=true:NoSchedule, scales to zero
#   - Karpenter prerequisites (node role + access entry, interruption queue, controller IRSA role,
#     discovery tags) so Karpenter NodePools (infra/k8s) can take over bin-packing.
#   - IRSA OIDC provider, KMS envelope encryption of Kubernetes secrets, API-mode access entries.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 6.0, < 7.0" }
  }
}

data "aws_partition" "this" {}
data "aws_caller_identity" "this" {}

locals {
  name = var.context.name
  tags = merge(var.context.tags, { service = "platform", module = "cluster" })
  instance_types = {
    lean   = ["t4g.xlarge"]
    small  = ["t4g.large", "m7g.large"]
    medium = ["m7g.xlarge", "m8g.xlarge"]
    large  = ["m7g.2xlarge", "m8g.2xlarge"]
  }
  spot_instance_types = ["m7g.large", "m7g.xlarge", "m8g.large", "m8g.xlarge", "c7g.xlarge", "r7g.large"]
  partition           = data.aws_partition.this.partition
}

# ---------------------------------------------------------------- encryption
resource "aws_kms_key" "eks" {
  description             = "${local.name} EKS secrets envelope encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = local.tags
}

# ---------------------------------------------------------------- control plane
data "aws_iam_policy_document" "eks_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  name               = "${local.name}-eks-cluster"
  assume_role_policy = data.aws_iam_policy_document.eks_assume.json
  tags               = local.tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_eks_cluster" "this" {
  name                          = local.name
  version                       = var.kubernetes_version
  role_arn                      = aws_iam_role.cluster.arn
  enabled_cluster_log_types     = ["api", "audit", "authenticator"]
  bootstrap_self_managed_addons = false
  deletion_protection           = var.context.deletion_protection

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = true
  }

  vpc_config {
    subnet_ids              = var.network.cluster_subnet_ids
    endpoint_private_access = true
    endpoint_public_access  = !var.private_endpoint
    public_access_cidrs     = var.private_endpoint ? null : (length(var.context.admin_cidrs) > 0 ? var.context.admin_cidrs : ["0.0.0.0/0"])
  }

  encryption_config {
    resources = ["secrets"]
    provider {
      key_arn = aws_kms_key.eks.arn
    }
  }

  upgrade_policy {
    support_type = var.context.environment == "prod" ? "EXTENDED" : "STANDARD"
  }

  tags       = merge(local.tags, { "karpenter.sh/discovery" = local.name })
  depends_on = [aws_iam_role_policy_attachment.cluster]
}

# IRSA: per-service roles (identity module) trust this provider.
resource "aws_iam_openid_connect_provider" "this" {
  url            = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list = ["sts.amazonaws.com"]
  tags           = local.tags
}

resource "aws_eks_access_entry" "admins" {
  for_each      = toset(var.admin_principals)
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
  tags          = local.tags
}

resource "aws_eks_access_policy_association" "admins" {
  for_each      = toset(var.admin_principals)
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
  policy_arn    = "arn:${local.partition}:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"
  access_scope {
    type = "cluster"
  }
  depends_on = [aws_eks_access_entry.admins]
}

# ---------------------------------------------------------------- nodes
data "aws_iam_policy_document" "node_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

# Shared by managed node groups and Karpenter-launched nodes.
resource "aws_iam_role" "node" {
  name               = "${local.name}-eks-node"
  assume_role_policy = data.aws_iam_policy_document.node_assume.json
  tags               = local.tags
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "AmazonEKSWorkerNodePolicy",
    "AmazonEKS_CNI_Policy",
    "AmazonEC2ContainerRegistryPullOnly",
    "AmazonSSMManagedInstanceCore",
  ])
  role       = aws_iam_role.node.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/${each.value}"
}

resource "aws_iam_instance_profile" "node" {
  name = "${local.name}-eks-node"
  role = aws_iam_role.node.name
  tags = local.tags
}

# Karpenter-launched nodes join via an EC2_LINUX access entry on the shared node role.
resource "aws_eks_access_entry" "karpenter_nodes" {
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = aws_iam_role.node.arn
  type          = "EC2_LINUX"
  tags          = local.tags
}

resource "aws_launch_template" "node" {
  name_prefix            = "${local.name}-node-"
  update_default_version = true
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
    http_endpoint               = "enabled"
  }
  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_size = 50
      volume_type = "gp3"
      encrypted   = true
    }
  }
  tag_specifications {
    resource_type = "instance"
    tags          = local.tags
  }
  tags = local.tags
}

resource "aws_eks_node_group" "general" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "general-arm64"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = slice(var.network.cluster_subnet_ids, 0, var.network.zone_count)
  ami_type        = "AL2023_ARM_64_STANDARD"
  capacity_type   = "ON_DEMAND"
  instance_types  = local.instance_types[var.node_size]

  scaling_config {
    min_size     = var.min_nodes
    max_size     = var.max_nodes
    desired_size = var.min_nodes
  }

  launch_template {
    id      = aws_launch_template.node.id
    version = aws_launch_template.node.latest_version
  }

  labels = {
    "afrotransact.com/pool"  = "general"
    "karpenter.sh/discovery" = local.name
  }

  update_config {
    max_unavailable = 1
  }

  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }

  tags       = local.tags
  depends_on = [aws_iam_role_policy_attachment.node, aws_eks_addon.pre_compute]
}

resource "aws_eks_node_group" "spot" {
  count           = var.spot_enabled ? 1 : 0
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "spot-arm64"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = slice(var.network.cluster_subnet_ids, 0, var.network.zone_count)
  ami_type        = "AL2023_ARM_64_STANDARD"
  capacity_type   = "SPOT"
  instance_types  = local.spot_instance_types

  scaling_config {
    min_size     = 0
    max_size     = var.spot_max_nodes
    desired_size = 0
  }

  launch_template {
    id      = aws_launch_template.node.id
    version = aws_launch_template.node.latest_version
  }

  labels = { "afrotransact.com/pool" = "spot" }

  taint {
    key    = "afrotransact.com/spot"
    value  = "true"
    effect = "NO_SCHEDULE"
  }

  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }

  tags       = local.tags
  depends_on = [aws_iam_role_policy_attachment.node, aws_eks_addon.pre_compute]
}

# ---------------------------------------------------------------- add-ons
resource "aws_eks_addon" "pre_compute" {
  for_each                    = toset(["vpc-cni", "kube-proxy", "eks-pod-identity-agent"])
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = each.value
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  configuration_values        = each.value == "vpc-cni" ? jsonencode({ env = { ENABLE_PREFIX_DELEGATION = "true" } }) : null
  tags                        = local.tags
}

resource "aws_eks_addon" "post_compute" {
  for_each                    = toset(["coredns", "aws-ebs-csi-driver"])
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = each.value
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  service_account_role_arn    = each.value == "aws-ebs-csi-driver" ? aws_iam_role.ebs_csi.arn : null
  tags                        = local.tags
  depends_on                  = [aws_eks_node_group.general]
}

locals {
  oidc_host = replace(aws_iam_openid_connect_provider.this.url, "https://", "")
}

data "aws_iam_policy_document" "irsa" {
  for_each = {
    ebs       = "kube-system:ebs-csi-controller-sa"
    karpenter = "kube-system:karpenter"
  }
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.this.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:sub"
      values   = ["system:serviceaccount:${each.value}"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ebs_csi" {
  name               = "${local.name}-ebs-csi"
  assume_role_policy = data.aws_iam_policy_document.irsa["ebs"].json
  tags               = local.tags
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.ebs_csi.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

# ---------------------------------------------------------------- Karpenter prerequisites
resource "aws_sqs_queue" "karpenter" {
  name                      = "${local.name}-karpenter"
  message_retention_seconds = 300
  sqs_managed_sse_enabled   = true
  tags                      = local.tags
}

data "aws_iam_policy_document" "karpenter_queue" {
  statement {
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.karpenter.arn]
    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com", "sqs.amazonaws.com"]
    }
  }
}

resource "aws_sqs_queue_policy" "karpenter" {
  queue_url = aws_sqs_queue.karpenter.url
  policy    = data.aws_iam_policy_document.karpenter_queue.json
}

locals {
  karpenter_events = {
    spot_interruption = { source = ["aws.ec2"], detail-type = ["EC2 Spot Instance Interruption Warning"] }
    rebalance         = { source = ["aws.ec2"], detail-type = ["EC2 Instance Rebalance Recommendation"] }
    state_change      = { source = ["aws.ec2"], detail-type = ["EC2 Instance State-change Notification"] }
    health            = { source = ["aws.health"], detail-type = ["AWS Health Event"] }
  }
}

resource "aws_cloudwatch_event_rule" "karpenter" {
  for_each      = local.karpenter_events
  name          = "${local.name}-karpenter-${replace(each.key, "_", "-")}"
  event_pattern = jsonencode(each.value)
  tags          = local.tags
}

resource "aws_cloudwatch_event_target" "karpenter" {
  for_each = local.karpenter_events
  rule     = aws_cloudwatch_event_rule.karpenter[each.key].name
  arn      = aws_sqs_queue.karpenter.arn
}

data "aws_iam_policy_document" "karpenter_controller" {
  statement {
    sid = "Provisioning"
    actions = [
      "ec2:CreateFleet", "ec2:CreateLaunchTemplate", "ec2:CreateTags", "ec2:RunInstances",
      "ec2:DeleteLaunchTemplate", "ec2:TerminateInstances",
    ]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/karpenter.sh/discovery"
      values   = [local.name]
    }
  }
  statement {
    sid = "ProvisioningUntagged"
    actions = [
      "ec2:CreateFleet", "ec2:RunInstances", "ec2:CreateLaunchTemplate", "ec2:CreateTags",
    ]
    resources = [
      "arn:${local.partition}:ec2:${var.context.region}::image/*",
      "arn:${local.partition}:ec2:${var.context.region}::snapshot/*",
      "arn:${local.partition}:ec2:${var.context.region}:*:subnet/*",
      "arn:${local.partition}:ec2:${var.context.region}:*:security-group/*",
      "arn:${local.partition}:ec2:${var.context.region}:*:launch-template/*",
      "arn:${local.partition}:ec2:${var.context.region}:*:fleet/*",
      "arn:${local.partition}:ec2:${var.context.region}:*:instance/*",
      "arn:${local.partition}:ec2:${var.context.region}:*:volume/*",
      "arn:${local.partition}:ec2:${var.context.region}:*:network-interface/*",
      "arn:${local.partition}:ec2:${var.context.region}:*:spot-instances-request/*",
    ]
  }
  statement {
    sid = "Read"
    actions = [
      "ec2:DescribeAvailabilityZones", "ec2:DescribeImages", "ec2:DescribeInstances",
      "ec2:DescribeInstanceTypeOfferings", "ec2:DescribeInstanceTypes", "ec2:DescribeLaunchTemplates",
      "ec2:DescribeSecurityGroups", "ec2:DescribeSpotPriceHistory", "ec2:DescribeSubnets",
      "pricing:GetProducts", "ssm:GetParameter", "eks:DescribeCluster",
    ]
    resources = ["*"]
  }
  statement {
    sid       = "PassNodeRole"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.node.arn]
  }
  statement {
    sid = "InstanceProfiles"
    actions = [
      "iam:GetInstanceProfile", "iam:CreateInstanceProfile", "iam:TagInstanceProfile",
      "iam:AddRoleToInstanceProfile", "iam:RemoveRoleFromInstanceProfile", "iam:DeleteInstanceProfile",
      "iam:ListInstanceProfiles",
    ]
    resources = ["*"]
  }
  statement {
    sid       = "Interruption"
    actions   = ["sqs:DeleteMessage", "sqs:GetQueueUrl", "sqs:ReceiveMessage"]
    resources = [aws_sqs_queue.karpenter.arn]
  }
}

resource "aws_iam_role" "karpenter" {
  name               = "${local.name}-karpenter"
  assume_role_policy = data.aws_iam_policy_document.irsa["karpenter"].json
  tags               = local.tags
}

resource "aws_iam_role_policy" "karpenter" {
  name   = "karpenter-controller"
  role   = aws_iam_role.karpenter.id
  policy = data.aws_iam_policy_document.karpenter_controller.json
}

# Tag the cluster security group so Karpenter EC2NodeClass can discover it.
resource "aws_ec2_tag" "cluster_sg_discovery" {
  resource_id = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
  key         = "karpenter.sh/discovery"
  value       = local.name
}
