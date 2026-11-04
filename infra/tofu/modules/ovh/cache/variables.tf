variable "network" {
  description = "The `network` output of the network module for the same cloud."
  type        = any
}

variable "engine_version" {
  description = "Valkey/Redis major version (\"8\"). Azure Cache for Redis only offers Redis 6 and ignores it."
  type        = string
  default     = "8"
}

variable "size" {
  description = "small | medium | large (sized to the cart/session working set)."
  type        = string
  default     = "small"
  validation {
    condition     = contains(["small", "medium", "large"], var.size)
    error_message = "size must be small, medium or large."
  }
}

variable "high_availability" {
  description = "Replica in another zone with automatic failover."
  type        = bool
  default     = false
}
