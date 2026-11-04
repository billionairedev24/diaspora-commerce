# conftest verify --policy infra/policy/tofu
package main

import rego.v1

rc(type, after) := {
	"address": sprintf("%s.this", [type]),
	"mode": "managed",
	"type": type,
	"name": "this",
	"provider_name": "registry.opentofu.org/hashicorp/x",
	"change": {"actions": ["create"], "after": after, "after_unknown": {}},
}

good_tags := {"env": "dev", "owner": "platform", "cost-center": "engineering"}

plan(resources) := {"resource_changes": resources}

# ------------------------------------------------------------------ public buckets
test_s3_without_public_access_block_denied if {
	some msg in deny with input as plan([rc("aws_s3_bucket", {"tags_all": good_tags})])
	contains(msg, "public_access_block")
}

test_s3_with_block_and_sse_allowed if {
	count(deny) == 0 with input as plan([
		rc("aws_s3_bucket", {"tags_all": good_tags}),
		rc("aws_s3_bucket_public_access_block", {
			"block_public_acls": true, "block_public_policy": true,
			"ignore_public_acls": true, "restrict_public_buckets": true,
		}),
		rc("aws_s3_bucket_server_side_encryption_configuration", {}),
	])
}

test_public_access_block_flag_false_denied if {
	some msg in deny with input as plan([rc("aws_s3_bucket_public_access_block", {
		"block_public_acls": true, "block_public_policy": false,
		"ignore_public_acls": true, "restrict_public_buckets": true,
	})])
	contains(msg, "block_public_policy")
}

test_gcs_without_enforced_prevention_denied if {
	some msg in deny with input as plan([rc("google_storage_bucket", {
		"public_access_prevention": "inherited", "uniform_bucket_level_access": true,
		"terraform_labels": good_tags,
	})])
	contains(msg, "public_access_prevention")
}

test_gcs_all_users_denied if {
	some msg in deny with input as plan([rc("google_storage_bucket_iam_member", {"member": "allUsers"})])
	contains(msg, "allUsers")
}

test_azure_public_container_denied if {
	some msg in deny with input as plan([rc("azurerm_storage_container", {"container_access_type": "blob"})])
	contains(msg, "private")
}

# ------------------------------------------------------------------ encryption
test_unencrypted_aurora_denied if {
	some msg in deny with input as plan([rc("aws_rds_cluster", {"storage_encrypted": false, "tags_all": good_tags})])
	contains(msg, "storage_encrypted")
}

test_encrypted_aurora_allowed if {
	count(deny) == 0 with input as plan([rc("aws_rds_cluster", {"storage_encrypted": true, "tags_all": good_tags})])
}

test_elasticache_without_transit_encryption_denied if {
	some msg in deny with input as plan([rc("aws_elasticache_replication_group", {
		"at_rest_encryption_enabled": "true", "transit_encryption_enabled": false, "tags_all": good_tags,
	})])
	contains(msg, "transit_encryption_enabled")
}

test_cloudsql_without_tls_denied if {
	some msg in deny with input as plan([rc("google_sql_database_instance", {
		"settings": [{"ip_configuration": [{"ssl_mode": "ALLOW_UNENCRYPTED_AND_ENCRYPTED"}]}],
	})])
	contains(msg, "ssl_mode")
}

test_azure_redis_non_ssl_denied if {
	some msg in deny with input as plan([rc("azurerm_redis_cache", {"non_ssl_port_enabled": true, "tags": good_tags})])
	contains(msg, "non-TLS")
}

# ------------------------------------------------------------------ tags
test_missing_cost_center_denied if {
	some msg in deny with input as plan([rc("aws_vpc", {"tags_all": {"env": "dev", "owner": "platform"}})])
	contains(msg, "cost-center")
}

test_gcp_labels_checked if {
	some msg in deny with input as plan([rc("google_storage_bucket", {
		"public_access_prevention": "enforced", "uniform_bucket_level_access": true,
		"terraform_labels": {"env": "dev"},
	})])
	contains(msg, "missing required tags")
}

test_unknown_tags_skipped if {
	r := object.union(rc("aws_vpc", {"tags_all": null}), {"change": {"actions": ["create"], "after": {"tags_all": null}, "after_unknown": {"tags_all": true}}})
	count(deny) == 0 with input as plan([r])
}

test_cloudflare_tags_ignored if {
	r := object.union(rc("cloudflare_dns_record", {"tags": ["env:dev"]}), {"provider_name": "registry.opentofu.org/cloudflare/cloudflare"})
	count(deny) == 0 with input as plan([r])
}

# ------------------------------------------------------------------ network
test_world_ingress_rule_denied if {
	some msg in deny with input as plan([rc("aws_vpc_security_group_ingress_rule", {"cidr_ipv4": "0.0.0.0/0", "tags_all": good_tags})])
	contains(msg, "open to the world")
}

test_world_ingress_on_load_balancer_allowed if {
	count(deny) == 0 with input as plan([rc("aws_vpc_security_group_ingress_rule", {
		"cidr_ipv4": "0.0.0.0/0",
		"tags_all": object.union(good_tags, {"purpose": "load-balancer"}),
	})])
}

test_vpc_ingress_allowed if {
	count(deny) == 0 with input as plan([rc("aws_vpc_security_group_ingress_rule", {"cidr_ipv4": "10.20.0.0/16", "tags_all": good_tags})])
}

test_gcp_firewall_world_denied if {
	some msg in deny with input as plan([rc("google_compute_firewall", {
		"direction": "INGRESS", "allow": [{"protocol": "tcp"}], "source_ranges": ["0.0.0.0/0"], "target_tags": [],
	})])
	contains(msg, "firewall")
}

test_azure_nsg_internet_denied if {
	some msg in deny with input as plan([rc("azurerm_network_security_group", {
		"tags": good_tags,
		"security_rule": [{"name": "ssh", "direction": "Inbound", "access": "Allow", "source_address_prefix": "Internet", "source_address_prefixes": []}],
	})])
	contains(msg, "NSG rule")
}

test_azure_nsg_deny_all_allowed if {
	count(deny) == 0 with input as plan([rc("azurerm_network_security_group", {
		"tags": good_tags,
		"security_rule": [{"name": "deny-all", "direction": "Inbound", "access": "Deny", "source_address_prefix": "*", "source_address_prefixes": []}],
	})])
}

test_deletes_ignored if {
	r := object.union(rc("aws_rds_cluster", {"storage_encrypted": false}), {"change": {"actions": ["delete"], "after": null, "after_unknown": {}}})
	count(deny) == 0 with input as plan([r])
}

test_ovh_storage_without_encryption_denied if {
	some msg in deny with input as plan([rc("ovh_cloud_project_storage", {"tags": good_tags})])
	contains(msg, "sse_algorithm")
}

test_ovh_storage_with_encryption_allowed if {
	count(deny) == 0 with input as plan([rc("ovh_cloud_project_storage", {"tags": good_tags, "encryption": {"sse_algorithm": "AES256"}})])
}
