# prod sizing and cost levers (the only file that differs between envs/dev, uat and prod).
# LEAN profile: the whole platform on ONE node, one replica of everything, one VPC, one zone. It is sized for ~20-200
# active users and a USD 200/month ceiling (docs/engineering/cloud-cost.md has the line items and the scale-up path).
#   - ONE zonal GKE cluster (free-tier control plane), ONE e2-standard-4 node, no system/spot pool, no autoscaling
#   - ONE VPC, no Cloud NAT (egress "none": nodes have public IPs, inbound closed, Cloudflare Tunnel for traffic in)
#   - Postgres, Valkey and Meilisearch are external (Layerbase): managed_data = false creates none of them here
#   - Kafka and Temporal run in the cluster (infra/k8s/platform/lean); Elastic Cloud, Confluent and Temporal Cloud off
#   - deletion protection and locked KYC retention (7 years) stay on: compliance does not scale down
# Scale up by flipping these back: zone_count 3 + min_nodes 3 (HA), managed_data = true, enable_* in terraform.tfvars.
locals {
  environment = "prod"
  sizing = {
    zone_count          = 1
    egress              = "none"
    node_size           = "lean"
    min_nodes           = 1
    max_nodes           = 1
    spot_max_nodes      = 0
    private_endpoint    = false
    db_size             = "small"
    db_storage_gb       = 32
    backup_days         = 7
    cache_size          = "small"
    ha                  = false
    saas_profile        = "small"
    kyc_retention_days  = 2555
    monthly_budget      = 200 # USD, cloud spend only (modules/<cloud>/budget); alerts at 50/80/100% actual, 100% forecast
    managed_data        = false
    deletion_protection = true
  }
}
