variable "network" {
  description = "The `network` output of the network module (Azure uses it for the private endpoint). May be null."
  type        = any
  default     = null
}

variable "buckets" {
  description = <<-EOT
    Private buckets keyed by short name (media, labels, exports, kyc-evidence).
      versioning         - keep object versions
      object_lock        - WORM: S3 Object Lock / GCS retention policy / Azure immutability policy
      retention_days     - WORM retention period (object_lock only); locked (compliance) when deletion_protection
      archive_after_days - move to infrequent/archive tier after N days (0 = never)
      expire_after_days  - delete objects after N days (0 = never)
      cors_origins       - browser origins allowed to PUT via signed URLs
  EOT
  type = map(object({
    versioning         = optional(bool, true)
    object_lock        = optional(bool, false)
    retention_days     = optional(number, 0)
    archive_after_days = optional(number, 0)
    expire_after_days  = optional(number, 0)
    cors_origins       = optional(list(string), [])
  }))
}
