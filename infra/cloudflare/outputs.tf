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

output "r2_media_bucket" {
  value = cloudflare_r2_bucket.media.name
}

output "pages_projects" {
  description = "Cloudflare Pages projects (created by the pages_projects_owner run) and this environment's docs host."
  value = {
    projects        = keys(local.pages_projects)
    developers_host = local.hosts["developers"]
  }
}
