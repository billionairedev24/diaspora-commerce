# Cloudflare edge as code (cloudflare/cloudflare v5). Cloudflare fronts every environment regardless of the
# backend cloud, so moving clouds is an origin change here, nothing else.
#
# One root, applied once per environment with envs/<env>.tfvars and its own state key:
#   tofu init -backend-config=backend.hcl -backend-config="key=cloudflare/<env>.tfstate"
#   tofu apply -var-file=envs/<env>.tfvars
# Zone-wide objects (settings, WAF, rate limits, AOP, certificate packs, admin allow-list) exist once per
# zone and are owned by the run with manage_zone = true (prod). Per-environment objects (DNS, Workers
# routes, Turnstile widgets, R2 media bucket, Tunnel) are owned by each environment's run.
terraform {
  required_version = ">= 1.8"
  required_providers {
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 5.0" }
    random     = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # backend "s3" {} | backend "gcs" {} | backend "azurerm" {}  - same state bucket as infra/terraform
}

# API token from CLOUDFLARE_API_TOKEN (scopes: Zone Settings/DNS/WAF/Workers Routes/SSL edit, Account
# Turnstile/R2/Tunnel/Lists edit).
provider "cloudflare" {}

locals {
  env_prefix = var.environment == "prod" ? "" : "${var.environment}."
  base       = "${local.env_prefix}${var.zone_name}"

  # Public hostnames for this environment.
  hosts = {
    storefront    = local.base                 # afrotransact.com | dev.afrotransact.com
    seller-center = "seller.${local.base}"     # seller.afrotransact.com
    staff-console = "admin.${local.base}"      # admin.afrotransact.com
    api           = "api.${local.base}"        # gateway-bff (BFF for web + mobile)
    auth          = "auth.${local.base}"       # identity (Spring Authorization Server)
    media         = "media.${local.base}"      # R2 public media
    mcp           = "mcp.${local.base}"        # mcp-server (own host, MCP clients)
    grafana       = "grafana.${local.base}"    # kube-prometheus-stack Grafana
    developers    = "developers.${local.base}" # docs portal (Cloudflare Pages, pages.tf)
  }
  web_apps    = ["storefront", "seller-center", "staff-console"]
  origin_apps = ["api", "auth", "mcp", "grafana"]

  tags = ["env:${var.environment}", "managed-by:opentofu"]
}

data "cloudflare_zone" "this" {
  filter = {
    name = var.zone_name
  }
}

locals {
  zone_id = data.cloudflare_zone.this.zone_id
}
