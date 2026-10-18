variable "prefix" {
  description = "Secret name prefix. Final names: <prefix>/<env>/<service> on AWS, <prefix>-<env>-<service> on GCP/Azure."
  type        = string
  default     = "afrotransact"
}

variable "services" {
  description = "Services that get a secret (one JSON document per service, read by External Secrets Operator)."
  type        = list(string)
}

variable "initial_values" {
  description = "Optional seed values per service (service => key => value). Written once; later edits happen in the secret store and are ignored by Terraform."
  type        = map(map(string))
  default     = {}
  sensitive   = true
}
