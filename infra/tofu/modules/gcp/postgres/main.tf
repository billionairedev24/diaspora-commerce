# GCP postgres: Cloud SQL for PostgreSQL 17 (Enterprise edition), private IP only via Private Service
# Access, TLS required, PITR, IAM database auth, REGIONAL (HA) when requested. Per-service databases are
# created natively; owner roles/grants are still applied by scripts/postgres-bootstrap.sh so all three
# clouds end up identical. The admin password lives in Secret Manager (<prefix>-<env>-postgres-admin).
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
    random = { source = "hashicorp/random", version = ">= 3.6" }
  }
}

locals {
  project = var.context.scope
  name    = "${var.context.name}-pg"
  labels = { for k, v in merge(var.context.tags, { service = "platform", module = "postgres" }) :
  lower(replace(k, "/[^a-zA-Z0-9_-]/", "_")) => lower(replace(v, "/[^a-zA-Z0-9_-]/", "-")) }
  tier = {
    small  = "db-custom-1-3840"
    medium = "db-custom-2-7680"
    large  = "db-custom-4-16384"
  }
}

resource "random_password" "admin" {
  length  = 40
  special = false
}

resource "google_sql_database_instance" "this" {
  project             = local.project
  name                = local.name
  region              = var.context.region
  database_version    = "POSTGRES_${var.engine_version}"
  deletion_protection = var.context.deletion_protection

  settings {
    edition                     = "ENTERPRISE"
    tier                        = local.tier[var.size]
    availability_type           = var.high_availability ? "REGIONAL" : "ZONAL"
    disk_type                   = "PD_SSD"
    disk_size                   = var.storage_gb
    disk_autoresize             = true
    deletion_protection_enabled = var.context.deletion_protection
    user_labels                 = local.labels

    ip_configuration {
      ipv4_enabled                                  = false
      private_network                               = var.network.id
      ssl_mode                                      = "ENCRYPTED_ONLY"
      enable_private_path_for_google_cloud_services = true
    }

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "07:00"
      transaction_log_retention_days = min(var.backup_retention_days, 7)
      backup_retention_settings {
        retained_backups = var.backup_retention_days
      }
    }

    insights_config {
      query_insights_enabled = true
    }

    database_flags {
      name  = "cloudsql.iam_authentication"
      value = "on"
    }
    database_flags {
      name  = "log_min_duration_statement"
      value = "500"
    }

    maintenance_window {
      day  = 7
      hour = 7
    }
  }

  depends_on = [var.network]
}

resource "google_sql_user" "admin" {
  project  = local.project
  instance = google_sql_database_instance.this.name
  name     = "at_admin"
  password = random_password.admin.result
}

resource "google_sql_database" "this" {
  for_each = toset(var.databases)
  project  = local.project
  instance = google_sql_database_instance.this.name
  name     = each.value
}

resource "google_secret_manager_secret" "admin" {
  project   = local.project
  secret_id = "${var.secret_store.prefix}-postgres-admin"
  labels    = local.labels
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "admin" {
  secret = google_secret_manager_secret.admin.id
  secret_data = jsonencode({
    username = google_sql_user.admin.name
    password = random_password.admin.result
    host     = google_sql_database_instance.this.private_ip_address
    port     = 5432
  })
}
