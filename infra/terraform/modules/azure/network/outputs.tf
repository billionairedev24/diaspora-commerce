output "network_id" {
  value = azurerm_virtual_network.this.id
}

output "cluster_subnet_ids" {
  value = [azurerm_subnet.cluster.id]
}

output "data_subnet_ids" {
  value = [azurerm_subnet.postgres.id, azurerm_subnet.endpoints.id]
}

output "network" {
  description = "Opaque handle consumed by the cluster, postgres, cache and bucket modules of the same cloud."
  value = {
    cloud               = "azure"
    id                  = azurerm_virtual_network.this.id
    resource_group_name = local.rg
    cidr_block          = var.cidr_block
    zone_count          = var.zone_count
    cluster_subnet_id   = azurerm_subnet.cluster.id
    postgres_subnet_id  = azurerm_subnet.postgres.id
    endpoint_subnet_id  = azurerm_subnet.endpoints.id
    private_dns_zone_ids = {
      for k, z in azurerm_private_dns_zone.this : k => z.id
    }
    outbound_type = var.egress == "none" ? "none" : "userAssignedNATGateway"
    egress_ips    = azurerm_public_ip.nat[*].ip_address
    dns_link_ids  = [for l in azurerm_private_dns_zone_virtual_network_link.this : l.id]
  }
}
