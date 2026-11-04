variable "repositories" {
  description = "Image repositories (one per service). GHCR images are mirrored here by .github/workflows/_deploy.yml."
  type        = list(string)
}

variable "immutable_tags" {
  description = "Refuse to overwrite an existing tag."
  type        = bool
  default     = true
}

variable "keep_last" {
  description = "Keep the newest N images per repository; older untagged/sha images are cleaned up."
  type        = number
  default     = 50
}

variable "reader_principals" {
  description = "The cluster's node identity (`node_identity` output of the cluster module) and any other pullers."
  type        = list(string)
  default     = []
}
