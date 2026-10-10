# Helpers shared by every policy. Input: `tofu show -json plan.out` (Terraform/OpenTofu plan JSON).
package main

import rego.v1

# Resources being created or updated (deletes and reads are not checked).
changes contains rc if {
	some rc in input.resource_changes
	rc.mode == "managed"
	some action in rc.change.actions
	action in {"create", "update"}
}

after(rc) := rc.change.after

after_unknown(rc) := object.get(rc.change, "after_unknown", {})

# Same module + same for_each/count index, e.g. the public access block of an S3 bucket.
sibling_exists(rc, type) if {
	some other in input.resource_changes
	other.type == type
	object.get(other, "module_address", "") == object.get(rc, "module_address", "")
	object.get(other, "index", null) == object.get(rc, "index", null)
}

is_world(cidr) if cidr in {"0.0.0.0/0", "::/0", "*", "Internet", "Any"}

# Escape hatch for load balancers only: tag/label purpose = load-balancer.
is_load_balancer(rc) if object.get(object.get(after(rc), "tags", {}), "purpose", "") == "load-balancer"

is_load_balancer(rc) if object.get(object.get(after(rc), "tags_all", {}), "purpose", "") == "load-balancer"

is_load_balancer(rc) if "load-balancer" in object.get(after(rc), "target_tags", [])

is_load_balancer(rc) if startswith(object.get(after(rc), "name", ""), "lb-")
