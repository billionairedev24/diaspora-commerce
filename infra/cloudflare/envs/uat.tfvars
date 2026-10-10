# Cloudflare edge for uat. Non-secret values only; the API token comes from CLOUDFLARE_API_TOKEN.
environment = "uat"
account_id  = "REPLACE_WITH_CLOUDFLARE_ACCOUNT_ID"
zone_name   = "afrotransact.com"
manage_zone = false # zone-wide WAF/rate limits/settings are owned by the prod run

# Origin for api.… and auth.…: "tunnel" (cloudflared in the cluster, no public LB) or "ingress".
origin_mode    = "tunnel"
tunnel_service = "http://envoy-gateway.ingress.svc.cluster.local:80"
# origin_mode   = "ingress"
# origin_target = "k8s-ingress-1234.elb.us-east-1.amazonaws.com" # or the GKE/AKS LB IP

workers_routes_enabled = true
