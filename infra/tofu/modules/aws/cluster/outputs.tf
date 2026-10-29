output "cluster_name" {
  value = aws_eks_cluster.this.name
}

output "cluster_endpoint" {
  value = aws_eks_cluster.this.endpoint
}

output "cluster_ca_certificate" {
  description = "Base64 PEM of the cluster CA."
  value       = aws_eks_cluster.this.certificate_authority[0].data
}

output "oidc_issuer_url" {
  value = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

output "workload_identity" {
  description = "Handle for the identity module."
  value = {
    cloud             = "aws"
    cluster_name      = aws_eks_cluster.this.name
    oidc_provider_arn = aws_iam_openid_connect_provider.this.arn
    oidc_issuer_host  = local.oidc_host
    karpenter = {
      controller_role_arn = aws_iam_role.karpenter.arn
      node_role_name      = aws_iam_role.node.name
      interruption_queue  = aws_sqs_queue.karpenter.name
    }
  }
}

output "node_identity" {
  description = "Principal the nodes pull images as."
  value       = aws_iam_role.node.arn
}

output "kubeconfig_command" {
  value = "aws eks update-kubeconfig --region ${var.context.region} --name ${aws_eks_cluster.this.name}"
}
