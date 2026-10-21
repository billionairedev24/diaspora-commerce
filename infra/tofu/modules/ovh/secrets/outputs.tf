output "store" {
  description = "Handle for the identity and postgres modules."
  value = {
    cloud         = "ovh"
    prefix        = local.prefix
    path          = local.prefix
    separator     = "/"
    okms_id       = ovh_okms.this.id
    rest_endpoint = ovh_okms.this.rest_endpoint
  }
}

output "secret_names" {
  description = "Service => secret path (the ExternalSecret `extract.key`)."
  value       = { for k, s in ovh_okms_secret.service : k => s.path }
}

output "cluster_secret_store" {
  description = "spec.provider for the External Secrets ClusterSecretStore named platform-secrets (Vault KV v2 on OKMS, mTLS)."
  value = {
    vault = {
      server  = ovh_okms.this.rest_endpoint
      path    = "secret"
      version = "v2"
      caProvider = {
        type      = "Secret"
        name      = "okms-client"
        namespace = "external-secrets"
        key       = "ca.crt"
      }
      auth = {
        cert = {
          clientCert = { name = "okms-client", namespace = "external-secrets", key = "tls.crt" }
          secretRef  = { name = "okms-client", namespace = "external-secrets", key = "tls.key" }
        }
      }
    }
  }
}
