output "buckets" {
  description = "Bucket key => {name, id, url}. `name` is what the identity module scopes S3 policies to."
  value = { for k, b in ovh_cloud_project_storage.this : k => {
    name = b.name
    id   = b.name
    url  = "https://${b.virtual_host}"
  } }
}

output "bucket_names" {
  value = { for k, b in ovh_cloud_project_storage.this : k => b.name }
}

output "endpoint" {
  description = "S3 endpoint of the region (path-style or virtual-host)."
  value       = "https://s3.${lower(local.site)}.io.cloud.ovh.net"
}
