# One-time, per Azure subscription: resource group + storage account + container for state.
# Entra ID auth only (shared keys off), TLS 1.2, versioning + soft delete; the azurerm backend locks via
# blob leases.
#   cd infra/terraform/bootstrap/azure && tofu init && tofu apply -var subscription_id=<id> -var location=eastus
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
    random  = { source = "hashicorp/random", version = "~> 3.6" }
  }
}

variable "subscription_id" {
  type = string
}

variable "allowed_ips" {
  description = "Public IPs/CIDRs (CI runners, VPN) allowed to reach the state account; everything else is denied."
  type        = list(string)
  default     = []
}

variable "location" {
  type    = string
  default = "eastus"
}

provider "azurerm" {
  features {}
  subscription_id     = var.subscription_id
  storage_use_azuread = true
}

locals {
  tags = { env = "shared", owner = "platform", "cost-center" = "engineering", service = "terraform-state" }
}

resource "random_id" "suffix" {
  byte_length = 3
}

resource "azurerm_resource_group" "state" {
  name     = "afrotransact-tfstate"
  location = var.location
  tags     = local.tags
}

resource "azurerm_storage_account" "state" {
  name                              = "attfstate${random_id.suffix.hex}"
  resource_group_name               = azurerm_resource_group.state.name
  location                          = azurerm_resource_group.state.location
  account_tier                      = "Standard"
  account_replication_type          = "ZRS"
  min_tls_version                   = "TLS1_2"
  https_traffic_only_enabled        = true
  shared_access_key_enabled         = false
  allow_nested_items_to_be_public   = false
  infrastructure_encryption_enabled = true
  public_network_access_enabled     = true
  tags                              = local.tags
  network_rules {
    default_action = "Deny"
    bypass         = ["AzureServices", "Logging", "Metrics"]
    ip_rules       = var.allowed_ips
  }
  blob_properties {
    versioning_enabled = true
    delete_retention_policy {
      days = 30
    }
  }
  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_storage_account_queue_properties" "state" {
  storage_account_id = azurerm_storage_account.state.id
  logging {
    delete                = true
    read                  = true
    write                 = true
    version               = "1.0"
    retention_policy_days = 30
  }
}

resource "azurerm_storage_container" "state" {
  name                  = "tfstate"
  storage_account_id    = azurerm_storage_account.state.id
  container_access_type = "private"
}

output "storage_account_name" {
  value = azurerm_storage_account.state.name
}
