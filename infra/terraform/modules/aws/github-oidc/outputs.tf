output "login" {
  description = "Inputs for the cloud login action (store as GitHub environment variables)."
  value = {
    cloud    = "aws"
    role_arn = aws_iam_role.this.arn
    region   = var.context.region
  }
}

output "principal" {
  value = aws_iam_role.this.arn
}

output "provider_ref" {
  description = "Pass to other instances with create_provider = false."
  value       = local.provider_arn
}
