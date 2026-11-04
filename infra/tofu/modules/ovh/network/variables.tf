variable "cidr_block" {
  description = "Address space for the network. Subnets for cluster, data and edge are carved from it."
  type        = string
  default     = "10.20.0.0/16"
}

variable "zone_count" {
  description = "Availability zones to spread subnets over (1 for dev, 3 for prod)."
  type        = number
  default     = 3
  validation {
    condition     = var.zone_count >= 1 && var.zone_count <= 3
    error_message = "zone_count must be between 1 and 3."
  }
}

variable "egress" {
  description = <<-EOT
    Internet egress for workloads (Stripe, OpenRouter, Elastic Cloud, Confluent, carriers ...).
    Cloud APIs always use private endpoints, so NAT only carries third-party traffic.
      none     - no NAT at all (fully private; only private endpoints/PrivateLink)
      single   - one NAT for the whole network (dev, uat)
      per-zone - one NAT per zone (prod; survives a zone outage)
  EOT
  type        = string
  default     = "single"
  validation {
    condition     = contains(["none", "single", "per-zone"], var.egress)
    error_message = "egress must be none, single or per-zone."
  }
}
