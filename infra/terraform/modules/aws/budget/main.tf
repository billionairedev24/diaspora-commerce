# AWS budget (S182): one monthly COST budget per environment with ACTUAL and FORECASTED alerts to email and an SNS
# topic (wire the topic to Slack/PagerDuty via AWS Chatbot or a subscription). Prefer one AWS account per
# environment; when several environments share an account (shared_account = true) the budget filters on the cost
# allocation tag `env`, which must be activated once per payer account (Billing -> Cost allocation tags) after the
# first tagged resources exist, otherwise the budget reads 0.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 6.0, < 7.0" }
  }
}

data "aws_caller_identity" "this" {}
data "aws_partition" "this" {}

locals {
  tags = merge(var.context.tags, { service = "platform", module = "budget" })
  name = "${var.context.name}-monthly"
}

# Budgets can only publish to SNS topics whose key policy lets budgets.amazonaws.com use the key, so the topic gets
# its own CMK (the AWS-managed alias/aws/sns key cannot be shared with the Budgets service).
resource "aws_kms_key" "alerts" {
  description         = "${local.name} budget alerts topic"
  enable_key_rotation = true
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "Account"
        Effect    = "Allow"
        Principal = { AWS = "arn:${data.aws_partition.this.partition}:iam::${data.aws_caller_identity.this.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "Budgets"
        Effect    = "Allow"
        Principal = { Service = "budgets.amazonaws.com" }
        Action    = ["kms:GenerateDataKey*", "kms:Decrypt"]
        Resource  = "*"
      },
    ]
  })
  tags = local.tags
}

resource "aws_sns_topic" "alerts" {
  name              = "${local.name}-budget-alerts"
  kms_master_key_id = aws_kms_key.alerts.arn
  tags              = local.tags
}

resource "aws_sns_topic_policy" "alerts" {
  arn = aws_sns_topic.alerts.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AWSBudgetsPublish"
      Effect    = "Allow"
      Principal = { Service = "budgets.amazonaws.com" }
      Action    = "SNS:Publish"
      Resource  = aws_sns_topic.alerts.arn
      Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.this.account_id } }
    }]
  })
}

resource "aws_budgets_budget" "this" {
  name         = local.name
  budget_type  = "COST"
  limit_amount = format("%.2f", var.monthly_limit)
  limit_unit   = var.currency
  time_unit    = "MONTHLY"

  dynamic "cost_filter" {
    for_each = var.shared_account ? [1] : []
    content {
      name   = "TagKeyValue"
      values = ["user:env$${var.context.environment}"]
    }
  }

  dynamic "notification" {
    for_each = toset(var.actual_thresholds)
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = var.notification_emails
      subscriber_sns_topic_arns  = [aws_sns_topic.alerts.arn]
    }
  }

  dynamic "notification" {
    for_each = toset(var.forecast_thresholds)
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "FORECASTED"
      subscriber_email_addresses = var.notification_emails
      subscriber_sns_topic_arns  = [aws_sns_topic.alerts.arn]
    }
  }

  tags       = local.tags
  depends_on = [aws_sns_topic_policy.alerts]
}
