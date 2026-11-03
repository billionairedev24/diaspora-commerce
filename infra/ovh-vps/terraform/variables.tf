variable "ovh_location" {
  description = "us-east-va (Vint Hill, Virginia, OVHcloud US), us-west-or (Hillsboro, OVHcloud US) or ca-bhs (Beauharnois, OVHcloud Canada); see locations.json. The account must belong to that OVHcloud entity (US and Canada accounts are separate)."
  type        = string
  default     = "us-east-va"

  validation {
    condition     = contains(["us-east-va", "us-west-or", "ca-bhs"], var.ovh_location)
    error_message = "ovh_location must be us-east-va, us-west-or or ca-bhs."
  }
}

variable "service_name" {
  description = "Public Cloud project id (32 hex characters). A project costs nothing by itself."
  type        = string
}

variable "instance_flavor" {
  description = "Public Cloud flavor. b3-32 is 8 vCPU / 32 GB: the smallest that holds the whole stack with its data stores. Billed by the hour while the instance exists, powered on or off."
  type        = string
  default     = "b3-32"
}

variable "instance_image" {
  description = "Image name (the login user is ubuntu)."
  type        = string
  default     = "Ubuntu 24.04"
}

variable "ssh_public_key" {
  description = "The pipeline's deploy key (OpenSSH public key line); make ovh-gh-setup creates the pair."
  type        = string
}
