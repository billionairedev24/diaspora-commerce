# Elasticsearch 9 on Elastic Cloud (elastic/ec), co-located with the chosen cloud/region so search and
# indexer stay low-latency. Same module on aws | gcp | azure.
# Tiers: hot (autoscaled) + warm + frozen (searchable snapshots) per environment profile; the ILM policies
# that move indices between them live in ilm/*.json and are applied by scripts/elastic-ilm.sh (the ec
# provider manages deployments, not index-level APIs).
terraform {
  required_version = ">= 1.8"
  required_providers {
    ec = { source = "elastic/ec", version = ">= 0.12, < 1.0" }
  }
}

variable "context" {
  description = "Same object as the cloud modules (name, environment, region, tags)."
  type = object({
    name                = string
    environment         = string
    region              = string
    scope               = optional(string, "")
    tags                = map(string)
    admin_cidrs         = optional(list(string), [])
    deletion_protection = optional(bool, true)
  })
}

variable "cloud" {
  description = "aws | gcp | azure - where Elastic Cloud runs the deployment."
  type        = string
}

variable "version_regex" {
  description = "Stack version to track."
  type        = string
  default     = "^9\\.[0-9]+\\.[0-9]+$"
}

variable "profile" {
  description = "small (dev: 1 zone, hot only) | medium (uat: hot + frozen) | large (prod: 2-3 zones, hot + warm + frozen)."
  type        = string
  default     = "small"
  validation {
    condition     = contains(["small", "medium", "large"], var.profile)
    error_message = "profile must be small, medium or large."
  }
}

variable "allowed_cidrs" {
  description = "Egress IPs of the cluster NAT (network.egress_ips) allowed to reach the deployment. Empty = no IP filter (use PrivateLink/PSC instead)."
  type        = list(string)
  default     = []
}

locals {
  # Elastic Cloud region ids: aws-us-east-1 / gcp-us-east4 / azure-eastus
  region = var.cloud == "aws" ? var.context.region : "${var.cloud}-${var.context.region}"
  template = {
    aws   = "aws-storage-optimized"
    gcp   = "gcp-storage-optimized"
    azure = "azure-storage-optimized"
  }
  sizes = {
    small  = { hot = "4g", hot_max = "8g", zones = 1, warm = null, frozen = null }
    medium = { hot = "4g", hot_max = "16g", zones = 1, warm = null, frozen = "4g" }
    large  = { hot = "8g", hot_max = "64g", zones = 3, warm = "4g", frozen = "4g" }
  }
  s = local.sizes[var.profile]
}

data "ec_stack" "this" {
  version_regex = var.version_regex
  region        = local.region
  lock          = true
}

resource "ec_deployment_traffic_filter" "this" {
  count  = length(var.allowed_cidrs) > 0 ? 1 : 0
  name   = "${var.context.name}-cluster-egress"
  region = local.region
  type   = "ip"
  dynamic "rule" {
    for_each = var.allowed_cidrs
    content {
      source      = rule.value
      description = "cluster NAT"
    }
  }
}

resource "ec_deployment" "this" {
  name                   = "${var.context.name}-search"
  region                 = local.region
  version                = data.ec_stack.this.version
  deployment_template_id = local.template[var.cloud]
  traffic_filter         = ec_deployment_traffic_filter.this[*].id
  tags                   = var.context.tags

  elasticsearch = {
    autoscale = true
    hot = {
      size       = local.s.hot
      zone_count = local.s.zones
      autoscaling = {
        max_size          = local.s.hot_max
        max_size_resource = "memory"
      }
    }
    warm = local.s.warm == null ? null : {
      size        = local.s.warm
      zone_count  = local.s.zones
      autoscaling = {}
    }
    frozen = local.s.frozen == null ? null : {
      size        = local.s.frozen
      zone_count  = 1
      autoscaling = {}
    }
  }

  kibana = {
    size       = "1g"
    zone_count = 1
  }
}
