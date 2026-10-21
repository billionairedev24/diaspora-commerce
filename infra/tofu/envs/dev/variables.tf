# Inputs. IDENTICAL in every env; environment-specific sizing is in sizing.tf.

variable "cloud" {
  description = "Target cloud for this environment: aws | gcp | azure | ovh. The ONLY switch needed to move clouds."
  type        = string
  validation {
    condition     = contains(["aws", "gcp", "azure", "ovh"], var.cloud)
    error_message = "cloud must be aws, gcp, azure or ovh."
  }
}

variable "region" {
  description = "Region in the chosen cloud (us-east-1 | us-east4 | eastus | GRA11 on OVHcloud ...)."
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

variable "enable_temporal" {
  description = "Provision the environment's Temporal Cloud namespace (needs TEMPORAL_CLOUD_API_KEY)."
  type        = bool
  default     = true
}

variable "temporal_region" {
  description = "Temporal Cloud region; empty = <cloud>-<region> (aws-us-east-1, gcp-us-east4). Required when cloud = azure."
  type        = string
  default     = ""
}

variable "temporal_accepted_client_ca" {
  description = "Base64 PEM of the CA signing the Temporal worker client certificates (mTLS, not secret). Empty = API key auth."
  type        = string
  default     = ""
}

# ------------------------------------------------------------------ cost (S182)
variable "budget_notification_emails" {
  description = "Recipients of the environment's monthly budget alerts (sizing.tf monthly_budget). Empty = AWS: SNS topic only, GCP: billing admins, Azure: subscription Owners."
  type        = list(string)
  default     = []
}

variable "budget_shared_account" {
  description = "Several environments share this AWS account / GCP project: filter the budget by the env tag/label (AWS: activate the `env` cost allocation tag in Billing first)."
  type        = bool
  default     = false
}

variable "gcp_billing_account" {
  description = "Billing account id the GCP project is linked to (cloud = gcp; required for the budget)."
  type        = string
  default     = ""
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

variable "ovh_service_name" {
  description = "OVHcloud Public Cloud project id (cloud = ovh). One project can hold several environments; give each its own cidr_block."
  type        = string
  default     = ""
}

variable "ovh_endpoint" {
  description = "OVHcloud API endpoint (cloud = ovh): ovh-eu | ovh-ca | ovh-us. Credentials come from OVH_APPLICATION_KEY / OVH_APPLICATION_SECRET / OVH_CONSUMER_KEY."
  type        = string
  default     = "ovh-eu"
  validation {
    condition     = contains(["ovh-eu", "ovh-ca", "ovh-us"], var.ovh_endpoint)
    error_message = "ovh_endpoint must be ovh-eu, ovh-ca or ovh-us."
  }
}

variable "saas_cloud" {
  description = "Cloud that Elastic Cloud, Confluent Cloud and Temporal Cloud run on when cloud = ovh (they are not offered on OVHcloud): aws | gcp | azure. Ignored on the other clouds."
  type        = string
  default     = "gcp"
  validation {
    condition     = contains(["aws", "gcp", "azure"], var.saas_cloud)
    error_message = "saas_cloud must be aws, gcp or azure."
  }
}

variable "saas_region" {
  description = "Region of saas_cloud closest to the OVHcloud region (cloud = ovh), e.g. europe-west1 (gcp), eu-west-1 (aws), westeurope (azure). Ignored on the other clouds."
  type        = string
  default     = "europe-west1"
}

variable "layerbase_region" {
  description = "Where the external Layerbase server runs (sizing.managed_data = false): virginia | oregon. The cluster's region must be in the same place (plan check), so database traffic stays in one metro: virginia = aws us-east-1, gcp us-east4, azure eastus, ovh BHS5; oregon = aws us-west-2, gcp us-west1 (Azure has no Oregon region next to Layerbase: use virginia)."
  type        = string
  default     = "virginia"
  validation {
    condition     = contains(["virginia", "oregon"], var.layerbase_region)
    error_message = "layerbase_region must be virginia or oregon."
  }
}

variable "disposable" {
  description = "true for a trial / test environment (free-credit accounts): deletion protection off and KYC retention 1 day, so `tofu destroy` leaves nothing behind. Never true for the real production state."
  type        = bool
  default     = false
}
