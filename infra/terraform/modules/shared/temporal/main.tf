# Temporal Cloud namespace per environment (temporalio/temporalcloud), the same on aws | gcp | azure (S249).
# The namespace is "<context.name>" (afrotransact-dev | -uat | -prod), matching the TEMPORAL_NAMESPACE default of
# orders-service; Temporal Cloud appends the account id ("afrotransact-dev.a1b2c"), which is what clients use.
#   auth          mTLS with our own client CA (accepted_client_ca, base64 PEM) - what orders-service's
#                 application-<env>.yml expects (TEMPORAL_TLS_CERT / TEMPORAL_TLS_KEY); without a CA the
#                 namespace uses API keys instead.
#   region        Temporal Cloud region next to the cluster: "<cloud>-<region>" (aws-us-east-1, gcp-us-east4). Azure
#                 clusters pass temporal_region explicitly (pick the closest AWS/GCP region Temporal Cloud offers
#                 unless an Azure region is available on the account).
#   retention     small 3 days (dev), medium 14 (uat), large 30 (prod); delete protection follows
#                 context.deletion_protection.
# Credentials: TEMPORAL_CLOUD_API_KEY (an account API key with the Account Owner / Namespace Admin role).
terraform {
  required_version = ">= 1.8"
  required_providers {
    temporalcloud = { source = "temporalio/temporalcloud", version = ">= 1.0, < 2.0" }
  }
}

variable "context" {
  description = "Same object as the cloud modules (name, environment, region, tags)."
  type = object({
    name                = string
    environment         = string
    region              = string
    scope               = optional(string, "")
    tags                = map(string)
    admin_cidrs         = optional(list(string), [])
    deletion_protection = optional(bool, true)
  })
}

variable "cloud" {
  description = "aws | gcp | azure (selects the default Temporal Cloud region)."
  type        = string
}

variable "temporal_region" {
  description = "Temporal Cloud region id; empty = <cloud>-<context.region> (required for azure)."
  type        = string
  default     = ""
}

variable "profile" {
  description = "small | medium | large (retention 3 / 14 / 30 days)."
  type        = string
  default     = "small"
  validation {
    condition     = contains(["small", "medium", "large"], var.profile)
    error_message = "profile must be small, medium or large."
  }
}

variable "accepted_client_ca" {
  description = "Base64-encoded PEM bundle of the CA(s) that sign the workers' client certificates (mTLS). Empty = API key auth."
  type        = string
  default     = ""
}

locals {
  region    = var.temporal_region != "" ? var.temporal_region : "${var.cloud}-${var.context.region}"
  retention = { small = 3, medium = 14, large = 30 }[var.profile]
  mtls      = var.accepted_client_ca != ""
}

resource "temporalcloud_namespace" "this" {
  name               = var.context.name
  regions            = [local.region]
  retention_days     = local.retention
  accepted_client_ca = local.mtls ? var.accepted_client_ca : null
  api_key_auth       = local.mtls ? null : true
  namespace_lifecycle = {
    enable_delete_protection = var.context.deletion_protection
  }

  lifecycle {
    precondition {
      condition     = var.cloud != "azure" || var.temporal_region != ""
      error_message = "Set temporal_region for azure clusters (Temporal Cloud regions are aws-* / gcp-*)."
    }
  }
}

resource "temporalcloud_namespace_tags" "this" {
  namespace_id = temporalcloud_namespace.this.id
  tags = { for k, v in var.context.tags :
  lower(replace(k, "/[^a-zA-Z0-9_-]/", "_")) => lower(replace(v, "/[^a-zA-Z0-9_-]/", "-")) }
}

output "namespace" {
  description = "Namespace id as clients use it (<name>.<account>): TEMPORAL_NAMESPACE."
  value       = temporalcloud_namespace.this.id
}

output "target" {
  description = "gRPC endpoint for workers and clients: TEMPORAL_TARGET (mTLS or API key address)."
  value       = local.mtls ? temporalcloud_namespace.this.endpoints.mtls_grpc_address : temporalcloud_namespace.this.endpoints.grpc_address
}

output "auth" {
  description = "mtls | api-key."
  value       = local.mtls ? "mtls" : "api-key"
}
