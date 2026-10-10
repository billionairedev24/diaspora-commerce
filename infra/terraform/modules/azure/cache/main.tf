# Azure cache: Azure Cache for Redis (Redis 6 - Azure does not offer Valkey; the protocol is compatible),
# TLS 1.2 only (port 6380), non-TLS port off, public network off, private endpoint in the endpoint subnet.
#   small -> Standard C1 (replicated, no zones) | medium -> Premium P1 | large -> Premium P2 (zones when HA)
# Note: Microsoft is steering new workloads to Azure Managed Redis (azurerm_managed_redis); swapping the
# resource here keeps the module interface unchanged.
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
  }
}

locals {
  rg   = var.context.scope
  name = "${var.context.name}-redis"
  tags = merge(var.context.tags, { service = "platform", module = "cache" })
  sku = {
    small  = { name = "Standard", family = "C", capacity = 1 }
    medium = { name = "Premium", family = "P", capacity = 1 }
    large  = { name = "Premium", family = "P", capacity = 2 }
  }
  premium = local.sku[var.size].name == "Premium"
}

resource "azurerm_redis_cache" "this" {
  name                          = local.name
  location                      = var.context.region
  resource_group_name           = local.rg
  sku_name                      = local.sku[var.size].name
  family                        = local.sku[var.size].family
  capacity                      = local.sku[var.size].capacity
  minimum_tls_version           = "1.2"
  non_ssl_port_enabled          = false
  public_network_access_enabled = false
  redis_version                 = "6"
  zones                         = local.premium && var.high_availability ? ["1", "2"] : null
  replicas_per_primary          = local.premium && var.high_availability ? 1 : null
  tags                          = local.tags

  redis_configuration {
    maxmemory_policy = "allkeys-lru"
  }
}

resource "azurerm_private_endpoint" "this" {
  name                = local.name
  location            = var.context.region
  resource_group_name = local.rg
  subnet_id           = var.network.endpoint_subnet_id
  tags                = local.tags

  private_service_connection {
    name                           = local.name
    private_connection_resource_id = azurerm_redis_cache.this.id
    subresource_names              = ["redisCache"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "redis"
    private_dns_zone_ids = [var.network.private_dns_zone_ids["redis"]]
  }
}
