output "hostnames" {
  value = local.hosts
}

output "turnstile_site_keys" {
  description = "Public site keys for the web apps (NEXT_PUBLIC_TURNSTILE_SITE_KEY_*)."
  value       = { for k, w in cloudflare_turnstile_widget.this : k => w.sitekey }
}

output "turnstile_secrets" {
  description = "Server-side secrets: put in identity / orders secrets as TURNSTILE_SECRET_<WIDGET>."
  value       = { for k, w in cloudflare_turnstile_widget.this : k => w.secret }
  sensitive   = true
}

output "tunnel_id" {
  value = var.origin_mode == "tunnel" ? cloudflare_zero_trust_tunnel_cloudflared.this[0].id : null
}

output "r2_buckets" {
  description = "Both R2 buckets (public media, private kyc-evidence). The only definition: docs/runbooks/r2-buckets.md follows it."
  value = {
    media        = cloudflare_r2_bucket.media.name
    kyc_evidence = cloudflare_r2_bucket.kyc_evidence.name
  }
}

output "web_origins" {
  description = "The three web origins: the AllowedOrigins of the private bucket's CORS policy (docs/runbooks/r2-buckets.md section 3)."
  value       = local.web_origins
}

output "pages_projects" {
  description = "Cloudflare Pages projects (created by the pages_projects_owner run) and this environment's docs host."
  value = {
    projects        = keys(local.pages_projects)
    developers_host = local.hosts["developers"]
  }
}
