# No public buckets on any cloud.
package main

import rego.v1

# ---------------------------------------------------------------- AWS
deny contains msg if {
	some rc in changes
	rc.type == "aws_s3_bucket"
	not sibling_exists(rc, "aws_s3_bucket_public_access_block")
	msg := sprintf("%s: S3 bucket has no aws_s3_bucket_public_access_block", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "aws_s3_bucket_public_access_block"
	some flag in ["block_public_acls", "block_public_policy", "ignore_public_acls", "restrict_public_buckets"]
	object.get(after(rc), flag, false) != true
	msg := sprintf("%s: %s must be true", [rc.address, flag])
}

deny contains msg if {
	some rc in changes
	rc.type == "aws_s3_bucket_acl"
	object.get(after(rc), "acl", "private") in {"public-read", "public-read-write", "authenticated-read"}
	msg := sprintf("%s: public S3 ACL %q", [rc.address, after(rc).acl])
}

# ---------------------------------------------------------------- GCP
deny contains msg if {
	some rc in changes
	rc.type == "google_storage_bucket"
	object.get(after(rc), "public_access_prevention", "inherited") != "enforced"
	msg := sprintf("%s: public_access_prevention must be \"enforced\"", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "google_storage_bucket"
	object.get(after(rc), "uniform_bucket_level_access", false) != true
	msg := sprintf("%s: uniform_bucket_level_access must be true", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type in {"google_storage_bucket_iam_member", "google_storage_bucket_iam_binding", "google_storage_bucket_access_control"}
	members := array.concat([object.get(after(rc), "member", "")], object.get(after(rc), "members", []))
	some m in members
	m in {"allUsers", "allAuthenticatedUsers"}
	msg := sprintf("%s: grants bucket access to %s", [rc.address, m])
}

# ---------------------------------------------------------------- Azure
deny contains msg if {
	some rc in changes
	rc.type == "azurerm_storage_account"
	object.get(after(rc), "allow_nested_items_to_be_public", true) != false
	msg := sprintf("%s: allow_nested_items_to_be_public must be false", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "azurerm_storage_container"
	object.get(after(rc), "container_access_type", "private") != "private"
	msg := sprintf("%s: container_access_type must be private", [rc.address])
}
