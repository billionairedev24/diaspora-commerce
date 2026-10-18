# dev sizing and cost levers (the only file that differs between envs/dev, uat and prod).
#   - single zone, single NAT, no HA
#   - Aurora Serverless v2 min 0 ACU (auto-pause) / smallest Cloud SQL & Flexible tiers
#   - one small on-demand arm64 node; everything else lands on the spot pool (scales to zero)
#   - per-PR preview namespaces scale to zero when idle (KEDA/kube-downscaler in infra/k8s)
#   - Elastic hot-only, Confluent Basic (pay per use)
#   - short backups, KYC retention 1 day, no deletion protection
# NOTE: not part of the $200/month production budget. Keep this environment destroyed (local stack + CI previews cover
# development) and apply it only for a short, deliberate test; docs/engineering/cloud-cost.md.
locals {
  environment = "dev"
  sizing = {
    zone_count          = 1
    egress              = "single"
    node_size           = "small"
    min_nodes           = 1
    max_nodes           = 3
    spot_max_nodes      = 6
    private_endpoint    = false
    db_size             = "small"
    db_storage_gb       = 32
    backup_days         = 3
    cache_size          = "small"
    ha                  = false
    saas_profile        = "small"
    kyc_retention_days  = 1
    monthly_budget      = 1000 # USD, cloud spend only (modules/<cloud>/budget); alerts at 50/80/100% actual, 100% forecast
    managed_data        = true  # Terraform creates Postgres + Valkey; false = external (Layerbase), see prod
    deletion_protection = false
  }
}
