output "identities" {
  description = "Service => value for the service account annotation (role ARN)."
  value       = { for k, r in aws_iam_role.this : k => r.arn }
}

output "helm_values" {
  description = "Service => values fragment for platform-values.yaml (cloud + workloadIdentity)."
  value = { for k, r in aws_iam_role.this : k => {
    cloud            = "aws"
    workloadIdentity = { aws = { roleArn = r.arn } }
  } }
}
