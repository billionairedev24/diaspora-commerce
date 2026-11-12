variable "environment" {
  description = <<-EOT
    dev | uat | trial | prod. Every host comes from the map infra/cloudflare/hosts.json (see main.tf): prod is <zone> and <role>.<zone>,
    any other env is <suffix>.<zone> and <role>-<suffix>.<zone> (Cloudflare's free certificate covers the apex and one label only).
    trial is the 8-day OVH run of the prod profile (docs/runbooks/ovh-production.md, "Trial"); its suffix is test.
  EOT
  type        = string
  validation {
    condition     = contains(["dev", "uat", "trial", "prod"], var.environment)
    error_message = "environment must be dev, uat, trial or prod."
  }
}

variable "account_id" {
  description = "Cloudflare account id."
  type        = string
}

variable "zone_name" {
  description = "Zone override. Empty (default) = the zone of the environment in infra/cloudflare/hosts.json."
  type        = string
  default     = ""
}

variable "manage_zone" {
  description = "Own the zone-wide objects (settings, WAF, rate limits, AOP, cert packs). true in exactly one environment (prod)."
  type        = bool
  default     = false
}

variable "origin_mode" {
  description = <<-EOT
    How the api and auth hosts reach the cluster (trial: always tunnel, there is no load balancer):
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

variable "hsts_enabled" {
  description = "Send Strict-Transport-Security from the edge (zone-wide Cloudflare setting, owned by the manage_zone run). Over HTTPS only."
  type        = bool
  default     = true
}

variable "hsts_max_age" {
  description = "HSTS max-age in seconds (1 year). Lower it (for example 300) for a first rollout, raise it back once every sub-domain serves HTTPS."
  type        = number
  default     = 31536000
}

variable "hsts_preload" {
  description = "Add the preload token to HSTS. Keep false until the apex is submitted at hstspreload.org (docs/runbooks/production-readiness.md); the list is hard to leave."
  type        = bool
  default     = false
}

variable "edge_response_header_environments" {
  description = "Environments whose hosts get the edge baseline Referrer-Policy (when the origin sends none). One zone-wide ruleset, owned by the manage_zone run; remove an environment to switch it off there."
  type        = list(string)
  default     = ["dev", "uat", "trial", "prod"]
  validation {
    condition     = alltrue([for e in var.edge_response_header_environments : contains(["dev", "uat", "trial", "prod"], e)])
    error_message = "edge_response_header_environments may only contain dev, uat, trial, prod."
  }
}

variable "pages_projects_owner" {
  description = "This run creates the account-wide Cloudflare Pages projects (afrotransact-docs, afrotransact-storybook). Exactly one environment sets it (dev, the first one applied); every run attaches its own developers.<base> domain to the docs project."
  type        = bool
  default     = false
}

variable "developers_enabled" {
  description = "Attach developers.<host> (the docs portal on Cloudflare Pages) to this environment. false for the trial."
  type        = bool
  default     = true
}
