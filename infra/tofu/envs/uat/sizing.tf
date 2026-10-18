# uat sizing and cost levers (the only file that differs between envs/dev, uat and prod).
#   - prod-like topology at a smaller size: 2 zones, single NAT, HA off (turn on before load tests)
#   - Serverless v2 0.5-16 ACU / mid Cloud SQL & Flexible tiers, Valkey medium
#   - spot pool carries most workloads; on-demand floor of 2 nodes
#   - Elastic hot + frozen, Confluent Standard single-zone
#   - KYC retention 30 days (governance mode, unlocked) so test evidence can be cleaned up
# NOTE: not part of the $200/month production budget. Keep this environment destroyed (local stack + CI previews cover
# development) and apply it only for a short, deliberate test; docs/engineering/cloud-cost.md.
locals {
  environment = "uat"
  sizing = {
    zone_count          = 2
    egress              = "single"
    node_size           = "small"
    min_nodes           = 2
    max_nodes           = 4
    spot_max_nodes      = 10
    private_endpoint    = false
    db_size             = "medium"
    db_storage_gb       = 64
    backup_days         = 7
    cache_size          = "medium"
    ha                  = false
    saas_profile        = "medium"
    kyc_retention_days  = 30
    monthly_budget      = 1500 # USD, cloud spend only (modules/<cloud>/budget); alerts at 50/80/100% actual, 100% forecast
    managed_data        = true # Terraform creates Postgres + Valkey; false = external (Layerbase), see prod
    deletion_protection = true
  }
}
