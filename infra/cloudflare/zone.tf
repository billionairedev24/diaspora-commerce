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

# HSTS + X-Content-Type-Options: nosniff, written by Cloudflare on every proxied HTTPS response of the zone (zone-wide, so
# not switchable per environment; the baseline Referrer-Policy below is). HSTS is 1 year with includeSubDomains; preload
# is OFF until hsts_preload is set (only when every sub-domain is HTTPS-only and the apex is about to be submitted at
# hstspreload.org: a preloaded domain cannot be taken back quickly). Applications keep their own CSP: nothing here sets
# Content-Security-Policy (docs/engineering/security-headers.md).
resource "cloudflare_zone_setting" "hsts" {
  count      = local.zone_count
  zone_id    = local.zone_id
  setting_id = "security_header"
  value = {
    strict_transport_security = {
      enabled            = var.hsts_enabled
      max_age            = var.hsts_max_age
      include_subdomains = true
      preload            = var.hsts_preload
      nosniff            = true
    }
  }
}

# Baseline Referrer-Policy for the hosts of the environments in edge_response_header_environments, only when the origin
# sent none (the three Next.js apps and identity send strict-origin-when-cross-origin themselves), so an app's own stricter
# value always wins. A zone has one response-header ruleset, so the manage_zone run owns the rules of all environments.
locals {
  # Hosts of an environment, from the map (hosts.json): flat names, so the list is exact (no wildcard).
  edge_header_host_expressions = {
    for env in var.edge_response_header_environments :
    env => "http.host in {${join(" ", [for h in local.env_all_hosts[env] : "\"${h}\""])}}"
  }

  # Crawler directives (docs/engineering/seo.md), all derived from hosts.json: only the prod storefront is indexable.
  #  - every host of every NON-prod environment (storefront, seller, admin, api, auth, media, developers, ...): X-Robots-Tag noindex
  #    on every response, static assets included (the Worker's own middleware covers dynamic responses; this covers the rest);
  #  - prod seller / admin / api / auth / grafana / mcp: noindex always (back offices and machine endpoints);
  #  - prod media: noindex for everything that is not an image (images stay indexable for Google Images).
  noindex_value      = "noindex, nofollow, noarchive"
  prod_zone          = lookup(local.hostmap.envs["prod"], "zone", local.hostmap.zone)
  noindex_hosts_all  = flatten([for env, hosts in local.env_all_hosts : hosts if env != "prod"])
  prod_noindex_hosts = [for r in local.all_roles : "${r}.${local.prod_zone}" if !contains(["media", "developers"], r)]
  prod_media_host    = "media.${local.prod_zone}"
  noindex_rules = [
    {
      description = "non-prod hosts: X-Robots-Tag noindex"
      expression  = "http.host in {${join(" ", [for h in local.noindex_hosts_all : "\"${h}\""])}}"
    },
    {
      description = "prod back offices and machine endpoints: X-Robots-Tag noindex"
      expression  = "http.host in {${join(" ", [for h in local.prod_noindex_hosts : "\"${h}\""])}}"
    },
    {
      description = "prod media origin: X-Robots-Tag noindex except images"
      expression  = "http.host eq \"${local.prod_media_host}\" and not starts_with(http.response.content_type.media_type, \"image/\")"
    },
  ]
}

resource "cloudflare_ruleset" "response_headers" {
  count   = local.zone_count
  zone_id = local.zone_id
  name    = "baseline response headers"
  kind    = "zone"
  phase   = "http_response_headers_transform"
  rules = concat(
    [for r in local.noindex_rules : {
      description = r.description
      expression  = r.expression
      action      = "rewrite"
      enabled     = true
      action_parameters = {
        headers = {
          "X-Robots-Tag" = {
            operation = "set"
            value     = local.noindex_value
          }
        }
      }
    }],
    [for env, host_expression in local.edge_header_host_expressions : {
      description = "${env}: Referrer-Policy when the origin sent none"
      expression  = "${host_expression} and not any(http.response.headers.names[*] eq \"referrer-policy\")"
      action      = "rewrite"
      enabled     = true
      action_parameters = {
        headers = {
          "Referrer-Policy" = {
            operation = "set"
            value     = "strict-origin-when-cross-origin"
          }
        }
      }
    }]
  )
}

# Authenticated Origin Pulls (zone-level, Cloudflare origin-pull CA): the cluster ingress (Envoy) must
# require the client certificate (infra/k8s) so only Cloudflare can reach the origin.
resource "cloudflare_authenticated_origin_pulls_settings" "this" {
  count   = local.zone_count
  zone_id = local.zone_id
  enabled = true
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
      expression  = "(starts_with(http.host, \"admin.\") or starts_with(http.host, \"admin-\")) and not ip.src in $afrotransact_admin_allowlist"
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
