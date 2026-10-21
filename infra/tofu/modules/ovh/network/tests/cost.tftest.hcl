mock_provider "ovh" {
  mock_resource "ovh_cloud_project_network_private" {
    defaults = {
      regions_attributes = [{ openstackid = "os-net-1", region = "GRA11", status = "ACTIVE" }]
    }
  }
}

variables {
  context = {
    name        = "afrotransact-prod"
    environment = "prod"
    region      = "GRA11"
    scope       = "project123"
    tags        = { env = "prod", owner = "platform", "cost-center" = "engineering" }
  }
  cidr_block = "10.20.0.0/16"
}

run "no_egress_means_no_gateway" {
  command = plan
  variables {
    egress = "none"
  }
  assert {
    condition     = length(ovh_cloud_project_gateway.this) == 0 && ovh_cloud_project_network_private_subnet.this.no_gateway == true
    error_message = "egress none must not create a billed gateway"
  }
}

run "per_zone_is_still_one_gateway" {
  command = plan
  variables {
    egress = "per-zone"
  }
  assert {
    condition     = length(ovh_cloud_project_gateway.this) == 1 && ovh_cloud_project_gateway.this[0].model == "s"
    error_message = "OVH gateways are regional: exactly one, smallest model"
  }
}
