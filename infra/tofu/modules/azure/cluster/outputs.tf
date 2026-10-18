output "cluster_name" {
  value = azurerm_kubernetes_cluster.this.name
}

output "cluster_endpoint" {
  value = "https://${var.private_endpoint ? azurerm_kubernetes_cluster.this.private_fqdn : azurerm_kubernetes_cluster.this.fqdn}"
}

output "cluster_ca_certificate" {
  description = "Base64 PEM of the cluster CA."
  value       = azurerm_kubernetes_cluster.this.kube_config[0].cluster_ca_certificate
  sensitive   = true
}

output "oidc_issuer_url" {
  value = azurerm_kubernetes_cluster.this.oidc_issuer_url
}

output "workload_identity" {
  description = "Handle for the identity module."
  value = {
    cloud               = "azure"
    cluster_name        = azurerm_kubernetes_cluster.this.name
    oidc_issuer_url     = azurerm_kubernetes_cluster.this.oidc_issuer_url
    resource_group_name = local.rg
    location            = var.context.region
  }
}

output "node_identity" {
  description = "Kubelet identity object id (AcrPull)."
  value       = azurerm_kubernetes_cluster.this.kubelet_identity[0].object_id
}

output "kubeconfig_command" {
  value = "az aks get-credentials --resource-group ${local.rg} --name ${azurerm_kubernetes_cluster.this.name} && kubelogin convert-kubeconfig -l azurecli"
}
