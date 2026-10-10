# Cloudflare Pages (S249): account-wide projects for the developer portal and Storybook, deployed by direct upload from
# .github/workflows/docs.yml (afrotransact-docs) and storybook.yml (afrotransact-storybook) with production branch main;
# pull requests get <branch>.<project>.pages.dev previews. The docs are not environment-specific: every environment's
# developers.<base> host serves the production deployment of the same project.
locals {
  pages_projects = {
    afrotransact-docs      = "Developer portal (docs/portal, Docusaurus)"
    afrotransact-storybook = "Storybook of @afrotransact/ui"
  }
  docs_project = "afrotransact-docs"
}

resource "cloudflare_pages_project" "this" {
  for_each          = var.pages_projects_owner ? local.pages_projects : {}
  account_id        = var.account_id
  name              = each.key
  production_branch = "main"
}

# developers.<base> -> the docs project (the CNAME target is the project's pages.dev subdomain).
resource "cloudflare_pages_domain" "developers" {
  account_id   = var.account_id
  project_name = local.docs_project
  name         = local.hosts["developers"]
  depends_on   = [cloudflare_pages_project.this]
}

resource "cloudflare_dns_record" "developers" {
  zone_id = local.zone_id
  name    = local.hosts["developers"]
  type    = "CNAME"
  content = "${local.docs_project}.pages.dev"
  proxied = true
  ttl     = 1
  comment = "developer portal (${var.environment}) -> Cloudflare Pages ${local.docs_project}"
  tags    = local.tags
}
