mock_provider "ovh" {}

variables {
  context = {
    name        = "afrotransact-prod"
    environment = "prod"
    region      = "GRA11"
    scope       = "project123"
    tags        = { env = "prod", owner = "platform", "cost-center" = "engineering" }
  }
  buckets = {
    exports      = { versioning = false, expire_after_days = 30 }
    kyc-evidence = { versioning = true, object_lock = true, retention_days = 2555, archive_after_days = 90 }
  }
}

run "lifecycle_keeps_storage_small" {
  command = plan
  assert {
    condition     = length(ovh_cloud_project_storage_object_bucket_lifecycle_configuration.this["exports"].rules) == 2
    error_message = "exports: abort stale uploads + expire after 30 days"
  }
  assert {
    condition     = length(ovh_cloud_project_storage_object_bucket_lifecycle_configuration.this["kyc-evidence"].rules) == 3
    error_message = "kyc: abort stale uploads, expire noncurrent versions, archive to infrequent access"
  }
  assert {
    condition     = ovh_cloud_project_storage.this["kyc-evidence"].object_lock.rule.mode == "compliance"
    error_message = "prod evidence is locked in compliance mode"
  }
}
