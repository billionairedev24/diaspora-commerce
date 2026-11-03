# One Public Cloud instance for the single-server test environment (infra/ovh-vps/README.md, docs/runbooks/ovh-vps.md).
# Nothing else: no network, no database, no buckets. The instance is billed by the hour from the moment it exists until
# `destroy` deletes it. k3s and the services are installed over SSH by the pipeline (bootstrap.sh, deploy.sh).
locals {
  loc = jsondecode(file("${path.module}/locations.json"))[var.ovh_location]
}

provider "ovh" {
  endpoint = local.loc.api_endpoint
}

data "ovh_cloud_project_flavors" "box" {
  service_name = var.service_name
  region       = local.loc.region
  name_filter  = var.instance_flavor
}

data "ovh_cloud_project_images" "box" {
  service_name = var.service_name
  region       = local.loc.region
  os_type      = "linux"
}

locals {
  # By name in the region (the Linux flavor, not its Windows twin).
  flavor_id = try([for f in data.ovh_cloud_project_flavors.box.flavors : f.id if f.name == var.instance_flavor && f.os_type == "linux"][0], null)
  image_id  = try([for i in data.ovh_cloud_project_images.box.images : i.id if i.name == var.instance_image][0], null)
}

resource "ovh_cloud_project_instance" "box" {
  service_name   = var.service_name
  region         = local.loc.region
  name           = "afrotransact-test"
  billing_period = "hourly"

  flavor {
    flavor_id = local.flavor_id
  }
  boot_from {
    image_id = local.image_id
  }
  ssh_key_create {
    name       = "afrotransact-test"
    public_key = var.ssh_public_key
  }
  network {
    public = true
  }

  lifecycle {
    # A newer Ubuntu 24.04 build gets a new image id: never rebuild the server (and its data) for that.
    ignore_changes = [boot_from]

    precondition {
      condition     = local.flavor_id != null
      error_message = "The flavor ${var.instance_flavor} is not offered in ${local.loc.region} (Public Cloud > Instances > Create shows the flavors)."
    }
    precondition {
      condition     = local.image_id != null
      error_message = "No image named ${var.instance_image} in ${local.loc.region}."
    }
  }
}
