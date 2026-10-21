# AfroTransact environment root. IDENTICAL in envs/{dev,uat,prod} (scripts/sync-interface.sh --check);
# per-environment sizing lives in sizing.tf, per-account values in terraform.tfvars.
#
# Pick the cloud with ONE variable:   cloud = "aws" | "gcp" | "azure" | "ovh"
# Only that cloud's modules - and providers - instantiate (for_each over an empty set for the others). Every cloud module set has the same
# inputs/outputs (modules/README.md), so the wiring below is the same four times with a different source.
# Elastic Cloud, Confluent Cloud and Temporal Cloud are the same on every cloud (modules/shared); on OVHcloud, which they do not
# run on, they are placed in saas_cloud / saas_region (default GCP europe-west1, next to Gravelines/Roubaix).

terraform {
  required_version = ">= 1.9" # provider for_each (OpenTofu)
  required_providers {
    aws           = { source = "hashicorp/aws", version = "~> 6.0" }
    google        = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
    azurerm       = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
    ovh           = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
    random        = { source = "hashicorp/random", version = "~> 3.6" }
    ec            = { source = "elastic/ec", version = "~> 0.12" }
    confluent     = { source = "confluentinc/confluent", version = "~> 2.0" }
    temporalcloud = { source = "temporalio/temporalcloud", version = ">= 1.0, < 2.0" }
  }
  # Partial backend config: see backend.<cloud>.hcl.example and README "Bootstrap state".
  # tofu init -backend-config=backend.hcl
  # backend "s3" {}      # cloud = aws
  # backend "gcs" {}     # cloud = gcp
  # backend "azurerm" {} # cloud = azure
}

locals {
  is_aws   = var.cloud == "aws"
  is_gcp   = var.cloud == "gcp"
  is_azure = var.cloud == "azure"
  is_ovh   = var.cloud == "ovh"

  # Instance keys for provider/module for_each (OpenTofu >= 1.9). Non-selected clouds get ZERO provider
  # instances, so their providers are never configured and need no credentials.
  on = {
    aws       = local.is_aws ? toset(["aws"]) : toset([])
    gcp       = local.is_gcp ? toset(["gcp"]) : toset([])
    azure     = local.is_azure ? toset(["azure"]) : toset([])
    ovh       = local.is_ovh ? toset(["ovh"]) : toset([])
    ec        = var.enable_elastic ? toset(["ec"]) : toset([])
    confluent = var.enable_confluent ? toset(["confluent"]) : toset([])
    temporal  = var.enable_temporal ? toset(["temporal"]) : toset([])
  }

  # sizing.tf as written, except `disposable` (a trial or test run, variables.tf) which removes everything that would make
  # a teardown hard: no deletion protection, and KYC evidence kept for one day instead of seven years (a locked retention
  # policy cannot be deleted until it expires).
  size = var.disposable ? merge(local.sizing, { deletion_protection = false, kyc_retention_days = 1 }) : local.sizing

  # Postgres and Valkey come from Terraform only when sizing.managed_data; otherwise they are external (Layerbase):
  # nothing is created, their endpoints and credentials go into the secret store by hand (docs/engineering/cloud-cost.md).
  on_data = {
    aws   = local.is_aws && local.size.managed_data ? toset(["aws"]) : toset([])
    gcp   = local.is_gcp && local.size.managed_data ? toset(["gcp"]) : toset([])
    azure = local.is_azure && local.size.managed_data ? toset(["azure"]) : toset([])
    ovh   = local.is_ovh && local.size.managed_data ? toset(["ovh"]) : toset([])
  }

  # The cluster must sit next to the external database server (latency, and cross-region traffic is billed). Layerbase
  # Dedicated runs in US East (Virginia) and US West (Oregon) only.
  layerbase_regions = {
    virginia = { aws = "us-east-1", gcp = "us-east4", azure = "eastus", ovh = "BHS5" } # BHS (Beauharnois, Quebec) is the closest OVH site
    oregon   = { aws = "us-west-2", gcp = "us-west1", azure = "", ovh = "" }
  }

  context = {
    name                = "${var.name_prefix}-${local.environment}"
    environment         = local.environment
    region              = var.region
    scope               = local.is_gcp ? var.gcp_project_id : (local.is_azure ? "${var.name_prefix}-${local.environment}" : (local.is_ovh ? var.ovh_service_name : ""))
    admin_cidrs         = var.admin_cidrs
    deletion_protection = local.size.deletion_protection
    tags = merge(var.extra_tags, {
      env           = local.environment
      owner         = var.owner
      "cost-center" = var.cost_center
      managed-by    = "opentofu"
      repo          = "diaspora-commerce"
    })
  }

  # Every deployable service (infra/local/PORTS.md) and its Postgres database (null = no database).
  services = {
    identity        = { db = "identity", buckets = [] }
    catalog         = { db = "catalog", buckets = [] }
    offers          = { db = "offers", buckets = [] }
    inventory       = { db = "inventory", buckets = [] }
    orders          = { db = "orders", buckets = [] }
    payments        = { db = "payments", buckets = ["exports"] }
    seller          = { db = "seller", buckets = ["kyc-evidence", "exports"] }
    retail          = { db = "retail", buckets = ["exports"] }
    fulfilment      = { db = "fulfilment", buckets = ["labels"] }
    bookings        = { db = "bookings", buckets = [] }
    trust-ops       = { db = "trustops", buckets = ["kyc-evidence", "exports"] }
    tax-returns     = { db = "taxreturns", buckets = ["exports"] }
    content         = { db = "content", buckets = ["media"] }
    ai-gateway      = { db = "ai", buckets = [] }
    mcp-server      = { db = null, buckets = [] }
    gateway-bff     = { db = null, buckets = [] }
    search          = { db = null, buckets = [] }
    indexer         = { db = null, buckets = [] }
    cart            = { db = "cart", buckets = [] }
    promise         = { db = null, buckets = [] }
    geo             = { db = "geo", buckets = [] }
    media           = { db = "media", buckets = ["media"] }
    notifications   = { db = "notifications", buckets = [] }
    carrier-gateway = { db = "carriers", buckets = ["labels"] }
  }
  service_names = sort(keys(local.services))
  databases     = sort(compact([for s in values(local.services) : s.db]))

  workload_identities = merge(
    { for k, v in local.services : k => { namespace = var.namespace, buckets = v.buckets, secrets = false } },
    # External Secrets Operator is the only workload that reads the secret store.
    { external-secrets = { namespace = "external-secrets", buckets = [], secrets = true } },
  )

  buckets = {
    media = {
      versioning   = true, archive_after_days = 180
      cors_origins = [for h in ["", "seller.", "admin."] : "https://${h}${var.web_domain}"]
    }
    labels       = { versioning = true, archive_after_days = 30, expire_after_days = 400 }
    exports      = { versioning = false, expire_after_days = 30 }
    kyc-evidence = { versioning = true, object_lock = true, retention_days = local.size.kyc_retention_days, archive_after_days = 90 }
  }

  # Events: at.<aggregate>.events, produced by the owning service, consumed by indexer/notifications/... .
  topics = {
    "at.product.events"      = { producers = ["catalog"], consumers = ["indexer", "offers", "ai-gateway"] }
    "at.offer.events"        = { producers = ["offers"], consumers = ["indexer", "cart"] }
    "at.inventory.events"    = { producers = ["inventory"], consumers = ["indexer", "offers", "promise", "cart"] }
    "at.order.events"        = { producers = ["orders"], consumers = ["payments", "fulfilment", "inventory", "notifications", "tax-returns", "seller"] }
    "at.payment.events"      = { producers = ["payments"], consumers = ["orders", "seller", "notifications"] }
    "at.shipment.events"     = { producers = ["fulfilment", "carrier-gateway"], consumers = ["orders", "notifications", "promise"] }
    "at.seller.events"       = { producers = ["seller"], consumers = ["identity", "catalog", "indexer", "notifications"] }
    "at.booking.events"      = { producers = ["bookings"], consumers = ["notifications", "payments", "indexer"] }
    "at.retail.events"       = { producers = ["retail"], consumers = ["inventory", "catalog"] }
    "at.trust.events"        = { producers = ["trust-ops"], consumers = ["catalog", "seller", "notifications"] }
    "at.content.events"      = { producers = ["content"], consumers = ["indexer", "gateway-bff"] }
    "at.identity.events"     = { producers = ["identity"], consumers = ["seller", "notifications", "cart"] }
    "at.media.events"        = { producers = ["media"], consumers = ["catalog", "indexer"] }
    "at.return.events"       = { producers = ["tax-returns"], consumers = ["orders", "payments", "inventory", "notifications"] }
    "at.search.events"       = { producers = ["search"], consumers = ["indexer", "ai-gateway"] }
    "at.ads.events"          = { producers = ["search", "content"], consumers = ["indexer", "content"] }
    "at.notification.events" = { producers = ["notifications"], consumers = ["trust-ops"], retention_ms = 259200000 }
  }

  gh_subject_env = "repo:${var.github_repository}:environment:${local.environment}"

  # Elastic, Confluent and Temporal Cloud do not run on OVHcloud: they sit in saas_cloud / saas_region (same continent).
  saas_cloud   = local.is_ovh ? var.saas_cloud : var.cloud
  saas_context = local.is_ovh ? merge(local.context, { region = var.saas_region }) : local.context
}

# The region is where everything lives: cluster, buckets, secrets, and (via layerbase_region) Postgres/Valkey/Meilisearch.
resource "terraform_data" "database_location" {
  lifecycle {
    precondition {
      condition     = local.size.managed_data || var.region == local.layerbase_regions[var.layerbase_region][var.cloud]
      error_message = "With external data (Layerbase) the region must match layerbase_region (${var.layerbase_region}): that is ${local.layerbase_regions[var.layerbase_region][var.cloud] == "" ? "not available on " : local.layerbase_regions[var.layerbase_region][var.cloud] + " on "}${var.cloud}, but region is ${var.region}."
    }
  }
}

# =========================================================================================== AWS
module "aws_network" {
  source     = "../../modules/aws/network"
  for_each   = local.on.aws
  providers  = { aws = aws.this[each.key] }
  context    = local.context
  cidr_block = var.cidr_block
  zone_count = local.size.zone_count
  egress     = local.size.egress
}

module "aws_cluster" {
  source           = "../../modules/aws/cluster"
  for_each         = local.on.aws
  providers        = { aws = aws.this[each.key] }
  context          = local.context
  network          = module.aws_network["aws"].network
  node_size        = local.size.node_size
  min_nodes        = local.size.min_nodes
  max_nodes        = local.size.max_nodes
  spot_enabled     = local.size.spot_max_nodes > 0
  spot_max_nodes   = local.size.spot_max_nodes
  private_endpoint = local.size.private_endpoint
  admin_principals = var.cluster_admins
}

module "aws_secrets" {
  source         = "../../modules/aws/secrets"
  for_each       = local.on.aws
  providers      = { aws = aws.this[each.key] }
  context        = local.context
  services       = local.service_names
  initial_values = local.seed_values
}

module "aws_postgres" {
  source                = "../../modules/aws/postgres"
  for_each              = local.on_data.aws
  providers             = { aws = aws.this[each.key] }
  context               = local.context
  network               = module.aws_network["aws"].network
  size                  = local.size.db_size
  high_availability     = local.size.ha
  storage_gb            = local.size.db_storage_gb
  backup_retention_days = local.size.backup_days
  databases             = local.databases
  secret_store          = module.aws_secrets["aws"].store
}

module "aws_cache" {
  source            = "../../modules/aws/cache"
  for_each          = local.on_data.aws
  providers         = { aws = aws.this[each.key] }
  context           = local.context
  network           = module.aws_network["aws"].network
  size              = local.size.cache_size
  high_availability = local.size.ha
}

module "aws_bucket" {
  source    = "../../modules/aws/bucket"
  for_each  = local.on.aws
  providers = { aws = aws.this[each.key] }
  context   = local.context
  network   = module.aws_network["aws"].network
  buckets   = local.buckets
}

module "aws_registry" {
  source            = "../../modules/aws/registry"
  for_each          = local.on.aws
  providers         = { aws = aws.this[each.key] }
  context           = local.context
  repositories      = local.service_names
  reader_principals = []
}

module "aws_budget" {
  source              = "../../modules/aws/budget"
  for_each            = local.on.aws
  providers           = { aws = aws.this[each.key] }
  context             = local.context
  monthly_limit       = local.size.monthly_budget
  notification_emails = var.budget_notification_emails
  shared_account      = var.budget_shared_account
}

module "aws_identity" {
  source       = "../../modules/aws/identity"
  for_each     = local.on.aws
  providers    = { aws = aws.this[each.key] }
  context      = local.context
  cluster      = module.aws_cluster["aws"].workload_identity
  services     = local.workload_identities
  buckets      = module.aws_bucket["aws"].buckets
  secret_store = module.aws_secrets["aws"].store
}

module "aws_github_deploy" {
  source          = "../../modules/aws/github-oidc"
  for_each        = local.on.aws
  providers       = { aws = aws.this[each.key] }
  context         = local.context
  access          = "deploy"
  subjects        = [local.gh_subject_env]
  create_provider = var.create_github_oidc_provider
  provider_ref    = var.github_oidc_provider_ref
  registry        = module.aws_registry["aws"].registry
}

module "aws_github_plan" {
  source          = "../../modules/aws/github-oidc"
  for_each        = local.on.aws
  providers       = { aws = aws.this[each.key] }
  context         = local.context
  access          = "plan"
  subjects        = ["repo:${var.github_repository}:pull_request"]
  create_provider = false
  provider_ref    = module.aws_github_deploy["aws"].provider_ref
}

module "aws_github_apply" {
  source          = "../../modules/aws/github-oidc"
  for_each        = local.on.aws
  providers       = { aws = aws.this[each.key] }
  context         = local.context
  access          = "apply"
  subjects        = ["repo:${var.github_repository}:environment:${local.environment}-infra"]
  create_provider = false
  provider_ref    = module.aws_github_deploy["aws"].provider_ref
}

# =========================================================================================== GCP
module "gcp_network" {
  source     = "../../modules/gcp/network"
  for_each   = local.on.gcp
  providers  = { google = google.this[each.key] }
  context    = local.context
  cidr_block = var.cidr_block
  zone_count = local.size.zone_count
  egress     = local.size.egress
}

module "gcp_cluster" {
  source           = "../../modules/gcp/cluster"
  for_each         = local.on.gcp
  providers        = { google = google.this[each.key] }
  context          = local.context
  network          = module.gcp_network["gcp"].network
  node_size        = local.size.node_size
  min_nodes        = local.size.min_nodes
  max_nodes        = local.size.max_nodes
  spot_enabled     = local.size.spot_max_nodes > 0
  spot_max_nodes   = local.size.spot_max_nodes
  private_endpoint = local.size.private_endpoint
  admin_principals = var.cluster_admins
}

module "gcp_secrets" {
  source         = "../../modules/gcp/secrets"
  for_each       = local.on.gcp
  providers      = { google = google.this[each.key] }
  context        = local.context
  services       = local.service_names
  initial_values = local.seed_values
}

module "gcp_postgres" {
  source                = "../../modules/gcp/postgres"
  for_each              = local.on_data.gcp
  providers             = { google = google.this[each.key] }
  context               = local.context
  network               = module.gcp_network["gcp"].network
  size                  = local.size.db_size
  high_availability     = local.size.ha
  storage_gb            = local.size.db_storage_gb
  backup_retention_days = local.size.backup_days
  databases             = local.databases
  secret_store          = module.gcp_secrets["gcp"].store
}

module "gcp_cache" {
  source            = "../../modules/gcp/cache"
  for_each          = local.on_data.gcp
  providers         = { google = google.this[each.key] }
  context           = local.context
  network           = module.gcp_network["gcp"].network
  size              = local.size.cache_size
  high_availability = local.size.ha
}

module "gcp_bucket" {
  source    = "../../modules/gcp/bucket"
  for_each  = local.on.gcp
  providers = { google = google.this[each.key] }
  context   = local.context
  network   = module.gcp_network["gcp"].network
  buckets   = local.buckets
}

module "gcp_registry" {
  source            = "../../modules/gcp/registry"
  for_each          = local.on.gcp
  providers         = { google = google.this[each.key] }
  context           = local.context
  repositories      = local.service_names
  reader_principals = [module.gcp_cluster["gcp"].node_identity]
}

module "gcp_budget" {
  source              = "../../modules/gcp/budget"
  for_each            = local.on.gcp
  providers           = { google = google.this[each.key] }
  context             = local.context
  monthly_limit       = local.size.monthly_budget
  notification_emails = var.budget_notification_emails
  shared_account      = var.budget_shared_account
  billing_account     = var.gcp_billing_account
}

module "gcp_identity" {
  source       = "../../modules/gcp/identity"
  for_each     = local.on.gcp
  providers    = { google = google.this[each.key] }
  context      = local.context
  cluster      = module.gcp_cluster["gcp"].workload_identity
  services     = local.workload_identities
  buckets      = module.gcp_bucket["gcp"].buckets
  secret_store = module.gcp_secrets["gcp"].store
}

module "gcp_github_deploy" {
  source          = "../../modules/gcp/github-oidc"
  for_each        = local.on.gcp
  providers       = { google = google.this[each.key] }
  context         = local.context
  access          = "deploy"
  subjects        = [local.gh_subject_env]
  create_provider = var.create_github_oidc_provider
  provider_ref    = var.github_oidc_provider_ref
  registry        = module.gcp_registry["gcp"].registry
}

module "gcp_github_plan" {
  source          = "../../modules/gcp/github-oidc"
  for_each        = local.on.gcp
  providers       = { google = google.this[each.key] }
  context         = local.context
  access          = "plan"
  subjects        = ["repo:${var.github_repository}:pull_request"]
  create_provider = false
  provider_ref    = module.gcp_github_deploy["gcp"].provider_ref
}

module "gcp_github_apply" {
  source          = "../../modules/gcp/github-oidc"
  for_each        = local.on.gcp
  providers       = { google = google.this[each.key] }
  context         = local.context
  access          = "apply"
  subjects        = ["repo:${var.github_repository}:environment:${local.environment}-infra"]
  create_provider = false
  provider_ref    = module.gcp_github_deploy["gcp"].provider_ref
}

# =========================================================================================== Azure
resource "azurerm_resource_group" "this" {
  for_each = local.on.azure
  provider = azurerm.this[each.key]
  name     = local.context.scope
  location = var.region
  tags     = local.context.tags
}

module "azure_network" {
  source     = "../../modules/azure/network"
  for_each   = local.on.azure
  providers  = { azurerm = azurerm.this[each.key] }
  context    = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  cidr_block = var.cidr_block
  zone_count = local.size.zone_count
  egress     = local.size.egress
}

module "azure_cluster" {
  source           = "../../modules/azure/cluster"
  for_each         = local.on.azure
  providers        = { azurerm = azurerm.this[each.key] }
  context          = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  network          = module.azure_network["azure"].network
  node_size        = local.size.node_size
  min_nodes        = local.size.min_nodes
  max_nodes        = local.size.max_nodes
  spot_enabled     = local.size.spot_max_nodes > 0
  spot_max_nodes   = local.size.spot_max_nodes
  private_endpoint = local.size.private_endpoint
  admin_principals = var.cluster_admins
}

module "azure_secrets" {
  source         = "../../modules/azure/secrets"
  for_each       = local.on.azure
  providers      = { azurerm = azurerm.this[each.key] }
  context        = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  services       = local.service_names
  initial_values = local.seed_values
}

module "azure_postgres" {
  source                = "../../modules/azure/postgres"
  for_each              = local.on_data.azure
  providers             = { azurerm = azurerm.this[each.key] }
  context               = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  network               = module.azure_network["azure"].network
  size                  = local.size.db_size
  high_availability     = local.size.ha
  storage_gb            = local.size.db_storage_gb
  backup_retention_days = local.size.backup_days
  databases             = local.databases
  secret_store          = module.azure_secrets["azure"].store
}

module "azure_cache" {
  source            = "../../modules/azure/cache"
  for_each          = local.on_data.azure
  providers         = { azurerm = azurerm.this[each.key] }
  context           = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  network           = module.azure_network["azure"].network
  size              = local.size.cache_size
  high_availability = local.size.ha
}

module "azure_bucket" {
  source    = "../../modules/azure/bucket"
  for_each  = local.on.azure
  providers = { azurerm = azurerm.this[each.key] }
  context   = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  network   = module.azure_network["azure"].network
  buckets   = local.buckets
}

module "azure_registry" {
  source            = "../../modules/azure/registry"
  for_each          = local.on.azure
  providers         = { azurerm = azurerm.this[each.key] }
  context           = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  repositories      = local.service_names
  reader_principals = [module.azure_cluster["azure"].node_identity]
}

module "azure_budget" {
  source              = "../../modules/azure/budget"
  for_each            = local.on.azure
  providers           = { azurerm = azurerm.this[each.key] }
  context             = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  monthly_limit       = local.size.monthly_budget
  notification_emails = var.budget_notification_emails
  shared_account      = var.budget_shared_account
}

module "azure_identity" {
  source       = "../../modules/azure/identity"
  for_each     = local.on.azure
  providers    = { azurerm = azurerm.this[each.key] }
  context      = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  cluster      = module.azure_cluster["azure"].workload_identity
  services     = local.workload_identities
  buckets      = module.azure_bucket["azure"].buckets
  secret_store = module.azure_secrets["azure"].store
}

module "azure_github_deploy" {
  source    = "../../modules/azure/github-oidc"
  for_each  = local.on.azure
  providers = { azurerm = azurerm.this[each.key] }
  context   = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  access    = "deploy"
  subjects  = [local.gh_subject_env]
  registry  = module.azure_registry["azure"].registry
}

module "azure_github_plan" {
  source    = "../../modules/azure/github-oidc"
  for_each  = local.on.azure
  providers = { azurerm = azurerm.this[each.key] }
  context   = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  access    = "plan"
  subjects  = ["repo:${var.github_repository}:pull_request"]
}

module "azure_github_apply" {
  source    = "../../modules/azure/github-oidc"
  for_each  = local.on.azure
  providers = { azurerm = azurerm.this[each.key] }
  context   = merge(local.context, { scope = azurerm_resource_group.this["azure"].name })
  access    = "apply"
  subjects  = ["repo:${var.github_repository}:environment:${local.environment}-infra"]
}

# =========================================================================================== OVHcloud
module "ovh_network" {
  source     = "../../modules/ovh/network"
  for_each   = local.on.ovh
  providers  = { ovh = ovh.this[each.key] }
  context    = local.context
  cidr_block = var.cidr_block
  zone_count = local.size.zone_count
  egress     = local.size.egress
}

module "ovh_cluster" {
  source           = "../../modules/ovh/cluster"
  for_each         = local.on.ovh
  providers        = { ovh = ovh.this[each.key] }
  context          = local.context
  network          = module.ovh_network["ovh"].network
  node_size        = local.size.node_size
  min_nodes        = local.size.min_nodes
  max_nodes        = local.size.max_nodes
  spot_enabled     = local.size.spot_max_nodes > 0
  spot_max_nodes   = local.size.spot_max_nodes
  private_endpoint = local.size.private_endpoint
  admin_principals = var.cluster_admins
}

module "ovh_secrets" {
  source         = "../../modules/ovh/secrets"
  for_each       = local.on.ovh
  providers      = { ovh = ovh.this[each.key] }
  context        = local.context
  services       = local.service_names
  initial_values = local.seed_values
}

module "ovh_postgres" {
  source                = "../../modules/ovh/postgres"
  for_each              = local.on_data.ovh
  providers             = { ovh = ovh.this[each.key] }
  context               = local.context
  network               = module.ovh_network["ovh"].network
  size                  = local.size.db_size
  high_availability     = local.size.ha
  storage_gb            = local.size.db_storage_gb
  backup_retention_days = local.size.backup_days
  databases             = local.databases
  secret_store          = module.ovh_secrets["ovh"].store
}

module "ovh_cache" {
  source            = "../../modules/ovh/cache"
  for_each          = local.on_data.ovh
  providers         = { ovh = ovh.this[each.key] }
  context           = local.context
  network           = module.ovh_network["ovh"].network
  size              = local.size.cache_size
  high_availability = local.size.ha
}

module "ovh_bucket" {
  source    = "../../modules/ovh/bucket"
  for_each  = local.on.ovh
  providers = { ovh = ovh.this[each.key] }
  context   = local.context
  network   = module.ovh_network["ovh"].network
  buckets   = local.buckets
}

module "ovh_registry" {
  source            = "../../modules/ovh/registry"
  for_each          = local.on.ovh
  providers         = { ovh = ovh.this[each.key] }
  context           = local.context
  repositories      = local.service_names
  reader_principals = []
}

module "ovh_budget" {
  source              = "../../modules/ovh/budget"
  for_each            = local.on.ovh
  providers           = { ovh = ovh.this[each.key] }
  context             = local.context
  monthly_limit       = local.size.monthly_budget
  notification_emails = var.budget_notification_emails
  shared_account      = var.budget_shared_account
}

module "ovh_identity" {
  source       = "../../modules/ovh/identity"
  for_each     = local.on.ovh
  providers    = { ovh = ovh.this[each.key] }
  context      = local.context
  cluster      = module.ovh_cluster["ovh"].workload_identity
  services     = local.workload_identities
  buckets      = module.ovh_bucket["ovh"].buckets
  secret_store = module.ovh_secrets["ovh"].store
}

module "ovh_github_deploy" {
  source    = "../../modules/ovh/github-oidc"
  for_each  = local.on.ovh
  providers = { ovh = ovh.this[each.key] }
  context   = local.context
  access    = "deploy"
  subjects  = [local.gh_subject_env]
  registry  = module.ovh_registry["ovh"].registry
}

module "ovh_github_plan" {
  source    = "../../modules/ovh/github-oidc"
  for_each  = local.on.ovh
  providers = { ovh = ovh.this[each.key] }
  context   = local.context
  access    = "plan"
  subjects  = ["repo:${var.github_repository}:pull_request"]
}

module "ovh_github_apply" {
  source    = "../../modules/ovh/github-oidc"
  for_each  = local.on.ovh
  providers = { ovh = ovh.this[each.key] }
  context   = local.context
  access    = "apply"
  subjects  = ["repo:${var.github_repository}:environment:${local.environment}-infra"]
}

# =========================================================================================== shared SaaS
module "elastic" {
  source        = "../../modules/shared/elastic"
  for_each      = local.on.ec
  providers     = { ec = ec.this[each.key] }
  context       = local.saas_context
  cloud         = local.saas_cloud
  profile       = local.size.saas_profile
  allowed_cidrs = [for ip in local.egress_ips : "${ip}/32"]
}

module "confluent" {
  source    = "../../modules/shared/confluent"
  for_each  = local.on.confluent
  providers = { confluent = confluent.this[each.key] }
  context   = local.saas_context
  cloud     = local.saas_cloud
  profile   = local.size.saas_profile
  topics    = local.topics
  services  = local.service_names
}

# Temporal Cloud namespace afrotransact-<env> (orders-service checkout saga; docs/runbooks/first-environment.md).
module "temporal" {
  source             = "../../modules/shared/temporal"
  for_each           = local.on.temporal
  providers          = { temporalcloud = temporalcloud.this[each.key] }
  context            = local.saas_context
  cloud              = local.saas_cloud
  temporal_region    = var.temporal_region
  profile            = local.size.saas_profile
  accepted_client_ca = var.temporal_accepted_client_ca
}

# =========================================================================================== selected cloud
# Collapse the three module sets into one object with the shared output names.
locals {
  egress_ips = one([for m in concat(values(module.aws_network), values(module.gcp_network), values(module.azure_network), values(module.ovh_network)) : m.network.egress_ips])

  p = {
    network   = one(concat(values(module.aws_network), values(module.gcp_network), values(module.azure_network), values(module.ovh_network))).network
    cluster   = one(concat(values(module.aws_cluster), values(module.gcp_cluster), values(module.azure_cluster), values(module.ovh_cluster)))
    postgres  = one(concat(values(module.aws_postgres), values(module.gcp_postgres), values(module.azure_postgres), values(module.ovh_postgres)))
    cache     = one(concat(values(module.aws_cache), values(module.gcp_cache), values(module.azure_cache), values(module.ovh_cache)))
    bucket    = one(concat(values(module.aws_bucket), values(module.gcp_bucket), values(module.azure_bucket), values(module.ovh_bucket)))
    secrets   = one(concat(values(module.aws_secrets), values(module.gcp_secrets), values(module.azure_secrets), values(module.ovh_secrets)))
    registry  = one(concat(values(module.aws_registry), values(module.gcp_registry), values(module.azure_registry), values(module.ovh_registry)))
    identity  = one(concat(values(module.aws_identity), values(module.gcp_identity), values(module.azure_identity), values(module.ovh_identity)))
    budget    = one(concat(values(module.aws_budget), values(module.gcp_budget), values(module.azure_budget), values(module.ovh_budget)))
    gh_deploy = one(concat(values(module.aws_github_deploy), values(module.gcp_github_deploy), values(module.azure_github_deploy), values(module.ovh_github_deploy)))
    gh_plan   = one(concat(values(module.aws_github_plan), values(module.gcp_github_plan), values(module.azure_github_plan), values(module.ovh_github_plan)))
    gh_apply  = one(concat(values(module.aws_github_apply), values(module.gcp_github_apply), values(module.azure_github_apply), values(module.ovh_github_apply)))
  }

  # Non-secret connection details seeded into each service's secret on first apply. Passwords are added
  # by scripts/postgres-bootstrap.sh (per-service DB roles) and by operators (Stripe, OpenRouter ...).
  kafka_credentials = var.enable_confluent ? module.confluent["confluent"].service_credentials : {}
  seed_values = {
    for k, v in local.services : k => merge(
      { APP_ENV = local.environment },
      v.db == null ? {} : { DB_NAME = v.db, DB_USER = "${v.db}_app" },
      var.enable_confluent ? {
        KAFKA_BOOTSTRAP     = module.confluent["confluent"].bootstrap_servers
        KAFKA_API_KEY       = local.kafka_credentials[k].key
        KAFKA_API_SECRET    = local.kafka_credentials[k].secret
        SCHEMA_REGISTRY_URL = module.confluent["confluent"].schema_registry_url
        } : {
        # Lean profile: single-node Kafka in the cluster (infra/k8s/platform/lean/kafka), plaintext inside the namespace network.
        KAFKA_BOOTSTRAP = "kafka.kafka.svc.cluster.local:9092"
      },
      var.enable_elastic && contains(["search", "indexer"], k) ? { ES_URLS = module.elastic["ec"].elasticsearch_url } : {},
      var.enable_temporal && k == "orders" ? {
        TEMPORAL_TARGET    = module.temporal["temporal"].target
        TEMPORAL_NAMESPACE = module.temporal["temporal"].namespace
      } : {},
    )
  }
}
