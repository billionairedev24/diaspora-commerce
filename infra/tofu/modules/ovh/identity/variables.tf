variable "services" {
  description = <<-EOT
    One entry per Kubernetes service account (same shape as the other clouds' identity modules; only the keys are used).
      namespace       - Kubernetes namespace (afrotransact-<env> by default)
      service_account - Kubernetes service account name (defaults to the service name, as in the Helm library chart)
      buckets         - ignored on OVH (no OVH buckets)
      secrets         - ignored on OVH
  EOT
  type = map(object({
    namespace       = string
    service_account = optional(string)
    buckets         = optional(list(string), [])
    secrets         = optional(bool, false)
  }))
}
