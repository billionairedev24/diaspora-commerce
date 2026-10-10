output "network_id" {
  description = "VPC id."
  value       = aws_vpc.this.id
}

output "cluster_subnet_ids" {
  description = "Private subnets for Kubernetes nodes and pods."
  value       = aws_subnet.cluster[*].id
}

output "data_subnet_ids" {
  description = "Private subnets for managed databases and caches."
  value       = aws_subnet.data[*].id
}

output "network" {
  description = "Opaque handle consumed by the cluster, postgres, cache and bucket modules of the same cloud."
  value = {
    cloud              = "aws"
    id                 = aws_vpc.this.id
    cidr_block         = aws_vpc.this.cidr_block
    zones              = local.azs
    cluster_subnet_ids = aws_subnet.cluster[*].id
    data_subnet_ids    = aws_subnet.data[*].id
    public_subnet_ids  = aws_subnet.public[*].id
    egress_ips         = aws_eip.nat[*].public_ip # allow-list these at Elastic Cloud / Confluent / Stripe
  }
}
