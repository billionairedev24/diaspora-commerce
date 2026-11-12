# Cloudflare edge for the 8-day TRIAL (OVH Managed Kubernetes + Layerbase). Non-secret values only; the API token comes from
# CLOUDFLARE_API_TOKEN. docs/runbooks/ovh-production.md, section "Trial".
#
# The trial runs the PROD profile with flat single-label hosts, all directly under zone_name (Cloudflare's free certificate covers
# the apex and one label):
#   https://test.<zone>          storefront (Worker storefront-trial)
#   https://seller-test.<zone>   Seller Center (Worker seller-center-trial)
#   https://admin-test.<zone>    staff console (Worker staff-console-trial)
#   https://api-test.<zone>      gateway-bff and the webhooks, through the Tunnel
#   https://auth-test.<zone>     identity, through the Tunnel
#   https://media-test.<zone>    R2 public bucket afrotransact-trial-media
# There is no mcp, grafana or developers host and no load balancer. Every host comes from infra/cloudflare/hosts.json (env trial,
# suffix test); the zone is a variable: to run the trial in another zone use `python3 tools/ci/env_hosts.py set-trial-zone <zone>`, which also moves Argo CD,
# the edge routes and the Worker config (`python3 tools/ci/env_hosts.py check` proves they agree).
environment        = "trial"
account_id         = "REPLACE_WITH_CLOUDFLARE_ACCOUNT_ID"
developers_enabled = false

# Zone-wide objects (WAF, rate limits, HSTS, admin allow-list) belong to the run of the zone's owner: the prod run when the trial
# shares the prod zone (its custom rules already cover admin-<label>.), THIS run when the trial has a zone of its own: then set
# manage_zone = true, fill admin_allowlist and keep edge_response_header_environments = ["trial"].
manage_zone                       = false
edge_response_header_environments = []

# No load balancer: cloudflared in the cluster (infra/k8s/platform/edge/overlays/trial).
origin_mode    = "tunnel"
tunnel_service = "http://envoy-gateway.ingress.svc.cluster.local:80"

# Deploy the three Worker scripts once (the trial workflow) before applying with true; with false only DNS, the Tunnel and R2 exist.
workers_routes_enabled = true
