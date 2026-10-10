# prod sizing and cost levers (the only file that differs between envs/dev, uat and prod).
#   - multi-AZ everywhere: 3 zones, NAT per zone, HA Postgres (writer + reader / REGIONAL / zone-redundant),
#     Valkey replica with failover, Elastic 3-zone hot + warm + frozen, Confluent Standard multi-zone
#   - on-demand arm64 floor (3 nodes) is the steady baseline: cover ~60% of it with Compute Savings Plans
#     (AWS), committed-use discounts (GCP) or Azure savings plan / reservations; spot above it
#   - deletion protection, locked KYC retention (7 years, COMPLIANCE / locked policy), 30-day backups
#   - private Kubernetes API is recommended once a VPN/Tunnel is in place (private_endpoint = true)
locals {
  environment = "prod"
  sizing = {
    zone_count          = 3
    egress              = "per-zone"
    node_size           = "medium"
    min_nodes           = 3
    max_nodes           = 12
    spot_max_nodes      = 30
    private_endpoint    = false
    db_size             = "large"
    db_storage_gb       = 256
    backup_days         = 30
    cache_size          = "large"
    ha                  = true
    saas_profile        = "large"
    kyc_retention_days  = 2555
    deletion_protection = true
  }
}
