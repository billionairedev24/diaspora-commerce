# AWS GitHub OIDC trust: IAM OIDC provider for token.actions.githubusercontent.com (once per account)
# and a role assumable only by the listed `sub` claims of this repository.
#   deploy -> push to the platform ECR repositories (image mirror)
#   plan   -> ReadOnlyAccess (+ state bucket read via the backend policy you attach)
#   apply  -> AdministratorAccess, only from the protected GitHub environment
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 6.0, < 7.0" }
  }
}

locals {
  tags         = merge(var.context.tags, { service = "ci", module = "github-oidc" })
  provider_arn = var.create_provider ? aws_iam_openid_connect_provider.github[0].arn : var.provider_ref
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_provider ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  tags           = local.tags
}

data "aws_iam_policy_document" "trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = var.subjects
    }
  }
}

resource "aws_iam_role" "this" {
  name                 = "${var.context.name}-github-${var.access}"
  assume_role_policy   = data.aws_iam_policy_document.trust.json
  max_session_duration = 3600
  tags                 = local.tags
}

data "aws_iam_policy_document" "deploy" {
  count = var.access == "deploy" ? 1 : 0
  statement {
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    actions = [
      "ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:CompleteLayerUpload",
      "ecr:DescribeImages", "ecr:GetDownloadUrlForLayer", "ecr:InitiateLayerUpload",
      "ecr:PutImage", "ecr:UploadLayerPart",
    ]
    resources = var.registry == null ? ["*"] : var.registry.repository_arns
  }
}

resource "aws_iam_role_policy" "deploy" {
  count  = var.access == "deploy" ? 1 : 0
  name   = "registry-push"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.deploy[0].json
}

resource "aws_iam_role_policy_attachment" "managed" {
  count      = var.access == "deploy" ? 0 : 1
  role       = aws_iam_role.this.name
  policy_arn = var.access == "plan" ? "arn:aws:iam::aws:policy/ReadOnlyAccess" : "arn:aws:iam::aws:policy/AdministratorAccess"
}
