# Azure cluster: AKS with
#   - "system" default pool: Cobalt 100 arm64 (Dpsv6), Azure Linux, zone-spread, critical add-ons only in prod
#   - "general" user pool: Cobalt arm64 on-demand
#   - "spot" user pool: Cobalt arm64 Spot (AKS also adds kubernetes.azure.com/scalesetpriority=spot:NoSchedule),
#     tainted afrotransact.com/spot=true:NoSchedule, scales to zero
#   - Azure CNI Overlay + Cilium, OIDC issuer + workload identity, Entra ID RBAC (local accounts disabled),
#     image cleaner, VPA/KEDA, automatic patch upgrades. Node autoprovisioning (Karpenter-based NAP) can be
#     switched on with node_provisioning_profile.mode = "Auto" once the pools are retired.
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
  }
}

data "azurerm_client_config" "this" {}

locals {
  rg   = var.context.scope
  name = var.context.name
  tags = merge(var.context.tags, { service = "platform", module = "cluster" })
  vm_size = {
    small  = "Standard_D2ps_v6"
    medium = "Standard_D4ps_v6"
    large  = "Standard_D8ps_v6"
  }
  zones = [for z in range(1, var.network.zone_count + 1) : tostring(z)]
}

resource "azurerm_user_assigned_identity" "control_plane" {
  name                = "${local.name}-aks"
  location            = var.context.region
  resource_group_name = local.rg
  tags                = local.tags
}

# Control plane manages NICs/LBs in the VNet.
resource "azurerm_role_assignment" "network" {
  scope                = var.network.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.control_plane.principal_id
}

resource "azurerm_kubernetes_cluster" "this" {
  name                      = local.name
  location                  = var.context.region
  resource_group_name       = local.rg
  dns_prefix                = local.name
  kubernetes_version        = var.kubernetes_version
  sku_tier                  = var.context.environment == "prod" ? "Standard" : "Free"
  automatic_upgrade_channel = "patch"
  node_os_upgrade_channel   = "NodeImage"
  oidc_issuer_enabled       = true
  workload_identity_enabled = true
  local_account_disabled    = true
  private_cluster_enabled   = var.private_endpoint
  image_cleaner_enabled     = true
  cost_analysis_enabled     = var.context.environment == "prod"
  tags                      = local.tags

  default_node_pool {
    name                         = "system"
    vm_size                      = local.vm_size["small"]
    os_sku                       = "AzureLinux"
    vnet_subnet_id               = var.network.cluster_subnet_id
    zones                        = local.zones
    auto_scaling_enabled         = true
    min_count                    = 1
    max_count                    = 3
    only_critical_addons_enabled = var.context.environment == "prod"
    temporary_name_for_rotation  = "systemtmp"
    node_labels                  = { "afrotransact.com/pool" = "system" }
    upgrade_settings {
      max_surge = "33%"
    }
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.control_plane.id]
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_data_plane  = "cilium"
    network_policy      = "cilium"
    load_balancer_sku   = "standard"
    outbound_type       = var.network.outbound_type == "none" ? "loadBalancer" : var.network.outbound_type
    pod_cidr            = "100.64.0.0/16"
    service_cidr        = "172.16.0.0/16"
    dns_service_ip      = "172.16.0.10"
  }

  dynamic "api_server_access_profile" {
    for_each = !var.private_endpoint && length(var.context.admin_cidrs) > 0 ? [1] : []
    content {
      authorized_ip_ranges = var.context.admin_cidrs
    }
  }

  azure_active_directory_role_based_access_control {
    tenant_id              = data.azurerm_client_config.this.tenant_id
    azure_rbac_enabled     = true
    admin_group_object_ids = var.admin_principals
  }

  node_provisioning_profile {
    mode = "Manual"
  }

  workload_autoscaler_profile {
    vertical_pod_autoscaler_enabled = true
    keda_enabled                    = true
  }

  maintenance_window_auto_upgrade {
    frequency   = "Weekly"
    interval    = 1
    duration    = 4
    day_of_week = "Sunday"
    start_time  = "06:00"
    utc_offset  = "+00:00"
  }

  lifecycle {
    ignore_changes = [default_node_pool[0].node_count, kubernetes_version]
  }

  depends_on = [azurerm_role_assignment.network]
}

resource "azurerm_kubernetes_cluster_node_pool" "general" {
  name                  = "general"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.this.id
  mode                  = "User"
  vm_size               = local.vm_size[var.node_size]
  os_sku                = "AzureLinux"
  vnet_subnet_id        = var.network.cluster_subnet_id
  zones                 = local.zones
  auto_scaling_enabled  = true
  min_count             = var.min_nodes
  max_count             = var.max_nodes
  node_labels           = { "afrotransact.com/pool" = "general" }
  tags                  = local.tags
  upgrade_settings {
    max_surge = "33%"
  }
  lifecycle {
    ignore_changes = [node_count]
  }
}

resource "azurerm_kubernetes_cluster_node_pool" "spot" {
  count                 = var.spot_enabled ? 1 : 0
  name                  = "spot"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.this.id
  mode                  = "User"
  priority              = "Spot"
  eviction_policy       = "Delete"
  spot_max_price        = -1
  vm_size               = local.vm_size[var.node_size]
  os_sku                = "AzureLinux"
  vnet_subnet_id        = var.network.cluster_subnet_id
  zones                 = local.zones
  auto_scaling_enabled  = true
  min_count             = 0
  max_count             = var.spot_max_nodes
  node_labels = {
    "afrotransact.com/pool"                 = "spot"
    "kubernetes.azure.com/scalesetpriority" = "spot"
  }
  node_taints = [
    "afrotransact.com/spot=true:NoSchedule",
    "kubernetes.azure.com/scalesetpriority=spot:NoSchedule",
  ]
  tags = local.tags
  lifecycle {
    ignore_changes = [node_count]
  }
}
