# Azure postgres: Azure Database for PostgreSQL Flexible Server 17, VNet-integrated (delegated subnet +
# private DNS zone, no public access), TLS enforced (require_secure_transport default ON), encrypted at rest
# (platform-managed keys; CMEK optional), zone-redundant HA when requested, geo-redundant backups in prod,
# CanNotDelete lock when deletion_protection. Per-service databases created natively; roles/grants by
# scripts/postgres-bootstrap.sh. Admin credential stored in Key Vault as <prefix>-postgres-admin.
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
    random  = { source = "hashicorp/random", version = ">= 3.6" }
  }
}

data "azurerm_client_config" "this" {}

locals {
  rg   = var.context.scope
  name = "${var.context.name}-pg"
  tags = merge(var.context.tags, { service = "platform", module = "postgres" })
  sku = {
    small  = "B_Standard_B1ms"
    medium = "GP_Standard_D2ds_v5"
    large  = "GP_Standard_D4ds_v5"
  }
}

resource "random_password" "admin" {
  length  = 40
  special = false
}

resource "azurerm_postgresql_flexible_server" "this" {
  name                          = local.name
  resource_group_name           = local.rg
  location                      = var.context.region
  version                       = var.engine_version
  sku_name                      = local.sku[var.size]
  storage_mb                    = var.storage_gb * 1024
  auto_grow_enabled             = true
  backup_retention_days         = var.backup_retention_days
  geo_redundant_backup_enabled  = var.context.environment == "prod"
  delegated_subnet_id           = var.network.postgres_subnet_id
  private_dns_zone_id           = var.network.private_dns_zone_ids["postgres"]
  public_network_access_enabled = false
  administrator_login           = "at_admin"
  administrator_password        = random_password.admin.result
  zone                          = "1"
  tags                          = local.tags

  authentication {
    active_directory_auth_enabled = true
    password_auth_enabled         = true
    tenant_id                     = data.azurerm_client_config.this.tenant_id
  }

  dynamic "high_availability" {
    for_each = var.high_availability ? [1] : []
    content {
      mode                      = "ZoneRedundant"
      standby_availability_zone = "2"
    }
  }

  maintenance_window {
    day_of_week  = 0
    start_hour   = 7
    start_minute = 0
  }

  depends_on = [var.network]
}

resource "azurerm_postgresql_flexible_server_configuration" "log_min_duration" {
  name      = "log_min_duration_statement"
  server_id = azurerm_postgresql_flexible_server.this.id
  value     = "500"
}

resource "azurerm_postgresql_flexible_server_database" "this" {
  for_each  = toset(var.databases)
  name      = each.value
  server_id = azurerm_postgresql_flexible_server.this.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

resource "azurerm_key_vault_secret" "admin" {
  name         = "${var.secret_store.prefix}-postgres-admin"
  key_vault_id = var.secret_store.key_vault_id
  content_type = "application/json"
  value = jsonencode({
    username = "at_admin"
    password = random_password.admin.result
    host     = azurerm_postgresql_flexible_server.this.fqdn
    port     = 5432
  })
  tags = local.tags
}

resource "azurerm_management_lock" "this" {
  count      = var.context.deletion_protection ? 1 : 0
  name       = "${local.name}-cannot-delete"
  scope      = azurerm_postgresql_flexible_server.this.id
  lock_level = "CanNotDelete"
  notes      = "Production database - remove the lock deliberately before destroying."
}
