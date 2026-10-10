{{- define "at-service.serviceAccount" -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "at-service.name" . }}
  labels: {{- include "at-service.labels" . | nindent 4 }}
  {{- with (include "at-service.saAnnotations" . | trim) }}
  annotations: {{- . | nindent 4 }}
  {{- end }}
{{- end -}}

{{- define "at-service.service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "at-service.name" . }}
  labels: {{- include "at-service.labels" . | nindent 4 }}
spec:
  selector: {{- include "at-service.selector" . | nindent 4 }}
  ports:
    - {name: http, port: 80, targetPort: http}
{{- if .Values.rollout.enabled }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ include "at-service.name" . }}-canary
  labels: {{- include "at-service.labels" . | nindent 4 }}
spec:
  selector: {{- include "at-service.selector" . | nindent 4 }}
  ports:
    - {name: http, port: 80, targetPort: http}
{{- end }}
{{- end -}}

{{- define "at-service.hpa" -}}
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "at-service.name" . }}
  labels: {{- include "at-service.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: {{ if .Values.rollout.enabled }}argoproj.io/v1alpha1{{ else }}apps/v1{{ end }}
    kind: {{ if .Values.rollout.enabled }}Rollout{{ else }}Deployment{{ end }}
    name: {{ include "at-service.name" . }}
  minReplicas: {{ .Values.autoscaling.min }}
  maxReplicas: {{ .Values.autoscaling.max }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: {type: Utilization, averageUtilization: {{ .Values.autoscaling.cpu }}}
{{- end }}
{{- end -}}

{{- define "at-service.pdb" -}}
{{- if or .Values.autoscaling.enabled (gt (int .Values.replicas) 1) }}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "at-service.name" . }}
  labels: {{- include "at-service.labels" . | nindent 4 }}
spec:
  maxUnavailable: 1
  selector:
    matchLabels: {{- include "at-service.selector" . | nindent 6 }}
{{- end }}
{{- end -}}

{{- define "at-service.serviceMonitor" -}}
{{- if .Values.monitoring.serviceMonitor }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "at-service.name" . }}
  labels: {{- include "at-service.labels" . | nindent 4 }}
    release: kube-prometheus-stack
spec:
  selector:
    matchLabels: {{- include "at-service.selector" . | nindent 6 }}
  endpoints:
    - port: http
      path: {{ include "at-service.metricsPath" . }}
      interval: 30s
{{- end }}
{{- end -}}

{{- define "at-service.networkPolicy" -}}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "at-service.name" . }}
  labels: {{- include "at-service.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels: {{- include "at-service.selector" . | nindent 6 }}
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: {{ .Release.Namespace }}}
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: ingress}
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: observability}
      ports:
        - {port: {{ .Values.service.port }}}
{{- end -}}

{{- define "at-service.externalSecret" -}}
{{- if .Values.secrets.enabled }}
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: {{ include "at-service.name" . }}-secrets
  labels: {{- include "at-service.labels" . | nindent 4 }}
spec:
  refreshInterval: 1h
  secretStoreRef:
    kind: ClusterSecretStore
    name: {{ .Values.secrets.store }}
  target:
    name: {{ include "at-service.name" . }}-secrets
  dataFrom:
    - extract:
        {{- /* AWS Secrets Manager allows "/"; GCP Secret Manager and Azure Key Vault need "-" (secrets.keySeparator). */}}
        key: {{ join (.Values.secrets.keySeparator | default "/") (list "afrotransact" .Values.env (include "at-service.name" .)) }}
{{- end }}
{{- end -}}

{{- define "at-service.analysis" -}}
{{- if .Values.rollout.enabled }}
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: {{ include "at-service.name" . }}-slo
  labels: {{- include "at-service.labels" . | nindent 4 }}
spec:
  metrics:
    - name: error-rate
      interval: 1m
      failureLimit: 1
      successCondition: result[0] < {{ .Values.rollout.maxErrorRate }}
      provider:
        prometheus:
          address: {{ .Values.monitoring.prometheusUrl }}
          query: |
            sum(rate(http_server_requests_seconds_count{service="{{ include "at-service.name" . }}",status=~"5.."}[2m]) or rate(http_server_requests_total{service="{{ include "at-service.name" . }}",status=~"5.."}[2m]))
            /
            sum(rate(http_server_requests_seconds_count{service="{{ include "at-service.name" . }}"}[2m]) or rate(http_server_requests_total{service="{{ include "at-service.name" . }}"}[2m]))
{{- end }}
{{- end -}}

{{/* Render everything for a service chart: {{ include "at-service.all" . }} */}}
{{- define "at-service.all" -}}
{{ include "at-service.serviceAccount" . }}
---
{{ include "at-service.service" . }}
---
{{ include "at-service.workload" . }}
{{- with (include "at-service.hpa" . | trim) }}
---
{{ . }}
{{- end }}
{{- with (include "at-service.pdb" . | trim) }}
---
{{ . }}
{{- end }}
{{- with (include "at-service.serviceMonitor" . | trim) }}
---
{{ . }}
{{- end }}
---
{{ include "at-service.networkPolicy" . }}
{{- with (include "at-service.externalSecret" . | trim) }}
---
{{ . }}
{{- end }}
{{- with (include "at-service.analysis" . | trim) }}
---
{{ . }}
{{- end }}
{{- end -}}
