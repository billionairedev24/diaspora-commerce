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
  # backend "s3" {} | backend "gcs" {} | backend "azurerm" {}  - same state bucket as infra/tofu
}

# API token from CLOUDFLARE_API_TOKEN (scopes: Zone Settings/DNS/WAF/Workers Routes/SSL edit, Account
# Turnstile/R2/Tunnel/Lists edit).
provider "cloudflare" {}

locals {
  # THE host map (infra/cloudflare/hosts.json): the single source of truth for every public hostname, also read by
  # tools/ci/env_hosts.py and tools/ci/check_env_urls.py. Cloudflare's FREE Universal certificate covers the zone apex and ONE label
  # below it, so every host is flat:
  #   prod:  afrotransact.com (storefront)         <role>.afrotransact.com
  #   other: <suffix>.afrotransact.com (storefront) <role>-<suffix>.afrotransact.com      (suffix: dev | uat | test for the trial)
  hostmap   = jsondecode(file("${path.module}/hosts.json"))
  env_cfg   = local.hostmap.envs[var.environment]
  suffix    = local.env_cfg.suffix
  zone      = var.zone_name != "" ? var.zone_name : lookup(local.env_cfg, "zone", local.hostmap.zone)
  all_roles = concat(local.hostmap.roles, local.hostmap.other_roles)

  # role -> host for ONE environment (same formula as tools/ci/env_hosts.py)
  role_hosts      = { for r in local.all_roles : r => local.suffix == "" ? "${r}.${local.zone}" : "${r}-${local.suffix}.${local.zone}" }
  storefront_host = local.suffix == "" ? local.zone : "${local.suffix}.${local.zone}"

  # Public hostnames for this environment (keys are the names the rest of the module uses).
  hosts = {
    storefront    = local.storefront_host          # afrotransact.com | dev.afrotransact.com | test.afrotransact.com
    seller-center = local.role_hosts["seller"]     # seller.afrotransact.com | seller-uat.afrotransact.com
    staff-console = local.role_hosts["admin"]      # admin.afrotransact.com
    api           = local.role_hosts["api"]        # gateway-bff (BFF for web + mobile)
    auth          = local.role_hosts["auth"]       # identity (Spring Authorization Server)
    media         = local.role_hosts["media"]      # R2 public media
    developers    = local.role_hosts["developers"] # docs portal (Cloudflare Pages, pages.tf)
  }
  web_apps    = ["storefront", "seller-center", "staff-console"]
  origin_apps = ["api", "auth"]

  # Hosts of EVERY environment (for the zone-wide rules owned by the manage_zone run).
  env_all_hosts = {
    for e, cfg in local.hostmap.envs : e => concat(
      [lookup(cfg, "suffix", "") == "" ? lookup(cfg, "zone", local.hostmap.zone) : "${cfg.suffix}.${lookup(cfg, "zone", local.hostmap.zone)}"],
      [for r in local.all_roles : lookup(cfg, "suffix", "") == "" ? "${r}.${lookup(cfg, "zone", local.hostmap.zone)}" : "${r}-${cfg.suffix}.${lookup(cfg, "zone", local.hostmap.zone)}"]
    )
  }

  # R2 buckets, the ONE source of truth (docs/runbooks/r2-buckets.md, go/services/media/config/prod.yaml): afrotransact-<env>-media
  # (public, custom domain hosts["media"]) and afrotransact-<env>-kyc-evidence (private: no custom domain, r2.dev off).
  r2_buckets = {
    media        = "afrotransact-${var.environment}-media"
    kyc_evidence = "afrotransact-${var.environment}-kyc-evidence"
  }
  # Web origins that may call the private bucket from a browser (presigned PUT into quarantine/): the three web apps.
  web_origins = [for k in ["storefront", "seller-center", "staff-console"] : "https://${local.hosts[k]}"]

  tags = ["env:${var.environment}", "managed-by:opentofu"]
}

data "cloudflare_zone" "this" {
  filter = {
    name = local.zone
  }
}

locals {
  zone_id = data.cloudflare_zone.this.zone_id
}
