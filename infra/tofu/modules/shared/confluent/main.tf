# Kafka on Confluent Cloud (confluentinc/confluent), in the same cloud/region as the cluster. Same module
# on aws | gcp | azure, so producers/consumers and the schema registry never change when the cloud does.
#   small  (dev)  -> Basic, single zone          (pay per use, scales to ~0)
#   medium (uat)  -> Standard, single zone
#   large  (prod) -> Standard, multi zone        (Enterprise/Dedicated + PrivateLink when traffic justifies)
# Stream Governance ESSENTIALS gives the schema registry. Each service gets its own service account and
# API key; ACLs: WRITE on the topics it produces, READ on topics it consumes, READ on its consumer groups by PREFIX
# (the real group ids, e.g. payments-service, payments-service-referral; var.consumer_groups), and WRITE on the
# <topic>.dlq of every topic a Java service consumes (var.dlq_writers). Topics, producers, consumers and group prefixes
# come from infra/tofu/kafka-topics.json, generated from contracts/asyncapi and the code and checked in CI by
# tools/ops/topics-check.sh (docs/engineering/kafka-topics-and-acls.md).
terraform {
  required_version = ">= 1.8"
  required_providers {
    confluent = { source = "confluentinc/confluent", version = ">= 2.0, < 3.0" }
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
  description = "aws | gcp | azure."
  type        = string
}

variable "profile" {
  description = "small | medium | large (see header)."
  type        = string
  default     = "small"
  validation {
    condition     = contains(["small", "medium", "large"], var.profile)
    error_message = "profile must be small, medium or large."
  }
}

variable "topics" {
  description = "Topic name => {partitions, retention_ms, producers, consumers}."
  type = map(object({
    partitions   = optional(number, 6)
    retention_ms = optional(number, 604800000)
    producers    = list(string)
    consumers    = optional(list(string), [])
  }))
}

variable "services" {
  description = "Services that get Kafka credentials (superset of producers/consumers)."
  type        = list(string)
}

variable "consumer_groups" {
  description = "Service => consumer group id prefixes it may READ (PREFIXED ACL). Must cover every group id the service uses."
  type        = map(list(string))
  default     = {}
}

variable "dlq_writers" {
  description = "Services whose consumers dead-letter into <topic>.dlq (the Java platform-events error handler). Go consumers keep their own dead-letter topic or store."
  type        = list(string)
  default     = []
}

variable "dlq_retention_ms" {
  description = "Retention of the <topic>.dlq dead-letter topics (default 14 days, longer than the source topics)."
  type        = number
  default     = 1209600000
}

locals {
  cloud  = upper(var.cloud)
  prefix = var.context.name
  # Java consumers (platform-events) dead-letter a record they cannot process into <topic>.dlq, so every consumer
  # of a topic may WRITE to its DLQ. Replay (tools/ops/kafka-replay) uses the admin identity. See docs/runbooks/kafka-replay.md.
  # A topic that already is a dead-letter topic (the Go at.indexer.dlq.events) gets no further .dlq.
  dlq_topics = {
    for t, v in var.topics : "${t}.dlq" => {
      partitions   = v.partitions
      retention_ms = var.dlq_retention_ms
      producers    = [for c in v.consumers : c if contains(var.dlq_writers, c)]
      consumers    = []
    } if !strcontains(t, ".dlq.")
  }
  all_topics = merge(var.topics, local.dlq_topics)
  acl_write  = merge([for t, v in local.all_topics : { for s in v.producers : "${s}/${t}" => { svc = s, topic = t } }]...)
  acl_read   = merge([for t, v in local.all_topics : { for s in v.consumers : "${s}/${t}" => { svc = s, topic = t } }]...)
  acl_groups = merge([for s, prefixes in var.consumer_groups : { for p in prefixes : "${s}/${p}" => { svc = s, prefix = p } }]...)
  rest      = confluent_kafka_cluster.this.rest_endpoint
  admin_credentials = {
    key    = confluent_api_key.admin.id
    secret = confluent_api_key.admin.secret
  }
}

resource "confluent_environment" "this" {
  display_name = local.prefix
  stream_governance {
    package = "ESSENTIALS"
  }
}

resource "confluent_kafka_cluster" "this" {
  display_name = "${local.prefix}-kafka"
  availability = var.profile == "large" ? "MULTI_ZONE" : "SINGLE_ZONE"
  cloud        = local.cloud
  region       = var.context.region

  dynamic "basic" {
    for_each = var.profile == "small" ? [1] : []
    content {}
  }
  dynamic "standard" {
    for_each = var.profile == "small" ? [] : [1]
    content {}
  }

  environment {
    id = confluent_environment.this.id
  }
}

data "confluent_schema_registry_cluster" "this" {
  environment {
    id = confluent_environment.this.id
  }
  depends_on = [confluent_kafka_cluster.this]
}

# Terraform's own admin identity for topic/ACL management.
resource "confluent_service_account" "admin" {
  display_name = "${local.prefix}-terraform"
  description  = "Manages topics and ACLs for ${local.prefix}"
}

resource "confluent_role_binding" "admin" {
  principal   = "User:${confluent_service_account.admin.id}"
  role_name   = "CloudClusterAdmin"
  crn_pattern = confluent_kafka_cluster.this.rbac_crn
}

resource "confluent_api_key" "admin" {
  display_name = "${local.prefix}-terraform"
  owner {
    id          = confluent_service_account.admin.id
    api_version = confluent_service_account.admin.api_version
    kind        = confluent_service_account.admin.kind
  }
  managed_resource {
    id          = confluent_kafka_cluster.this.id
    api_version = confluent_kafka_cluster.this.api_version
    kind        = confluent_kafka_cluster.this.kind
    environment {
      id = confluent_environment.this.id
    }
  }
  depends_on = [confluent_role_binding.admin]
}

resource "confluent_kafka_topic" "this" {
  for_each         = local.all_topics
  topic_name       = each.key
  partitions_count = each.value.partitions
  rest_endpoint    = local.rest
  config = {
    "cleanup.policy"      = "delete"
    "retention.ms"        = tostring(each.value.retention_ms)
    "min.insync.replicas" = "2"
  }
  kafka_cluster {
    id = confluent_kafka_cluster.this.id
  }
  credentials {
    key    = local.admin_credentials.key
    secret = local.admin_credentials.secret
  }
}

# ---------------------------------------------------------------- per-service identities
resource "confluent_service_account" "service" {
  for_each     = toset(var.services)
  display_name = "${local.prefix}-${each.key}"
  description  = "Kafka client identity for ${each.key}"
}

resource "confluent_api_key" "service" {
  for_each     = toset(var.services)
  display_name = "${local.prefix}-${each.key}"
  owner {
    id          = confluent_service_account.service[each.key].id
    api_version = confluent_service_account.service[each.key].api_version
    kind        = confluent_service_account.service[each.key].kind
  }
  managed_resource {
    id          = confluent_kafka_cluster.this.id
    api_version = confluent_kafka_cluster.this.api_version
    kind        = confluent_kafka_cluster.this.kind
    environment {
      id = confluent_environment.this.id
    }
  }
}

resource "confluent_kafka_acl" "write" {
  for_each      = local.acl_write
  resource_type = "TOPIC"
  resource_name = confluent_kafka_topic.this[each.value.topic].topic_name
  pattern_type  = "LITERAL"
  principal     = "User:${confluent_service_account.service[each.value.svc].id}"
  host          = "*"
  operation     = "WRITE"
  permission    = "ALLOW"
  rest_endpoint = local.rest
  kafka_cluster {
    id = confluent_kafka_cluster.this.id
  }
  credentials {
    key    = local.admin_credentials.key
    secret = local.admin_credentials.secret
  }
}

resource "confluent_kafka_acl" "read" {
  for_each      = local.acl_read
  resource_type = "TOPIC"
  resource_name = confluent_kafka_topic.this[each.value.topic].topic_name
  pattern_type  = "LITERAL"
  principal     = "User:${confluent_service_account.service[each.value.svc].id}"
  host          = "*"
  operation     = "READ"
  permission    = "ALLOW"
  rest_endpoint = local.rest
  kafka_cluster {
    id = confluent_kafka_cluster.this.id
  }
  credentials {
    key    = local.admin_credentials.key
    secret = local.admin_credentials.secret
  }
}

resource "confluent_kafka_acl" "group" {
  for_each      = local.acl_groups
  resource_type = "GROUP"
  resource_name = each.value.prefix
  pattern_type  = "PREFIXED"
  principal     = "User:${confluent_service_account.service[each.value.svc].id}"
  host          = "*"
  operation     = "READ"
  permission    = "ALLOW"
  rest_endpoint = local.rest
  kafka_cluster {
    id = confluent_kafka_cluster.this.id
  }
  credentials {
    key    = local.admin_credentials.key
    secret = local.admin_credentials.secret
  }
}
