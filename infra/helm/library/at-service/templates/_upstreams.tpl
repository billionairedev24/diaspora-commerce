{{/*
Release 1 upstream wiring: ONE source of truth for every URL a deployable uses to reach a module of the OTHER deployable (or a module that has
not moved yet). The deployable chart lists them in values `upstreams` (ENV_NAME: module, or {module: x, suffix: /path}); this library renders
the environment variables, so no per-service port (81xx/82xx), no `http://localhost:81xx` and no per-service URL is needed outside the local
profile. docs/runbooks/release-1-deploy.md, "Upstream wiring"; checked by tools/release/upstreams.py (make release-1-check).

For each entry the target module M lives in deployable D (registry below) and the answer depends on where M runs NOW (cutover.services):
  M runs in THIS deployable, Java core    http://localhost:<service.port>/modules/<M>     loopback on core itself: never a Service, never an old port.
                                          Only for the Java -> Java edges that are still HTTP; the list shrinks as edges become in-process.
  M runs in THIS deployable, Go           nothing: Go modules reach each other in-process (modcfg), the env name is not rendered.
  M runs in the OTHER deployable          http://<D>.<namespace>.svc[/modules/<M>]        /modules/<M> for every Java module (core serves each module under
                                          it, which also picks the right module for the three duplicate routes POST /v1/returns, /v1/receipts,
                                          /v1/reconciliation/runs) and for the Go modules mounted `prefixed` (cart, search, carrier-gateway); the
                                          flat Go modules (geo, media, notifications, promise, gateway-bff) are reached on the bare Service.
  M has not moved yet                     http://<M>[suffix]                              its own per-service Service (port 80), which exists until the
                                          cutover phase. A batch therefore never breaks the modules that are still standalone.
cutover.services in infra/argocd/envs/<env>/platform-values.yaml is the only input that changes a URL.
*/}}

{{/* Where each module runs and how the Go ones are mounted (go/apps/afrotransact-go/internal/registry, java/apps/core CoreApplication). */}}
{{- define "at-service.upstreams.registry" -}}
identity: {deployable: afrotransact-core}
catalog: {deployable: afrotransact-core}
offers: {deployable: afrotransact-core}
inventory: {deployable: afrotransact-core}
orders: {deployable: afrotransact-core}
payments: {deployable: afrotransact-core}
seller: {deployable: afrotransact-core}
retail: {deployable: afrotransact-core}
fulfilment: {deployable: afrotransact-core}
bookings: {deployable: afrotransact-core}
trust-ops: {deployable: afrotransact-core}
tax-returns: {deployable: afrotransact-core}
content: {deployable: afrotransact-core}
ai-gateway: {deployable: afrotransact-core}
mcp-server: {deployable: afrotransact-core}
delivery-runs: {deployable: afrotransact-core}
gateway-bff: {deployable: afrotransact-go}
search: {deployable: afrotransact-go, mount: prefixed}
indexer: {deployable: afrotransact-go}
cart: {deployable: afrotransact-go, mount: prefixed}
geo: {deployable: afrotransact-go}
media: {deployable: afrotransact-go}
notifications: {deployable: afrotransact-go}
promise: {deployable: afrotransact-go}
carrier-gateway: {deployable: afrotransact-go, mount: prefixed}
authz-sync: {deployable: afrotransact-go}
{{- end -}}

{{/* The rendered upstream environment variables of this deployable, as YAML (name: value). Callers merge it under the user's extraEnv. */}}
{{- define "at-service.upstreams.env" -}}
{{- $self := .Values.service.name -}}
{{- $ns := .Release.Namespace -}}
{{- $reg := fromYaml (include "at-service.upstreams.registry" .) -}}
{{- $services := (.Values.cutover | default dict).services | default dict -}}
{{- $mods := .Values.modules | default dict -}}
{{- $out := dict -}}
{{- range $name, $spec := (.Values.upstreams | default dict) -}}
{{- $module := $spec -}}
{{- $suffix := "" -}}
{{- if kindIs "map" $spec -}}{{- $module = $spec.module -}}{{- $suffix = $spec.suffix | default "" -}}{{- end -}}
{{- $r := get $reg (toString $module) -}}
{{- if not $r -}}{{- fail (printf "upstreams.%s: unknown module %q (registry in infra/helm/library/at-service/templates/_upstreams.tpl)" $name (toString $module)) -}}{{- end -}}
{{- $dep := $r.deployable -}}
{{- $movedTo := get $services (toString $module) | default "" -}}
{{- if eq $dep $self -}}
{{- $m := get $mods (toString $module) | default dict -}}
{{- $here := or $m.enabled (eq $movedTo $self) -}}
{{- if $here -}}
{{- if eq $.Values.service.runtime "java" -}}
{{- $_ := set $out $name (printf "http://localhost:%v/modules/%s%s" $.Values.service.port $module $suffix) -}}
{{- end -}}
{{- else -}}
{{- $_ := set $out $name (printf "http://%s%s" $module $suffix) -}}
{{- end -}}
{{- else if eq $movedTo $dep -}}
{{- $prefix := "" -}}
{{- if or (eq $dep "afrotransact-core") (eq (toString $r.mount) "prefixed") -}}{{- $prefix = printf "/modules/%s" $module -}}{{- end -}}
{{- $_ := set $out $name (printf "http://%s.%s.svc%s%s" $dep $ns $prefix $suffix) -}}
{{- else -}}
{{- $_ := set $out $name (printf "http://%s%s" $module $suffix) -}}
{{- end -}}
{{- end -}}
{{- toYaml $out -}}
{{- end -}}
