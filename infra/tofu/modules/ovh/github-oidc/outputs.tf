output "login" {
  description = "Inputs for the cloud-login action. OVH has no OIDC: values are GitHub secrets named OVH_<ACCESS>_*."
  value = {
    cloud   = "ovh"
    secrets = var.access == "deploy" ? ["OVH_REGISTRY_USERNAME", "OVH_REGISTRY_PASSWORD"] : [for k in ["APPLICATION_KEY", "APPLICATION_SECRET", "CONSUMER_KEY", "S3_ACCESS_KEY", "S3_SECRET_KEY"] : "OVH_${local.identity}_${k}"]
  }
}

output "principal" {
  value = ""
}

output "provider_ref" {
  description = "Unused on OVH (no shared provider object); kept for interface parity."
  value       = ""
}
