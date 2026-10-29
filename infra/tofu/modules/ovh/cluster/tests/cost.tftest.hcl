# Cost guards for the OVH cluster module (plan only, mocked provider): `tofu test` in this directory.
mock_provider "ovh" {
  mock_resource "ovh_cloud_project_kube" {
    defaults = {
      kubeconfig_attributes = [{ host = "https://k8s.example", cluster_ca_certificate = "ca", client_certificate = "cert", client_key = "key" }]
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

run "lean_is_exactly_one_node_on_the_free_plan" {
  command = plan
  variables {
    node_size      = "lean"
    min_nodes      = 3
    max_nodes      = 9
    spot_enabled   = true
    spot_max_nodes = 6
  }
  assert {
    condition     = ovh_cloud_project_kube.this.plan == "free"
    error_message = "single-zone clusters must use the free control plane"
  }
  assert {
    condition     = ovh_cloud_project_kube_nodepool.general.flavor_name == "b3-16" && ovh_cloud_project_kube_nodepool.general.desired_nodes == 1 && ovh_cloud_project_kube_nodepool.general.max_nodes == 1
    error_message = "lean = one b3-16 node, no autoscaling headroom"
  }
  assert {
    condition     = ovh_cloud_project_kube_nodepool.general.autoscale == false && length(ovh_cloud_project_kube_nodepool.spot) == 0
    error_message = "lean has no autoscaler and no spot pool"
  }
  assert {
    condition     = ovh_cloud_project_kube.this.private_network_id == null
    error_message = "no gateway = no private network attachment (public node IPs, nothing to pay for)"
  }
}

run "prod_stable_pool_is_billed_monthly" {
  command = plan
  variables {
    node_size = "medium"
    min_nodes = 2
    max_nodes = 4
  }
  assert {
    condition     = ovh_cloud_project_kube_nodepool.general.monthly_billed == true
    error_message = "always-on prod nodes must use monthly billing"
  }
}

run "trial_environments_stay_hourly" {
  command = plan
  variables {
    context = {
      name                = "afrotransact-dev"
      environment         = "dev"
      region              = "GRA11"
      scope               = "project123"
      tags                = { env = "dev", owner = "platform", "cost-center" = "engineering" }
      deletion_protection = false
    }
    node_size      = "small"
    spot_enabled   = true
    spot_max_nodes = 4
  }
  assert {
    condition     = ovh_cloud_project_kube_nodepool.general.monthly_billed == false
    error_message = "disposable environments must be able to scale and be destroyed without a monthly commitment"
  }
  assert {
    condition     = length(ovh_cloud_project_kube_nodepool.spot) == 1 && ovh_cloud_project_kube_nodepool.spot[0].min_nodes == 0 && ovh_cloud_project_kube_nodepool.spot[0].monthly_billed == false
    error_message = "the spot pool scales from zero and is hourly"
  }
}
