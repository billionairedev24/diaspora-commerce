output "ipv4" {
  description = "The server's public IPv4: point api./auth./mcp.<domain> at it."
  value       = one([for a in ovh_cloud_project_instance.box.addresses : a.ip if a.version == 4])
}

output "location" {
  description = "Where it runs."
  value       = { name = var.ovh_location, label = local.loc.label, region = local.loc.region, flavor = var.instance_flavor }
}
