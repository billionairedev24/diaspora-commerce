# OVHcloud Secret Manager (OKMS): one secret per service holding a JSON document, path <prefix>/<env>/<service> (the "/"
# form of the Helm library chart, so no keySeparator override is needed). The KMS domain is created through the OVH order
# workflow (free, but the account needs a default payment method and the apply consumer key must allow the order routes,
# see the provider guide "Consumer key access rules for resources that place orders").
# Seed values are written once; later edits happen in the store and are ignored here (lifecycle ignore_changes).
# External Secrets Operator has no native OVH provider: the ClusterSecretStore below uses its Vault provider against the
# OKMS KV-v2 API with a client certificate (mTLS credential created in the Control Panel and stored in the cluster as the
# Secret external-secrets/okms-client). Verify the mount path once on first use (docs/engineering/cloud-cost.md, OVHcloud).
terraform {
  required_version = ">= 1.8"
  required_providers {
    ovh = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
  }
}

data "ovh_me" "this" {}

locals {
  prefix = var.prefix
  site   = upper(regex("^[A-Za-z]+", var.context.region))
  # Secret Manager regions are named after the OVH site.
  okms_regions = {
    GRA = "eu-west-rbx"
    RBX = "eu-west-rbx"
    SBG = "eu-west-sbg"
    PAR = "eu-west-par"
    BHS = "ca-east-bhs"
  }
  okms_region = lookup(local.okms_regions, local.site, "eu-west-rbx")
}

resource "ovh_okms" "this" {
  ovh_subsidiary = data.ovh_me.this.ovh_subsidiary
  region         = local.okms_region
  display_name   = var.context.name
}

resource "ovh_okms_secret" "service" {
  for_each = toset(var.services)
  okms_id  = ovh_okms.this.id
  path     = "${local.prefix}/${var.context.environment}/${each.value}"
  version = {
    data = jsonencode(try(nonsensitive(var.initial_values[each.value]), {}))
  }

  lifecycle {
    ignore_changes = [version]
  }
}
