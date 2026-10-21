{{- define "at-service.name" -}}{{ .Values.service.name }}{{- end -}}

{{- define "at-service.labels" -}}
app.kubernetes.io/name: {{ include "at-service.name" . }}
app.kubernetes.io/part-of: afrotransact
app.kubernetes.io/component: {{ .Values.service.runtime }}
app.kubernetes.io/version: {{ include "at-service.version" . | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
afrotransact.com/env: {{ .Values.env }}
{{- end -}}

{{- define "at-service.selector" -}}
app.kubernetes.io/name: {{ include "at-service.name" . }}
{{- end -}}

{{/*
Image reference. Every workload must pin its image: image.tag (CI writes the full git sha into values-<env>.yaml)
and/or image.digest (sha256:…; rendered as repo[:tag]@digest). A missing tag and digest, or the mutable tag
"latest", fails the render. Local and not-yet-deployed charts use the sentinel tag "dev".
*/}}
{{- define "at-service.imageTag" -}}
{{- $tag := trim (toString (.Values.image.tag | default "")) -}}
{{- $digest := trim (toString (.Values.image.digest | default "")) -}}
{{- if eq (lower $tag) "latest" -}}
{{- fail (printf "at-service: image.tag \"latest\" is not allowed for %s - set an immutable tag (git sha) or image.digest (local renders: the sentinel tag \"dev\")" (include "at-service.name" .)) -}}
{{- end -}}
{{- if and (not $tag) (not $digest) -}}
{{- fail (printf "at-service: image.tag or image.digest is required for %s - set an explicit tag (git sha) or digest (local renders: the sentinel tag \"dev\")" (include "at-service.name" .)) -}}
{{- end -}}
{{- if and $digest (not (regexMatch "^sha256:[a-f0-9]{64}$" $digest)) -}}
{{- fail (printf "at-service: image.digest %q for %s must be sha256:<64 hex>" $digest (include "at-service.name" .)) -}}
{{- end -}}
{{- $tag -}}
{{- end -}}

{{- define "at-service.image" -}}
{{- $tag := include "at-service.imageTag" . -}}
{{- $digest := trim (toString (.Values.image.digest | default "")) -}}
{{ .Values.image.registry }}/{{ .Values.image.repository | default (include "at-service.name" .) }}{{ if $tag }}:{{ $tag }}{{ end }}{{ if $digest }}@{{ $digest }}{{ end }}
{{- end -}}

{{/* app.kubernetes.io/version: the tag, else "sha256-" + 12 hex chars of the digest (label values cannot hold ':'). */}}
{{- define "at-service.version" -}}
{{- $tag := include "at-service.imageTag" . -}}
{{- if $tag -}}
{{- $tag | trunc 63 | trimSuffix "-" | trimSuffix "." -}}
{{- else -}}
{{- printf "sha256-%s" (trimPrefix "sha256:" (toString .Values.image.digest) | trunc 12) -}}
{{- end -}}
{{- end -}}

{{/* Health and metrics paths differ between Spring Boot and Go services. */}}
{{- define "at-service.livenessPath" -}}{{ if eq .Values.service.runtime "java" }}/actuator/health/liveness{{ else }}/healthz{{ end }}{{- end -}}
{{- define "at-service.readinessPath" -}}{{ if eq .Values.service.runtime "java" }}/actuator/health/readiness{{ else }}/readyz{{ end }}{{- end -}}
{{- define "at-service.metricsPath" -}}{{ if eq .Values.service.runtime "java" }}/actuator/prometheus{{ else }}/metrics{{ end }}{{- end -}}

{{/* Workload identity annotation per cloud, so pods reach the cloud secret store and buckets without keys. */}}
{{- define "at-service.saAnnotations" -}}
{{- $id := .Values.workloadIdentity -}}
{{- if eq .Values.cloud "aws" }}
eks.amazonaws.com/role-arn: {{ required "workloadIdentity.aws.roleArn is required when cloud=aws" $id.aws.roleArn }}
{{- else if eq .Values.cloud "gcp" }}
iam.gke.io/gcp-service-account: {{ required "workloadIdentity.gcp.serviceAccount is required when cloud=gcp" $id.gcp.serviceAccount }}
{{- else if eq .Values.cloud "azure" }}
azure.workload.identity/client-id: {{ required "workloadIdentity.azure.clientId is required when cloud=azure" $id.azure.clientId }}
{{- end }}
{{- end -}}

{{- define "at-service.envVars" -}}
- name: APP_ENV
  value: {{ .Values.env | quote }}
{{- if eq .Values.service.runtime "java" }}
- name: SPRING_PROFILES_ACTIVE
  value: {{ .Values.env | quote }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ printf "http://%s" .Values.telemetry.otlpEndpoint | quote }}
{{- else }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT_HOST
  value: {{ .Values.telemetry.otlpEndpoint | quote }}
{{- if and (include "at-service.goMemLimit" .) (not (hasKey (.Values.extraEnv | default dict) "GOMEMLIMIT")) }}
- name: GOMEMLIMIT
  value: {{ include "at-service.goMemLimit" . | quote }}
{{- end }}
{{- end }}
- name: PORT
  value: {{ .Values.service.port | quote }}
- name: TRACE_SAMPLING
  value: {{ .Values.telemetry.sampling | quote }}
{{- include "at-service.platformEnvVars" . }}
{{- range $k, $v := .Values.extraEnv }}
- name: {{ $k }}
  value: {{ $v | quote }}
{{- end }}
{{- end -}}

{{/*
Environment-wide public URLs, derived from global.domain (set once per environment in
infra/argocd/envs/<env>/platform-values.yaml) so no service hard-codes a host:
  PLATFORM_ENV       .Values.env                      always
  PLATFORM_DOMAIN    <domain>                         dev.afrotransact.com | uat.afrotransact.com | afrotransact.com
  PUBLIC_WEB_URL     https://<domain>                 storefront
  SELLER_CENTER_URL  https://seller.<domain>          Seller Center & Provider Hub
  STAFF_CONSOLE_URL  https://admin.<domain>           staff console
  API_BASE_URL       https://api.<domain>             gateway-bff (public API)
  AUTH_ISSUER_URL    https://auth.<domain>            identity (OIDC issuer; token endpoint = <issuer>/oauth2/token)
  MEDIA_PUBLIC_URL   https://media.<domain>           public media CDN (R2 custom domain)
Host names match infra/cloudflare (local.hosts). global.urls.<NAME> overrides one derived value for the whole
environment; a service's own extraEnv.<NAME> wins over both (the derived entry is then not rendered, so a name
never appears twice). Without global.domain (plain `helm template` in CI) only PLATFORM_ENV is rendered and
services fall back to their config/<env>.yaml / application-<env>.yml values.
*/}}
{{- define "at-service.platformEnvVars" -}}
{{- $g := .Values.global | default dict -}}
{{- $extra := .Values.extraEnv | default dict -}}
{{- $domain := trim (toString ($g.domain | default "")) -}}
{{- $vars := dict "PLATFORM_ENV" (toString .Values.env) -}}
{{- if $domain -}}
{{- if not (regexMatch "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$" $domain) -}}
{{- fail (printf "at-service: global.domain %q must be a bare lower-case host name such as dev.afrotransact.com (no scheme, port or path)" $domain) -}}
{{- end -}}
{{- $_ := set $vars "PLATFORM_DOMAIN" $domain -}}
{{- $_ := set $vars "PUBLIC_WEB_URL" (printf "https://%s" $domain) -}}
{{- $_ := set $vars "SELLER_CENTER_URL" (printf "https://seller.%s" $domain) -}}
{{- $_ := set $vars "STAFF_CONSOLE_URL" (printf "https://admin.%s" $domain) -}}
{{- $_ := set $vars "API_BASE_URL" (printf "https://api.%s" $domain) -}}
{{- $_ := set $vars "AUTH_ISSUER_URL" (printf "https://auth.%s" $domain) -}}
{{- $_ := set $vars "MEDIA_PUBLIC_URL" (printf "https://media.%s" $domain) -}}
{{- end -}}
{{- range $k, $v := ($g.urls | default dict) -}}
{{- $_ := set $vars $k (toString $v) -}}
{{- end -}}
{{- range $k := keys $vars | sortAlpha -}}
{{- if not (hasKey $extra $k) }}
- name: {{ $k }}
  value: {{ get $vars $k | quote }}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Go soft memory limit: 90% of the container's memory limit (Mi or Gi), so the garbage collector works harder before the
kernel OOM-kills the pod. Empty when the limit is not a whole number of Mi/Gi (then GOMEMLIMIT is simply not set).
*/}}
{{- define "at-service.goMemLimit" -}}
{{- $limits := (.Values.resources | default dict).limits | default dict -}}
{{- $m := toString ($limits.memory | default "") -}}
{{- if regexMatch "^[0-9]+(Mi|Gi)$" $m -}}
{{- $n := int64 (regexFind "^[0-9]+" $m) -}}
{{- if hasSuffix "Gi" $m }}{{ $n = mul $n 1024 }}{{ end -}}
{{- printf "%dMiB" (div (mul $n 90) 100) -}}
{{- end -}}
{{- end -}}
