output "buckets" {
  description = "Bucket key => {name, id, url}. `id` (container resource id) is what the identity module grants on."
  value = { for k, c in azurerm_storage_container.this : k => {
    name = c.name
    id   = c.id
    url  = "${azurerm_storage_account.this.primary_blob_endpoint}${c.name}"
  } }
}

output "bucket_names" {
  value = { for k, c in azurerm_storage_container.this : k => "${azurerm_storage_account.this.name}/${c.name}" }
}

output "endpoint" {
  description = "Blob endpoint (S3-compatible access goes through the media service's storage adapter)."
  value       = azurerm_storage_account.this.primary_blob_endpoint
}
