{{/*
Egress default-deny (audit F13). When networkPolicy.egress.enabled is true the service's NetworkPolicy also lists Egress in
policyTypes, so everything not allowed below is dropped, the cloud metadata service (169.254.169.254) included: it is never
allowed (every ipBlock below excludes link-local, private and CGNAT ranges).

Allowed, in order:
  1. DNS to kube-dns in kube-system (UDP and TCP 53).
  2. OTLP traces to the collector in observability (4317, 4318).
  3. In-cluster services: every pod in the release namespace on the service port (networkPolicy.egress.services: "*", default),
     or only the listed service names (tightening step, docs/runbooks/network-policies.md).
  4. Data stores, from the matrix below (or networkPolicy.egress.datastores, which replaces it for this service):
       pg       Postgres: port 5432 to networkPolicy.egress.postgres.cidrs (Layerbase) or, when empty, to any public IP;
                in-cluster Postgres in namespace data when networkPolicy.egress.postgres.inCluster (dev/test box)
       kafka    namespace kafka, 9092            valkey  namespace data, 6379
       temporal namespace temporal, 7233         meili   namespace data, 7700
       fga      namespace openfga, 8080          clamav  namespace data, 3310
  5. HTTPS (443) to public IPs when networkPolicy.egress.https (default true): third-party APIs (Stripe, Resend, OpenRouter,
     carriers, R2 ...) and the public OIDC issuer (JWKS is fetched from https://auth.<domain>). A vanilla NetworkPolicy cannot
     filter by host name: the per-service FQDN allow-list is documented in docs/runbooks/network-policies.md and enforceable
     with an FQDN-aware CNI (Cilium) only.
  6. networkPolicy.egress.extra: raw egress rules for anything else.
*/}}

{{/* Data stores a service talks to. Unlisted services get none (they still get DNS, OTLP, services, kafka and https). */}}
{{- define "at-service.netpol.matrix" -}}
identity: [pg]
catalog: [pg, fga]
offers: [pg, fga]
inventory: [pg]
orders: [pg, temporal, fga]
payments: [pg, fga]
seller: [pg]
retail: [pg]
fulfilment: [pg]
bookings: [pg]
trust-ops: [pg]
tax-returns: [pg]
content: [pg]
ai-gateway: [pg]
delivery-runs: [pg]
mcp-server: [valkey]
gateway-bff: [valkey]
search: [meili]
indexer: [meili]
cart: [pg, valkey]
promise: [valkey]
geo: [pg]
media: [pg, clamav]
notifications: [pg]
carrier-gateway: [pg, valkey]
authz-sync: [fga]
{{- end -}}

{{/* Public-internet ipBlock: everything except private, link-local (cloud metadata), CGNAT and loopback ranges. */}}
{{- define "at-service.netpol.publicBlock" -}}
ipBlock:
  cidr: 0.0.0.0/0
  except: [10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, 100.64.0.0/10, 127.0.0.0/8]
{{- end -}}

{{- define "at-service.netpol.egress" -}}
{{- $np := default dict .Values.networkPolicy -}}
{{- $eg := default dict $np.egress -}}
{{- $matrix := fromYaml (include "at-service.netpol.matrix" .) -}}
{{- $stores := default (default list (get $matrix (include "at-service.name" .))) $eg.datastores -}}
{{- $pg := default dict $eg.postgres -}}
{{- $https := true -}}
{{- if hasKey $eg "https" }}{{- $https = $eg.https -}}{{- end -}}
{{- $services := default "*" $eg.services -}}
{{ print "  egress:" }}
    - to:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: kube-system}
          podSelector:
            matchLabels: {k8s-app: kube-dns}
      ports:
        - {port: 53, protocol: UDP}
        - {port: 53, protocol: TCP}
    - to:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: observability}
      ports:
        - {port: 4317}
        - {port: 4318}
    {{- if kindIs "string" $services }}
    - to:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: {{ .Release.Namespace }}}
      ports:
        - {port: {{ .Values.service.port }}}
    {{- else if $services }}
    - to:
        {{- range $services }}
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: {{ $.Release.Namespace }}}
          podSelector:
            matchLabels: {app.kubernetes.io/name: {{ . }}}
        {{- end }}
      ports:
        - {port: {{ .Values.service.port }}}
    {{- end }}
    - to:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: kafka}
      ports:
        - {port: 9092}
    {{- if has "pg" $stores }}
    - to:
        {{- if $pg.cidrs }}
        {{- range $pg.cidrs }}
        - ipBlock: {cidr: {{ . }}}
        {{- end }}
        {{- else }}
        - {{- include "at-service.netpol.publicBlock" . | nindent 10 }}
        {{- end }}
        {{- if $pg.inCluster }}
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: data}
        {{- end }}
      ports:
        - {port: 5432}
    {{- end }}
    {{- if has "valkey" $stores }}
    - to:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: data}
          podSelector:
            matchLabels: {app.kubernetes.io/name: valkey}
      ports:
        - {port: 6379}
    {{- end }}
    {{- if has "meili" $stores }}
    - to:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: data}
          podSelector:
            matchLabels: {app.kubernetes.io/name: meilisearch}
      ports:
        - {port: 7700}
    {{- end }}
    {{- if has "clamav" $stores }}
    - to:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: data}
          podSelector:
            matchLabels: {app.kubernetes.io/name: clamav}
      ports:
        - {port: 3310}
    {{- end }}
    {{- if has "temporal" $stores }}
    - to:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: temporal}
      ports:
        - {port: 7233}
    {{- end }}
    {{- if has "fga" $stores }}
    - to:
        - namespaceSelector:
            matchLabels: {kubernetes.io/metadata.name: openfga}
      ports:
        - {port: 8080}
    {{- end }}
    {{- if $https }}
    - to:
        - {{- include "at-service.netpol.publicBlock" . | nindent 10 }}
      ports:
        - {port: 443}
    {{- end }}
    {{- with $eg.extra }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
{{- end -}}
