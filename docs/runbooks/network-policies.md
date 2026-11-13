# Network policies: default-deny egress (audit F13)

Before: each service had an ingress-only NetworkPolicy (whole namespace, `ingress`, `observability`) and no egress policy, so a
compromised pod could reach the internet, the node's metadata service and any other pod. Now the at-service library chart
(`infra/helm/library/at-service/templates/_netpol.tpl`) can add default-deny **egress** with explicit allowances. It is opt-in per
environment (`networkPolicy.egress.enabled` in `infra/argocd/envs/<env>/platform-values.yaml`): **on in uat, off in dev, trial and prod** until uat has soaked.

## What a service may reach (egress)

| Destination | Port | When |
|---|---|---|
| kube-dns (kube-system) | 53 UDP/TCP | always |
| OTLP collector (observability) | 4317, 4318 | always |
| Kafka (namespace kafka) | 9092 | always (single node, PLAINTEXT) |
| Services of the release namespace | service port 8080 | `egress.services: "*"` (default) or a list |
| Postgres | 5432 | services with `pg` in the matrix: to `egress.postgres.cidrs` (Layerbase) or any public IP; `inCluster` adds namespace data |
| Valkey (data) 6379 | | `valkey`: carrier-gateway, cart, gateway-bff, mcp-server, promise |
| Meilisearch (data) 7700 | | `meili`: indexer, search |
| Temporal (temporal) 7233 | | `temporal`: orders |
| OpenFGA (openfga) 8080 | | `fga`: authz-sync, catalog, offers, orders, payments |
| ClamAV (data) 3310 | | `clamav`: media |
| Public internet | 443 | `egress.https` (default true) to non-private IPs only |

Never allowed: 169.254.0.0/16 (the metadata IP 169.254.169.254), 10/8, 172.16/12, 192.168/16, 100.64/10, 127/8 on the internet and
database rules, and any non-443 port to the internet. The matrix itself is `at-service.netpol.matrix` in `_netpol.tpl` (from
`infra/ovh-vps/secrets.sh`: which service receives `REDIS_URL`, a database, Temporal ...). A new service that is not in the
matrix gets DNS, OTLP, Kafka, namespace services and 443 only: add it there or set `networkPolicy.egress.datastores` in its values.
Per-service overrides: `networkPolicy.egress.{services,datastores,postgres,https,extra}` in the service's `values-<env>.yaml`.

### Third parties reached on 443 (the allow-list)

A vanilla NetworkPolicy cannot match host names, so all services with `https: true` may reach any public 443. The names they need:
the public OIDC issuer `auth.<domain>` (every service: JWKS), api.stripe.com (payments, seller), Resend (notifications), openrouter.ai
(ai-gateway), Google Places/Maps and Smarty and nominatim.openstreetmap.org (geo), USPS, UPS, FedEx, DHL (carrier-gateway), AvaTax
(tax-returns), GS1 and world.openfoodfacts.org (catalog), Google/Apple/Facebook sign-in (identity), Cloudflare R2
`<account>.r2.cloudflarestorage.com` (media), Layerbase (Postgres 5432). Tightening, in order of value:
1. Set `egress.https: false` on every service that talks to no third party except the issuer, and point JWKS at the in-cluster identity (needs a config change, not done).
2. With an FQDN-aware CNI (Cilium) replace the 443 rule by `toFQDNs` rules for the names above.

### In-cluster service matrix (tightening phase)

Default allows the whole namespace on 8080 because the call graph is dense. Derived from the prod configs (UNVERIFIED for calls that use
env-supplied URLs); use as the input of `egress.services` lists:
gateway-bff calls 22 services; orders calls carrier-gateway, cart, catalog, content, delivery-runs, geo, identity, inventory, offers,
payments, promise, seller, tax-returns; mcp-server and ai-gateway call about ten each; leaf services (content, promise, geo, media
except ai-gateway) call few or none. Generate the exact list per service with the grep in `tools/ci/netpol_check.py --help` context:
`grep -ho 'http://[a-z-]*:8080' <service prod config>`; tighten one service at a time in uat and watch for `connection timed out`.

## Rollout plan

1. **Pre-checks**: `python3 tools/ci/netpol_check.py --env prod` passes (every chart renders a policy, DNS present, metadata blocked).
   Find the CNI: `kubectl -n kube-system get pods` (OVH MKS: UNVERIFIED whether Canal/Calico or Cilium). NetworkPolicy needs an enforcing CNI; on a CNI that ignores it nothing is blocked (silent failure: test in step 3).
2. **uat** (already enabled in `infra/argocd/envs/uat/platform-values.yaml`): sync, then smoke: `kubectl -n afrotransact-uat get pods`,
   login, browse, checkout on test keys, upload a file, a Kafka-driven flow (order confirmation e-mail), search.
   Watch: `kubectl logs` for `timed out`, `no route`, DNS errors; the Alloy dashboards for 5xx.
3. **Prove enforcement** from a pod: `kubectl -n afrotransact-uat exec deploy/cart -- wget -T3 -qO- http://169.254.169.254/` must time out;
   `... wget -T3 -qO- http://example.com` (port 80) must time out; DNS and the app itself keep working. If these succeed, the CNI does not enforce.
4. Soak at least one week of uat including a deploy and a node restart. Fix gaps with `egress.extra` per service, not by widening the library.
5. **dev**, then **prod**: set `enabled: true` in `platform-values.yaml`; in prod first fill `postgres.cidrs` with Layerbase's addresses
   (ask Layerbase for stable ranges; with `[]` port 5432 is open to any public IP). Sync manually (prod autoSync is off), one wave at a time.
6. **Rollback**: set `enabled: false` and sync: the policy goes back to ingress-only within a sync.
7. The platform namespaces (data, kafka, temporal, ops) keep their ingress policies (`infra/k8s/platform/lean`); they have no egress policy yet.
   Next: egress policies for temporal (to Postgres and itself) and the backup CronJob (to Postgres and Backblaze B2).

## Caveats

- Policies are additive: any other policy selecting the same pods that allows more still applies. The chart emits one policy per service.
- Egress rules are evaluated after DNAT on most CNIs, so allow the **pod** port (8080), not the Service port (80): done here.
- Rolling updates and Argo Rollouts canaries use the same labels: nothing extra needed.
- Health probes from the kubelet are not blocked by NetworkPolicy on mainstream CNIs. If a CNI blocks node traffic, add the node CIDR to ingress.
- Cloudflare Tunnel (`cloudflared`, namespace ingress) is unaffected: it is the ingress source, and its namespace has no egress policy.

## Kafka SASL: later

The single-node Kafka listener is PLAINTEXT without authentication and is reachable from every namespace in the cluster that may egress to
port 9092. Moving to SASL/SCRAM over a TLS listener needs: broker config and users, a Secret per service, `security.protocol=SASL_SSL` in every
Spring and Go client, and a rolling restart of everything. It is **not done** (it would break the single-node setup if half-applied). Until
then the egress policy above plus the ingress policy of `infra/k8s/platform/lean/kafka.yaml` (namespaces labelled part-of afrotransact) is the control.
