{{- define "at-service.name" -}}{{ .Values.service.name }}{{- end -}}

{{- define "at-service.labels" -}}
app.kubernetes.io/name: {{ include "at-service.name" . }}
app.kubernetes.io/part-of: afrotransact
app.kubernetes.io/component: {{ .Values.service.runtime }}
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
afrotransact.com/env: {{ .Values.env }}
{{- end -}}

{{- define "at-service.selector" -}}
app.kubernetes.io/name: {{ include "at-service.name" . }}
{{- end -}}

{{- define "at-service.image" -}}
{{ .Values.image.registry }}/{{ .Values.image.repository | default (include "at-service.name" .) }}:{{ .Values.image.tag }}
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
{{- end }}
- name: PORT
  value: {{ .Values.service.port | quote }}
- name: TRACE_SAMPLING
  value: {{ .Values.telemetry.sampling | quote }}
{{- range $k, $v := .Values.extraEnv }}
- name: {{ $k }}
  value: {{ $v | quote }}
{{- end }}
{{- end -}}
