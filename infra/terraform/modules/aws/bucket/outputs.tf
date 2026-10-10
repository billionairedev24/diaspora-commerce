output "buckets" {
  description = "Bucket key => {name, id, url}. `id` is what the identity module grants on."
  value = { for k, b in aws_s3_bucket.this : k => {
    name = b.bucket
    id   = b.arn
    url  = "s3://${b.bucket}"
  } }
}

output "bucket_names" {
  value = { for k, b in aws_s3_bucket.this : k => b.bucket }
}

output "endpoint" {
  description = "S3-compatible endpoint for SDKs."
  value       = "https://s3.${var.context.region}.amazonaws.com"
}
