variable "cluster" {
  description = "The `workload_identity` output of the cluster module."
  type        = any
}

variable "services" {
  description = <<-EOT
    One cloud identity per Kubernetes service account.
      namespace       - Kubernetes namespace (afrotransact-<env> by default)
      service_account - Kubernetes service account name (defaults to the service name, as in the Helm library chart)
      buckets         - bucket keys (from the bucket module) the service may read/write
      secrets         - may read every secret under the env prefix (only External Secrets Operator needs this)
  EOT
  type = map(object({
    namespace       = string
    service_account = optional(string)
    buckets         = optional(list(string), [])
    secrets         = optional(bool, false)
  }))
}

variable "buckets" {
  description = "The `buckets` output of the bucket module."
  type        = any
  default     = {}
}

variable "secret_store" {
  description = "The `store` output of the secrets module."
  type        = any
  default     = null
}
