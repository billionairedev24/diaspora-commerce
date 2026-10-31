variable "environment" {
  description = "dev | uat | prod"
  type        = string
  validation {
    condition     = contains(["dev", "uat", "prod"], var.environment)
    error_message = "environment must be dev, uat or prod."
  }
}

variable "storefront_host" {
  description = <<-EOT
    Canonical host of the customer storefront (docs/engineering/environments.md, "Storefront host rule"):
      www  - www.<base> serves the storefront (DNS record, Workers route) in every environment; <base> gets a proxied
             placeholder record and a 301 to www.<base> (the zone-wide redirect ruleset, owned by the manage_zone run)
      apex - explicit opt-out: <base> serves the storefront and nothing redirects
    Every other app keeps its sub-domain. Keep it equal to global.storefrontHost of infra/argocd/envs/<env>/platform-values.yaml.
  EOT
  type        = string
  default     = "www"
  validation {
    condition     = contains(["www", "apex"], var.storefront_host)
    error_message = "storefront_host must be www or apex."
  }
}

variable "apex_redirect_environments" {
  description = "Environments whose base domain (<zone>, dev.<zone>, uat.<zone>) 301-redirects to www.<base>. The redirect ruleset is zone-wide (one per zone and phase), so the manage_zone run owns the rules of all of them; drop an environment here when it sets storefront_host = apex."
  type        = list(string)
  default     = ["dev", "uat", "prod"]
}

variable "account_id" {
  description = "Cloudflare account id."
  type        = string
}

variable "zone_name" {
  description = "Apex zone."
  type        = string
  default     = "afrotransact.com"
}

variable "manage_zone" {
  description = "Own the zone-wide objects (settings, WAF, rate limits, AOP, cert packs). true in exactly one environment (prod)."
  type        = bool
  default     = false
}

variable "origin_mode" {
  description = <<-EOT
    How api./auth. reach the cluster:
      ingress - proxied DNS record to the cluster ingress load balancer (origin_target), Authenticated Origin Pulls
      tunnel  - Cloudflare Tunnel (cloudflared in-cluster); no public load balancer at all
  EOT
  type        = string
  default     = "tunnel"
  validation {
    condition     = contains(["ingress", "tunnel"], var.origin_mode)
    error_message = "origin_mode must be ingress or tunnel."
  }
}

variable "origin_target" {
  description = "origin_mode = ingress: load balancer hostname (CNAME) or IPv4 (A) of the cluster ingress."
  type        = string
  default     = ""
}

variable "tunnel_service" {
  description = "origin_mode = tunnel: in-cluster URL cloudflared forwards to (the Envoy gateway)."
  type        = string
  default     = "http://envoy-gateway.ingress.svc.cluster.local:80"
}

variable "workers_routes_enabled" {
  description = "Attach Workers routes. Deploy the Worker scripts once (web workflow) before enabling."
  type        = bool
  default     = true
}

variable "admin_allowlist" {
  description = "IPs/CIDRs allowed to send non-GET requests to /admin paths and to reach the staff console (zone-wide, manage_zone run)."
  type        = list(string)
  default     = []
}

variable "auth_rate_limit" {
  description = "Requests per period per IP per colo for /v1/auth/* before blocking."
  type = object({
    requests = number
    period   = number
    timeout  = number
  })
  default = { requests = 20, period = 60, timeout = 600 }
}

variable "checkout_rate_limit" {
  description = "Requests per period per IP per colo for checkout paths before blocking."
  type = object({
    requests = number
    period   = number
    timeout  = number
  })
  default = { requests = 30, period = 60, timeout = 300 }
}

variable "advanced_certificate_environments" {
  description = "Non-prod environments needing an ACM certificate for *.<env>.<zone> (two-level names are not covered by Universal SSL)."
  type        = list(string)
  default     = ["dev", "uat"]
}

variable "pages_projects_owner" {
  description = "This run creates the account-wide Cloudflare Pages projects (afrotransact-docs, afrotransact-storybook). Exactly one environment sets it (dev, the first one applied); every run attaches its own developers.<base> domain to the docs project."
  type        = bool
  default     = false
}
