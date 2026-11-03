mock_provider "ovh" {
  mock_resource "ovh_cloud_project_database" {
    defaults = {
      endpoints = [{ component = "valkey", domain = "cache.example", port = 20185, scheme = "rediss", ssl = true, ssl_mode = "require", path = "", uri = "" }]
    }
  }
}

variables {
  context = {
    name        = "afrotransact-uat"
    environment = "uat"
    region      = "GRA11"
    scope       = "project123"
    tags        = { env = "uat", owner = "platform", "cost-center" = "engineering" }
  }
  network = {
    cloud                = "ovh"
    id                   = "net"
    openstack_network_id = "os-net"
    subnet_id            = "sub"
    region               = "GRA11"
    cidr_block           = "10.20.0.0/16"
    zone_count           = 1
    gateway              = false
    egress_ips           = []
  }
}

run "single_valkey_node_by_default" {
  command = plan
  assert {
    condition     = ovh_cloud_project_database.this.plan == "discovery" && length(ovh_cloud_project_database.this.nodes) == 1
    error_message = "one node unless high availability is asked for"
  }
}
