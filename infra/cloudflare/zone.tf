# Zone-wide security baseline (owned by the manage_zone = true run).

locals {
  zone_count = var.manage_zone ? 1 : 0
  zone_settings = {
    ssl                      = "strict"
    always_use_https         = "on"
    min_tls_version          = "1.2"
    tls_1_3                  = "on"
    automatic_https_rewrites = "on"
    opportunistic_encryption = "on"
    http3                    = "on"
    "0rtt"                   = "off"
    browser_check            = "on"
    security_level           = "medium"
    ip_geolocation           = "on"
    email_obfuscation        = "on"
  }
}

resource "cloudflare_zone_setting" "this" {
  for_each   = var.manage_zone ? local.zone_settings : {}
  zone_id    = local.zone_id
  setting_id = each.key
  value      = each.value
}

# HSTS (1 year, subdomains, preload) + X-Content-Type-Options: nosniff.
resource "cloudflare_zone_setting" "hsts" {
  count      = local.zone_count
  zone_id    = local.zone_id
  setting_id = "security_header"
  value = {
    strict_transport_security = {
      enabled            = true
      max_age            = 31536000
      include_subdomains = true
      preload            = true
      nosniff            = true
    }
  }
}

# Authenticated Origin Pulls (zone-level, Cloudflare origin-pull CA): the cluster ingress (Envoy) must
# require the client certificate (infra/k8s) so only Cloudflare can reach the origin.
resource "cloudflare_authenticated_origin_pulls_settings" "this" {
  count   = local.zone_count
  zone_id = local.zone_id
  enabled = true
}

# Two-level hostnames (seller.dev.afrotransact.com) need an advanced certificate.
resource "cloudflare_certificate_pack" "env" {
  for_each              = var.manage_zone ? toset(var.advanced_certificate_environments) : toset([])
  zone_id               = local.zone_id
  type                  = "advanced"
  hosts                 = ["${each.key}.${var.zone_name}", "*.${each.key}.${var.zone_name}"]
  validation_method     = "txt"
  validity_days         = 90
  certificate_authority = "google"
  cloudflare_branding   = false
}

# ------------------------------------------------------------------------------ admin allow-list
resource "cloudflare_list" "admin_allowlist" {
  count       = local.zone_count
  account_id  = var.account_id
  name        = "afrotransact_admin_allowlist"
  kind        = "ip"
  description = "Office/VPN egress allowed to write to /admin and open the staff console"
  items       = [for ip in var.admin_allowlist : { ip = ip, comment = "admin" }]
}

# ------------------------------------------------------------------------------ WAF: managed rules
resource "cloudflare_ruleset" "managed_waf" {
  count       = local.zone_count
  zone_id     = local.zone_id
  name        = "AfroTransact managed WAF"
  description = "Cloudflare Managed Ruleset + OWASP Core Ruleset on every hostname"
  kind        = "zone"
  phase       = "http_request_firewall_managed"
  rules = [
    {
      description = "Cloudflare Managed Ruleset"
      expression  = "true"
      action      = "execute"
      enabled     = true
      action_parameters = {
        id = "efb7b8c949ac4650a09736fc376e9aee"
      }
    },
    {
      description = "Cloudflare OWASP Core Ruleset"
      expression  = "true"
      action      = "execute"
      enabled     = true
      action_parameters = {
        id = "4814384a9e5d4991b9815dcfc25d2f1f"
        overrides = {
          rules = [{
            id              = "6179ae15870a4bb7b2d480d4843b323c" # anomaly score threshold rule
            action          = "block"
            score_threshold = 40
          }]
        }
      }
    },
  ]
}

# ------------------------------------------------------------------------------ WAF: custom rules
resource "cloudflare_ruleset" "custom_waf" {
  count       = local.zone_count
  zone_id     = local.zone_id
  name        = "AfroTransact custom rules"
  description = "Route-level rules"
  kind        = "zone"
  phase       = "http_request_firewall_custom"
  rules = [
    {
      description = "Block non-GET to /admin from outside the allow-list"
      expression  = "(starts_with(http.request.uri.path, \"/admin\") or starts_with(http.request.uri.path, \"/v1/admin\")) and http.request.method ne \"GET\" and not ip.src in $afrotransact_admin_allowlist"
      action      = "block"
      enabled     = true
    },
    {
      description = "Challenge the staff console from outside the allow-list"
      expression  = "starts_with(http.host, \"admin.\") and not ip.src in $afrotransact_admin_allowlist"
      action      = "managed_challenge"
      enabled     = true
    },
    {
      description = "Block actuator/internal endpoints at the edge"
      expression  = "starts_with(http.request.uri.path, \"/actuator\") or starts_with(http.request.uri.path, \"/internal\")"
      action      = "block"
      enabled     = true
    },
    {
      description = "Only expected methods"
      expression  = "not http.request.method in {\"GET\" \"HEAD\" \"POST\" \"PUT\" \"PATCH\" \"DELETE\" \"OPTIONS\"}"
      action      = "block"
      enabled     = true
    },
  ]
  depends_on = [cloudflare_list.admin_allowlist]
}

# ------------------------------------------------------------------------------ rate limiting
resource "cloudflare_ruleset" "rate_limit" {
  count       = local.zone_count
  zone_id     = local.zone_id
  name        = "AfroTransact rate limits"
  description = "Credential stuffing and checkout abuse protection"
  kind        = "zone"
  phase       = "http_ratelimit"
  rules = [
    {
      description = "Rate limit /v1/auth"
      expression  = "starts_with(http.request.uri.path, \"/v1/auth\") or starts_with(http.request.uri.path, \"/oauth2/token\") or starts_with(http.request.uri.path, \"/login\")"
      action      = "block"
      enabled     = true
      ratelimit = {
        characteristics     = ["cf.colo.id", "ip.src"]
        period              = var.auth_rate_limit.period
        requests_per_period = var.auth_rate_limit.requests
        mitigation_timeout  = var.auth_rate_limit.timeout
      }
    },
    {
      description = "Rate limit checkout"
      expression  = "http.request.uri.path contains \"/checkout\" and http.request.method ne \"GET\""
      action      = "block"
      enabled     = true
      ratelimit = {
        characteristics     = ["cf.colo.id", "ip.src"]
        period              = var.checkout_rate_limit.period
        requests_per_period = var.checkout_rate_limit.requests
        mitigation_timeout  = var.checkout_rate_limit.timeout
      }
    },
  ]
}
