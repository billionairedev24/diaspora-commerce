# OVHcloud has no workload identity for pods and this stack uses no OVH Object Storage (media and KYC evidence live in
# Cloudflare R2, backups in Backblaze B2: docs/runbooks/r2-buckets.md), so there are no per-service Object Storage
# users and no S3_* secrets. This module has no resources: it only emits the `cloud = ovh` Helm values fragment so the
# environments expose the same `identity.helm_values` shape on every cloud.
# External Secrets Operator authenticates with its own mTLS certificate (secrets module).
terraform {
  required_version = ">= 1.8"
}
