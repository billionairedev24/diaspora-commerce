variable "network" {
  description = "The `network` output of the network module for the same cloud."
  type        = any
}

variable "kubernetes_version" {
  description = "Kubernetes minor version (e.g. \"1.34\"). null = provider/cloud default channel."
  type        = string
  default     = null
}

variable "node_size" {
  description = "Size class of the arm64 on-demand pool: small | medium | large (mapped to Graviton / Axion / Cobalt). lean = ONE 4 vCPU / 16 GB node and nothing else (x86 e2 on GCP, arm64 on AWS/Azure): the single-node footprint of docs/engineering/cloud-cost.md. Pair it with zone_count 1 and egress "none"."
  type        = string
  default     = "small"
  validation {
    condition     = contains(["small", "medium", "large", "lean"], var.node_size)
    error_message = "node_size must be small, medium, large or lean."
  }
}

variable "min_nodes" {
  description = "Minimum nodes in the on-demand arm64 pool (per zone on GKE)."
  type        = number
  default     = 1
}

variable "max_nodes" {
  description = "Maximum nodes in the on-demand arm64 pool."
  type        = number
  default     = 3
}

variable "spot_enabled" {
  description = "Create the spot/preemptible arm64 pool tainted afrotransact.com/spot=true:NoSchedule."
  type        = bool
  default     = true
}

variable "spot_max_nodes" {
  description = "Maximum nodes in the spot pool (min is always 0 so it scales to zero)."
  type        = number
  default     = 10
}

variable "private_endpoint" {
  description = "Make the Kubernetes API private only (reach it via VPN/bastion/Tunnel)."
  type        = bool
  default     = false
}

variable "admin_principals" {
  description = "Cluster admins: IAM role ARNs (aws), IAM members like group:platform@afrotransact.com (gcp), Entra group object ids (azure)."
  type        = list(string)
  default     = []
}
