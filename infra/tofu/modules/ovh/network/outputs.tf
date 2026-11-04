output "network_id" {
  value = ovh_cloud_project_network_private.this.id
}

output "cluster_subnet_ids" {
  value = [ovh_cloud_project_network_private_subnet.this.id]
}

output "data_subnet_ids" {
  description = "Managed databases share the cluster subnet (one private network per environment)."
  value       = [ovh_cloud_project_network_private_subnet.this.id]
}

output "network" {
  description = "Opaque handle consumed by the cluster, postgres, cache and bucket modules of the same cloud."
  value = {
    cloud                = "ovh"
    id                   = ovh_cloud_project_network_private.this.id
    openstack_network_id = tolist(ovh_cloud_project_network_private.this.regions_attributes[*].openstackid)[0]
    subnet_id            = ovh_cloud_project_network_private_subnet.this.id
    region               = var.context.region
    cidr_block           = var.cidr_block
    zone_count           = var.zone_count
    # false = no gateway: workloads and databases are reached over public IPs (egress none, lean profile).
    gateway = local.gateway
    egress_ips = flatten([
      for g in ovh_cloud_project_gateway.this : [
        for e in g.external_information : [for i in e.ips : i.ip]
      ]
    ])
  }
}
