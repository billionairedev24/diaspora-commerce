output "buckets" {
  description = "Bucket key => {name, id, url}. `id` is what the identity module grants on."
  value = { for k, b in google_storage_bucket.this : k => {
    name = b.name
    id   = b.name
    url  = b.url
  } }
}

output "bucket_names" {
  value = { for k, b in google_storage_bucket.this : k => b.name }
}

output "endpoint" {
  description = "S3-compatible (XML API, HMAC) endpoint for SDKs."
  value       = "https://storage.googleapis.com"
}
