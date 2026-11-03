# Azure network: VNet with an AKS node subnet (pods use Azure CNI Overlay, so no pod IPs from the VNet),
# a delegated subnet for PostgreSQL Flexible Server, a private-endpoint subnet (Redis, Blob, Key Vault, ACR),
# NSGs, private DNS zones linked to the VNet, optional NAT gateway for third-party egress.
# The resource group (context.scope) is created by the environment root.
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
  }
}

locals {
  rg   = var.context.scope
  tags = merge(var.context.tags, { service = "platform", module = "network" })
  private_dns_zones = {
    postgres = "${var.context.name}.private.postgres.database.azure.com"
    redis    = "privatelink.redis.cache.windows.net"
    blob     = "privatelink.blob.core.windows.net"
    vault    = "privatelink.vaultcore.azure.net"
    acr      = "privatelink.azurecr.io"
  }
}

resource "azurerm_virtual_network" "this" {
  name                = var.context.name
  location            = var.context.region
  resource_group_name = local.rg
  address_space       = [var.cidr_block]
  tags                = local.tags
}

resource "azurerm_subnet" "cluster" {
  name                 = "cluster"
  resource_group_name  = local.rg
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [cidrsubnet(var.cidr_block, 2, 0)] # /18 nodes
}

resource "azurerm_subnet" "postgres" {
  name                 = "postgres"
  resource_group_name  = local.rg
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [cidrsubnet(var.cidr_block, 8, 128)]
  delegation {
    name = "postgres"
    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

resource "azurerm_subnet" "endpoints" {
  name                              = "private-endpoints"
  resource_group_name               = local.rg
  virtual_network_name              = azurerm_virtual_network.this.name
  address_prefixes                  = [cidrsubnet(var.cidr_block, 8, 129)]
  private_endpoint_network_policies = "Enabled"
}

resource "azurerm_network_security_group" "data" {
  name                = "${var.context.name}-data"
  location            = var.context.region
  resource_group_name = local.rg
  tags                = local.tags

  security_rule {
    name                       = "allow-vnet"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_ranges    = ["443", "5432", "6380", "10000"]
    source_address_prefix      = "VirtualNetwork"
    destination_address_prefix = "VirtualNetwork"
  }
  security_rule {
    name                       = "deny-all-inbound"
    priority                   = 4000
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "*"
    source_port_range          = "*"
    destination_port_range     = "*"
    source_address_prefix      = "*"
    destination_address_prefix = "*"
  }
}

resource "azurerm_subnet_network_security_group_association" "postgres" {
  subnet_id                 = azurerm_subnet.postgres.id
  network_security_group_id = azurerm_network_security_group.data.id
}

resource "azurerm_subnet_network_security_group_association" "endpoints" {
  subnet_id                 = azurerm_subnet.endpoints.id
  network_security_group_id = azurerm_network_security_group.data.id
}

resource "azurerm_private_dns_zone" "this" {
  for_each            = local.private_dns_zones
  name                = each.value
  resource_group_name = local.rg
  tags                = local.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "this" {
  for_each             = local.private_dns_zones
  name                 = "${var.context.name}-${each.key}"
  private_dns_zone_id  = azurerm_private_dns_zone.this[each.key].id
  virtual_network_id   = azurerm_virtual_network.this.id
  registration_enabled = false
  tags                 = local.tags
}

# NAT gateway is zonal in Azure; "per-zone" uses a zone-redundant Standard public IP prefix instead.
resource "azurerm_public_ip" "nat" {
  count               = var.egress == "none" ? 0 : 1
  name                = "${var.context.name}-nat"
  location            = var.context.region
  resource_group_name = local.rg
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = var.egress == "per-zone" ? [for z in range(1, var.zone_count + 1) : tostring(z)] : null
  tags                = local.tags
}

resource "azurerm_nat_gateway" "this" {
  count                   = var.egress == "none" ? 0 : 1
  name                    = var.context.name
  location                = var.context.region
  resource_group_name     = local.rg
  sku_name                = "Standard"
  idle_timeout_in_minutes = 10
  tags                    = local.tags
}

resource "azurerm_nat_gateway_public_ip_association" "this" {
  count                = var.egress == "none" ? 0 : 1
  nat_gateway_id       = azurerm_nat_gateway.this[0].id
  public_ip_address_id = azurerm_public_ip.nat[0].id
}

resource "azurerm_subnet_nat_gateway_association" "cluster" {
  count          = var.egress == "none" ? 0 : 1
  subnet_id      = azurerm_subnet.cluster.id
  nat_gateway_id = azurerm_nat_gateway.this[0].id
}
