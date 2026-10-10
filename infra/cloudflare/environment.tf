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

resource "cloudflare_dns_record" "www" {
  count   = var.environment == "prod" ? 1 : 0
  zone_id = local.zone_id
  name    = "www.${var.zone_name}"
  type    = "AAAA"
  content = "100::"
  proxied = true
  ttl     = 1
  comment = "www -> 301 to the bare domain (redirect rule)"
  tags    = local.tags
}

# Worker scripts are uploaded by .github/workflows/_web-app.yml (wrangler deploy --name <app>-<env>).
resource "cloudflare_workers_route" "web" {
  for_each = var.workers_routes_enabled ? toset(local.web_apps) : toset([])
  zone_id  = local.zone_id
  pattern  = "${local.hosts[each.key]}/*"
  script   = "${each.key}-${var.environment}"
}

# www is not a second storefront: it redirects (301) to the bare domain, keeping path and query, so the BFF's
# origin checks and OAuth callbacks only ever see the bare domain.
resource "cloudflare_ruleset" "www_redirect" {
  count   = var.environment == "prod" ? 1 : 0
  zone_id = local.zone_id
  name    = "www to apex"
  kind    = "zone"
  phase   = "http_request_dynamic_redirect"
  rules = [{
    description = "www.${var.zone_name} -> ${var.zone_name}"
    expression  = "(http.host eq \"www.${var.zone_name}\")"
    action      = "redirect"
    enabled     = true
    action_parameters = {
      from_value = {
        status_code           = 301
        preserve_query_string = true
        target_url = {
          expression = "concat(\"https://${var.zone_name}\", http.request.uri.path)"
        }
      }
    }
  }]
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

# ------------------------------------------------------------------------------ R2 public media
# Product/seller images only. KYC evidence never goes to R2 (object-locked bucket on the primary cloud).
resource "cloudflare_r2_bucket" "media" {
  account_id = var.account_id
  name       = "afrotransact-media-${var.environment}"
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
