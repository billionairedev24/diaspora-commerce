variable "network" {
  description = "The `network` output of the network module for the same cloud."
  type        = any
}

variable "engine_version" {
  description = "PostgreSQL major version."
  type        = string
  default     = "17"
}

variable "size" {
  description = "small (dev, scale-to-zero where supported) | medium (uat) | large (prod)."
  type        = string
  default     = "small"
  validation {
    condition     = contains(["small", "medium", "large"], var.size)
    error_message = "size must be small, medium or large."
  }
}

variable "high_availability" {
  description = "Synchronous standby in another zone (Aurora reader, Cloud SQL REGIONAL, Azure zone-redundant HA)."
  type        = bool
  default     = false
}

variable "storage_gb" {
  description = "Initial storage (auto-grows where the engine supports it; ignored by Aurora)."
  type        = number
  default     = 32
}

variable "backup_retention_days" {
  description = "Automated backup / PITR retention."
  type        = number
  default     = 7
}

variable "databases" {
  description = "One database per service (PORTS.md). Created natively on GCP/Azure; on AWS by the bootstrap job (scripts/postgres-bootstrap.sh)."
  type        = list(string)
  default     = []
}

variable "secret_store" {
  description = "The `store` output of the secrets module. Used to keep the admin credential where the engine cannot manage it itself (GCP, Azure)."
  type        = any
  default     = null
}
