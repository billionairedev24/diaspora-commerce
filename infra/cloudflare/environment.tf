# Per-environment edge objects.

# ------------------------------------------------------------------------------ web apps on Workers
# Proxied placeholder records so the Workers routes match; the Worker answers, the IP is never contacted.
resource "cloudflare_dns_record" "web" {
  for_each = toset(local.web_apps)
  zone_id  = local.zone_id
  name     = local.hosts[each.key]
  type     = "AAAA"
  content  = "100::"
  proxied  = true
  ttl      = 1
  comment  = "${each.key} (${var.environment}) - served by Workers script ${each.key}-${var.environment}"
  tags     = local.tags
}

# Worker scripts are uploaded by .github/workflows/_web-app.yml (wrangler deploy --name <app>-<env>).
resource "cloudflare_workers_route" "web" {
  for_each = var.workers_routes_enabled ? toset(local.web_apps) : toset([])
  zone_id  = local.zone_id
  pattern  = "${local.hosts[each.key]}/*"
  script   = "${each.key}-${var.environment}"
}

# ------------------------------------------------------------------------------ API origins
resource "cloudflare_dns_record" "origin_ingress" {
  for_each = var.origin_mode == "ingress" ? toset(local.origin_apps) : toset([])
  zone_id  = local.zone_id
  name     = local.hosts[each.key]
  type     = can(regex("^[0-9.]+$", var.origin_target)) ? "A" : "CNAME"
  content  = var.origin_target
  proxied  = true
  ttl      = 1
  comment  = "${each.key} (${var.environment}) -> cluster ingress (AOP enforced)"
  tags     = local.tags
}

resource "random_bytes" "tunnel_secret" {
  count  = var.origin_mode == "tunnel" ? 1 : 0
  length = 32
}

resource "cloudflare_zero_trust_tunnel_cloudflared" "this" {
  count         = var.origin_mode == "tunnel" ? 1 : 0
  account_id    = var.account_id
  name          = "afrotransact-${var.environment}"
  config_src    = "cloudflare"
  tunnel_secret = random_bytes.tunnel_secret[0].base64
}

resource "cloudflare_zero_trust_tunnel_cloudflared_config" "this" {
  count      = var.origin_mode == "tunnel" ? 1 : 0
  account_id = var.account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.this[0].id
  config = {
    ingress = concat(
      [for app in local.origin_apps : {
        hostname = local.hosts[app]
        service  = var.tunnel_service
        origin_request = {
          http_host_header = local.hosts[app]
          connect_timeout  = 10
        }
      }],
      [{ service = "http_status:404" }],
    )
  }
}

resource "cloudflare_dns_record" "origin_tunnel" {
  for_each = var.origin_mode == "tunnel" ? toset(local.origin_apps) : toset([])
  zone_id  = local.zone_id
  name     = local.hosts[each.key]
  type     = "CNAME"
  content  = "${cloudflare_zero_trust_tunnel_cloudflared.this[0].id}.cfargotunnel.com"
  proxied  = true
  ttl      = 1
  comment  = "${each.key} (${var.environment}) -> Cloudflare Tunnel afrotransact-${var.environment}"
  tags     = local.tags
}

# ------------------------------------------------------------------------------ Turnstile
resource "cloudflare_turnstile_widget" "this" {
  for_each   = toset(["sign-in", "sign-up", "checkout"])
  account_id = var.account_id
  name       = "afrotransact-${var.environment}-${each.key}"
  domains    = distinct([local.hosts["storefront"], local.hosts["seller-center"], local.hosts["auth"]])
  mode       = each.key == "checkout" ? "invisible" : "managed"
  region     = "world"
}

# ------------------------------------------------------------------------------ R2 buckets (media service)
# Two buckets, names from local.r2_buckets (the one source of truth; the media service takes them from config/prod.yaml, or from
# R2_PUBLIC_BUCKET / R2_PRIVATE_BUCKET in the trial). The module does not manage CORS or the API tokens: both are dashboard steps
# in docs/runbooks/r2-buckets.md (sections 3 and 4). Do not also create the buckets by hand.
# Public bucket: released product and storefront images, served only through the custom domain below.
resource "cloudflare_r2_bucket" "media" {
  account_id = var.account_id
  name       = local.r2_buckets.media
  location   = "enam"
}

# Private bucket: quarantine (uploads waiting for moderation), KYC documents, proof and return photos, purchase orders,
# shipping documents, resumes and exports. NO custom domain and r2.dev stays off: it is only ever reached with presigned URLs.
resource "cloudflare_r2_bucket" "kyc_evidence" {
  account_id = var.account_id
  name       = local.r2_buckets.kyc_evidence
  location   = "enam"
}

resource "cloudflare_r2_custom_domain" "media" {
  account_id  = var.account_id
  bucket_name = cloudflare_r2_bucket.media.name
  domain      = local.hosts["media"]
  zone_id     = local.zone_id
  enabled     = true
  min_tls     = "1.2"
}
