# prod sizing and cost levers (the only file that differs between envs/dev, uat and prod).
# LEAN profile on OVHcloud: the whole platform on ONE node, one replica of everything, one project network, one zone.
# It is sized for the 21-service launch set (docs/engineering/cloud-cost.md has the memory table and the scale-up path):
#   - OVH Managed Kubernetes, FREE control plane (plan "free", single zone), ONE b3-16 node (4 vCPU / 16 GB) billed MONTHLY
#     (node_size = "lean" and deletion_protection = true in modules/ovh/cluster), no autoscaler, no spot pool
#   - egress "none": no vRack gateway, no load balancer, no public ingress: traffic comes in through Cloudflare Tunnel
#     (cloudflared pod, outbound connection only). The Kubernetes API is public on the free plan and restricted by admin_cidrs
#   - Postgres is external (Layerbase Virginia, same metro as OVH US East): managed_data = false creates none here;
#     Valkey and Meilisearch run in the cluster (infra/k8s/platform/lean)
#   - Kafka and Temporal run in the cluster; Elastic Cloud, Confluent and Temporal Cloud are off
#   - deletion protection and locked KYC retention (7 years) stay on: compliance does not scale down
# Scale up by flipping these back: zone_count 3 (needs the paid "standard" control plane) + min_nodes 3, a second node pool, or a
# managed database (managed_data = true, OVH Managed PostgreSQL).
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
    monthly_budget      = 150 # PLACEHOLDER, UNVERIFIED: OVH cloud spend only (node, block storage, object storage); set it to ~1.2x the OVH quote. Alerts at 50/80/100% actual
    managed_data        = false
    deletion_protection = true
  }
}
