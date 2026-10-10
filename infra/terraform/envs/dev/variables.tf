# Inputs. IDENTICAL in every env; environment-specific sizing is in sizing.tf.

variable "cloud" {
  description = "Target cloud for this environment: aws | gcp | azure. The ONLY switch needed to move clouds."
  type        = string
  validation {
    condition     = contains(["aws", "gcp", "azure"], var.cloud)
    error_message = "cloud must be aws, gcp or azure."
  }
}

variable "region" {
  description = "Region in the chosen cloud (us-east-1 | us-east4 | eastus ...)."
  type        = string
}

variable "name_prefix" {
  description = "Resource name prefix; the environment is appended (afrotransact-dev)."
  type        = string
  default     = "afrotransact"
}

variable "owner" {
  description = "`owner` tag (team or email alias)."
  type        = string
  default     = "platform"
}

variable "cost_center" {
  description = "`cost-center` tag."
  type        = string
  default     = "engineering"
}

variable "extra_tags" {
  description = "Additional tags/labels on every resource."
  type        = map(string)
  default     = {}
}

variable "cidr_block" {
  description = "Network address space (keep unique per environment for future peering/VPN)."
  type        = string
}

variable "admin_cidrs" {
  description = "CIDRs allowed to reach management endpoints (Kubernetes API, Key Vault). Empty = cloud default (public with auth)."
  type        = list(string)
  default     = []
}

variable "cluster_admins" {
  description = "Cluster admins: IAM role ARNs (aws), IAM members like group:x@y (gcp), Entra group object ids (azure)."
  type        = list(string)
  default     = []
}

variable "namespace" {
  description = "Kubernetes namespace the services run in (one cluster per environment)."
  type        = string
  default     = "afrotransact"
}

variable "web_domain" {
  description = "Public web domain for this environment (CORS on the media bucket), e.g. dev.afrotransact.com."
  type        = string
}

variable "github_repository" {
  description = "owner/repo trusted by the GitHub OIDC roles."
  type        = string
  default     = "billionairedev24/diaspora-commerce"
}

variable "create_github_oidc_provider" {
  description = "Create the account/project-wide GitHub OIDC provider. Only ONE environment per AWS account / GCP project should (set false + github_oidc_provider_ref in the others)."
  type        = bool
  default     = true
}

variable "github_oidc_provider_ref" {
  description = "Existing provider ARN (aws) or pool id (gcp) when create_github_oidc_provider = false."
  type        = string
  default     = ""
}

variable "enable_elastic" {
  description = "Provision Elasticsearch on Elastic Cloud (needs EC_API_KEY)."
  type        = bool
  default     = true
}

variable "enable_confluent" {
  description = "Provision Kafka on Confluent Cloud (needs CONFLUENT_CLOUD_API_KEY/SECRET)."
  type        = bool
  default     = true
}

# ------------------------------------------------------------------ cloud account identifiers
variable "gcp_project_id" {
  description = "GCP project (cloud = gcp)."
  type        = string
  default     = ""
}

variable "azure_subscription_id" {
  description = "Azure subscription (cloud = azure)."
  type        = string
  default     = ""
}

variable "azure_tenant_id" {
  description = "Entra tenant (cloud = azure)."
  type        = string
  default     = ""
}
