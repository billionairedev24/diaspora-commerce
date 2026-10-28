{{/* Deployment, or an Argo Rollouts canary when rollout.enabled (uat/prod). */}}
{{- define "at-service.workload" -}}
{{- if .Values.rollout.enabled }}
apiVersion: argoproj.io/v1alpha1
kind: Rollout
{{- else }}
apiVersion: apps/v1
kind: Deployment
{{- end }}
metadata:
  name: {{ include "at-service.name" . }}
  labels: {{- include "at-service.labels" . | nindent 4 }}
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicas }}
  {{- end }}
  revisionHistoryLimit: 5
  selector:
    matchLabels: {{- include "at-service.selector" . | nindent 6 }}
  {{- if .Values.rollout.enabled }}
  strategy:
    canary:
      canaryService: {{ include "at-service.name" . }}-canary
      stableService: {{ include "at-service.name" . }}
      analysis:
        templates:
          - templateName: {{ include "at-service.name" . }}-slo
        startingStep: 1
      steps:
        - setWeight: 10
        - pause: {duration: {{ .Values.rollout.pause }}}
        - setWeight: 50
        - pause: {duration: {{ .Values.rollout.pause }}}
        - setWeight: 100
  {{- else }}
  strategy:
    type: RollingUpdate
    rollingUpdate: {maxSurge: 25%, maxUnavailable: 0}
  {{- end }}
  template:
    metadata:
      labels: {{- include "at-service.labels" . | nindent 8 }}
        {{- if eq .Values.cloud "azure" }}
        azure.workload.identity/use: "true"
        {{- end }}
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: {{ include "at-service.metricsPath" . }}
        prometheus.io/port: {{ .Values.service.port | quote }}
    spec:
      serviceAccountName: {{ include "at-service.name" . }}
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: {{ if eq .Values.service.runtime "java" }}1000{{ else }}65532{{ end }}
        fsGroup: 1000
        seccompProfile: {type: RuntimeDefault}
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels: {{- include "at-service.selector" . | nindent 14 }}
      {{- with .Values.nodeSelector }}
      nodeSelector: {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations: {{- toYaml . | nindent 8 }}
      {{- end }}
      terminationGracePeriodSeconds: 30
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets: {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: app
          image: {{ include "at-service.image" . }}
          imagePullPolicy: IfNotPresent
          ports:
            - {name: http, containerPort: {{ .Values.service.port }}}
          env: {{- include "at-service.envVars" . | nindent 12 }}
          {{- if .Values.secrets.enabled }}
          envFrom:
            - secretRef:
                name: {{ include "at-service.name" . }}-secrets
          {{- end }}
          resources: {{- toYaml .Values.resources | nindent 12 }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: [ALL]}
          startupProbe:
            httpGet: {path: {{ include "at-service.livenessPath" . }}, port: http}
            periodSeconds: 5
            failureThreshold: {{ if eq .Values.service.runtime "java" }}36{{ else }}12{{ end }}
          livenessProbe:
            httpGet: {path: {{ include "at-service.livenessPath" . }}, port: http}
            periodSeconds: 10
          readinessProbe:
            httpGet: {path: {{ include "at-service.readinessPath" . }}, port: http}
            periodSeconds: 5
          lifecycle:
            preStop:
              sleep: {seconds: 5}
          volumeMounts:
            - {name: tmp, mountPath: /tmp}
      volumes:
        - name: tmp
          emptyDir: {}
{{- end -}}
