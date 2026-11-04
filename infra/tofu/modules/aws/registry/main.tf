# AWS registry: one ECR repository per service under <name>/ (immutable tags, scan on push, KMS),
# lifecycle keeps the newest N images. Images are mirrored from GHCR by _deploy.yml after cosign verify.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 6.0, < 7.0" }
  }
}

data "aws_caller_identity" "this" {}

locals {
  tags      = merge(var.context.tags, { module = "registry" })
  namespace = "afrotransact"
  host      = "${data.aws_caller_identity.this.account_id}.dkr.ecr.${var.context.region}.amazonaws.com"
}

resource "aws_ecr_repository" "this" {
  for_each             = toset(var.repositories)
  name                 = "${local.namespace}/${each.key}"
  image_tag_mutability = var.immutable_tags ? "IMMUTABLE" : "MUTABLE"
  force_delete         = !var.context.deletion_protection
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "KMS"
  }
  tags = merge(local.tags, { service = each.key })
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = toset(var.repositories)
  repository = aws_ecr_repository.this[each.key].name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep the newest ${var.keep_last} images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = var.keep_last }
      action       = { type = "expire" }
    }]
  })
}

# Node role already has AmazonEC2ContainerRegistryPullOnly; extra pullers (other accounts/roles) get a repo policy.
data "aws_iam_policy_document" "pull" {
  count = length(var.reader_principals) > 0 ? 1 : 0
  statement {
    actions = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
    principals {
      type        = "AWS"
      identifiers = var.reader_principals
    }
  }
}

resource "aws_ecr_repository_policy" "pull" {
  for_each   = length(var.reader_principals) > 0 ? toset(var.repositories) : toset([])
  repository = aws_ecr_repository.this[each.key].name
  policy     = data.aws_iam_policy_document.pull[0].json
}
