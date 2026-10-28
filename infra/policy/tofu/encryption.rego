# Encryption at rest (and TLS in transit for data services) everywhere.
package main

import rego.v1

# ---------------------------------------------------------------- AWS
deny contains msg if {
	some rc in changes
	rc.type in {"aws_rds_cluster", "aws_db_instance"}
	object.get(after(rc), "storage_encrypted", false) != true
	msg := sprintf("%s: storage_encrypted must be true", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "aws_elasticache_replication_group"
	some attr in ["at_rest_encryption_enabled", "transit_encryption_enabled"]
	not object.get(after(rc), attr, false) in {true, "true"}
	msg := sprintf("%s: %s must be true", [rc.address, attr])
}

deny contains msg if {
	some rc in changes
	rc.type == "aws_s3_bucket"
	not sibling_exists(rc, "aws_s3_bucket_server_side_encryption_configuration")
	msg := sprintf("%s: S3 bucket has no server-side encryption configuration (KMS required)", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "aws_ebs_volume"
	object.get(after(rc), "encrypted", false) != true
	msg := sprintf("%s: EBS volume must be encrypted", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "aws_launch_template"
	some bdm in object.get(after(rc), "block_device_mappings", [])
	some ebs in object.get(bdm, "ebs", [])
	object.get(ebs, "encrypted", "false") != "true"
	msg := sprintf("%s: launch template EBS volumes must be encrypted", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "aws_eks_cluster"
	count(object.get(after(rc), "encryption_config", [])) == 0
	msg := sprintf("%s: EKS secrets must use KMS envelope encryption", [rc.address])
}

# ---------------------------------------------------------------- GCP (at rest is always on; enforce TLS)
deny contains msg if {
	some rc in changes
	rc.type == "google_sql_database_instance"
	some s in object.get(after(rc), "settings", [])
	some ip in object.get(s, "ip_configuration", [])
	not object.get(ip, "ssl_mode", "") in {"ENCRYPTED_ONLY", "TRUSTED_CLIENT_CERTIFICATE_REQUIRED"}
	msg := sprintf("%s: Cloud SQL ssl_mode must be ENCRYPTED_ONLY or TRUSTED_CLIENT_CERTIFICATE_REQUIRED", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "google_memorystore_instance"
	object.get(after(rc), "transit_encryption_mode", "") != "SERVER_AUTHENTICATION"
	msg := sprintf("%s: Memorystore must use SERVER_AUTHENTICATION transit encryption", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "google_container_cluster"
	some de in object.get(after(rc), "database_encryption", [])
	de.state != "ENCRYPTED"
	msg := sprintf("%s: GKE application-layer secrets encryption must be ENCRYPTED", [rc.address])
}

# ---------------------------------------------------------------- Azure
deny contains msg if {
	some rc in changes
	rc.type == "azurerm_storage_account"
	object.get(after(rc), "https_traffic_only_enabled", true) == false
	msg := sprintf("%s: https_traffic_only_enabled must be true", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "azurerm_storage_account"
	object.get(after(rc), "min_tls_version", "TLS1_2") != "TLS1_2"
	msg := sprintf("%s: min_tls_version must be TLS1_2", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "azurerm_redis_cache"
	object.get(after(rc), "non_ssl_port_enabled", false) == true
	msg := sprintf("%s: non-TLS Redis port must be disabled", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "azurerm_postgresql_flexible_server_configuration"
	lower(object.get(after(rc), "name", "")) == "require_secure_transport"
	lower(object.get(after(rc), "value", "on")) == "off"
	msg := sprintf("%s: require_secure_transport must stay ON", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "azurerm_postgresql_flexible_server"
	object.get(after(rc), "public_network_access_enabled", false) == true
	msg := sprintf("%s: PostgreSQL must not be publicly reachable", [rc.address])
}

# ---------------------------------------------------------------- OVHcloud
deny contains msg if {
	some rc in changes
	rc.type == "ovh_cloud_project_storage"
	object.get(object.get(after(rc), "encryption", {}), "sse_algorithm", "") == ""
	msg := sprintf("%s: Object Storage container must set encryption.sse_algorithm", [rc.address])
}

deny contains msg if {
	some rc in changes
	rc.type == "ovh_cloud_project_database"
	object.get(after(rc), "deletion_protection", false) != true
	object.get(after(rc), "description", "") != ""
	contains(object.get(after(rc), "description", ""), "-prod-")
	msg := sprintf("%s: production databases must have deletion_protection", [rc.address])
}
