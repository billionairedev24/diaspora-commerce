# No 0.0.0.0/0 (or ::/0, *, Internet) ingress except on load balancers
# (tag/label purpose = load-balancer, GCP target tag "load-balancer", or an "lb-" name prefix).
package main

import rego.v1

# ---------------------------------------------------------------- AWS
deny contains msg if {
	some rc in changes
	rc.type == "aws_security_group"
	not is_load_balancer(rc)
	some rule in object.get(after(rc), "ingress", [])
	some cidr in array.concat(object.get(rule, "cidr_blocks", []), object.get(rule, "ipv6_cidr_blocks", []))
	is_world(cidr)
	msg := sprintf("%s: security group allows ingress from %s (only load balancers may)", [rc.address, cidr])
}

deny contains msg if {
	some rc in changes
	rc.type == "aws_vpc_security_group_ingress_rule"
	not is_load_balancer(rc)
	some attr in ["cidr_ipv4", "cidr_ipv6"]
	is_world(object.get(after(rc), attr, ""))
	msg := sprintf("%s: ingress rule open to the world (only load balancers may)", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "aws_security_group_rule"
	after(rc).type == "ingress"
	not is_load_balancer(rc)
	some cidr in array.concat(object.get(after(rc), "cidr_blocks", []), object.get(after(rc), "ipv6_cidr_blocks", []))
	is_world(cidr)
	msg := sprintf("%s: ingress rule open to %s (only load balancers may)", [rc.address, cidr])
}

# ---------------------------------------------------------------- GCP
deny contains msg if {
	some rc in changes
	rc.type == "google_compute_firewall"
	object.get(after(rc), "direction", "INGRESS") == "INGRESS"
	count(object.get(after(rc), "allow", [])) > 0
	not is_load_balancer(rc)
	some cidr in object.get(after(rc), "source_ranges", [])
	is_world(cidr)
	msg := sprintf("%s: firewall allows ingress from %s (only load balancers may)", [rc.address, cidr])
}

# ---------------------------------------------------------------- Azure
nsg_rule_open(rule) if {
	lower(object.get(rule, "direction", "")) == "inbound"
	lower(object.get(rule, "access", "")) == "allow"
	prefixes := array.concat([object.get(rule, "source_address_prefix", "")], object.get(rule, "source_address_prefixes", []))
	some p in prefixes
	is_world(p)
}

deny contains msg if {
	some rc in changes
	rc.type == "azurerm_network_security_group"
	not is_load_balancer(rc)
	some rule in object.get(after(rc), "security_rule", [])
	nsg_rule_open(rule)
	not startswith(object.get(rule, "name", ""), "lb-")
	msg := sprintf("%s: NSG rule %q allows inbound from the internet (only load balancers may)", [rc.address, rule.name])
}

deny contains msg if {
	some rc in changes
	rc.type == "azurerm_network_security_rule"
	not is_load_balancer(rc)
	nsg_rule_open(after(rc))
	msg := sprintf("%s: NSG rule allows inbound from the internet (only load balancers may)", [rc.address])
}

# ---------------------------------------------------------------- management endpoints (warning only)
warn contains msg if {
	some rc in changes
	rc.type == "aws_eks_cluster"
	some vpc in object.get(after(rc), "vpc_config", [])
	vpc.endpoint_public_access == true
	some cidr in object.get(vpc, "public_access_cidrs", [])
	is_world(cidr)
	msg := sprintf("%s: Kubernetes API is reachable from 0.0.0.0/0 - set admin_cidrs or private_endpoint", [rc.address])
}
