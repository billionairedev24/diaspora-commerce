mock_provider "ovh" {
  mock_resource "ovh_cloud_project_database" {
    defaults = {
      endpoints = [{ component = "postgresql", domain = "db.example", port = 20184, scheme = "postgresql", ssl = true, ssl_mode = "require", path = "", uri = "" }]
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
    gateway              = true
    egress_ips           = ["203.0.113.10"]
  }
  databases = ["catalog", "orders"]
}

run "single_node_by_default" {
  command = plan
  assert {
    condition     = ovh_cloud_project_database.this.plan == "discovery" && length(ovh_cloud_project_database.this.nodes) == 1 && ovh_cloud_project_database.this.flavor == "db1-4"
    error_message = "one db1-4 node unless high availability is asked for"
  }
  assert {
    condition     = length(ovh_cloud_project_database_database.this) == 2
    error_message = "one database per service on ONE server"
  }
}

run "ha_adds_exactly_one_standby" {
  command = plan
  variables {
    high_availability = true
    size              = "large"
  }
  assert {
    condition     = ovh_cloud_project_database.this.plan == "production" && length(ovh_cloud_project_database.this.nodes) == 2 && ovh_cloud_project_database.this.flavor == "db1-15"
    error_message = "production plan = two nodes"
  }
}
