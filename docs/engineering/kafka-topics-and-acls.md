# Kafka topics and ACLs

One list of topics, derived, not typed three times.

## Where each list comes from

| List | Source | Kept in step by |
|---|---|---|
| Topics | `contracts/asyncapi/events.yaml` (generated from `contracts/asyncapi/fragments/<service>.yaml`) | `python3 tools/contracts/merge_asyncapi.py --check` |
| Producers | fragment operations with `action: send` | `tools/ops/topics-check.sh` |
| Consumers | fragment operations with `action: receive` **plus** the code: Java `@KafkaListener` topics, Go `config/*.yaml` `topics:`. The union is used because an ACL must cover what really runs and the prose lags the code in places | `tools/ops/topics-check.sh` |
| Consumer group ids | `group-id` in `java/services/*/src/main/resources/application.yml` and the `groupId` defaults of the listeners; `group:` in `go/services/*/config/*.yaml`; notifications' `ConsumerGroup` constant | `tools/ops/topics-check.sh` |
| Terraform (Confluent) | `infra/tofu/kafka-topics.json`, read by `infra/tofu/envs/{dev,uat,prod}/main.tf`; **generated**: `tools/ops/topics-check.sh --write` | `tools/ops/topics-check.sh` |
| Local Docker | `infra/local/kafka/topics.txt` (exact set) | `tools/ops/topics-check.sh` |
| OVH / lean cluster | DLQ topic Job in `infra/k8s/platform/lean/kafka.yaml` (exact set; event topics are auto-created) | `tools/ops/topics-check.sh`, `tools/ops/dlq-topics.sh --check` |

CI workflow: `.github/workflows/kafka-topics.yml`. After adding or renaming a channel: run
`python3 tools/contracts/merge_asyncapi.py`, `tools/ops/topics-check.sh --write`, add the aggregate to
`infra/local/kafka/topics.txt` and its `.dlq` to the lean Job, then run `tools/ops/topics-check.sh`.

## ACLs per service (Confluent)

Per service account: `WRITE` on the topics it produces, `READ` on the topics it consumes, `READ` on its consumer groups by
**prefix**, and, for Java services (the `platform-events` error handler), `WRITE` on `<topic>.dlq` of every topic they
consume. Go consumers keep their own dead-letter topic (indexer, authz-sync) or store (notifications) and get no `.dlq` write.

The group ACL used to be `<service>.` (prefixed), which matches no real group (`payments-service` does not start with
`payments.`), so a consumer on Confluent would have been refused with `GroupAuthorizationException`. The prefix is now the
shortest real group id of the service; suffixed groups (`trust-ops-service-moderation`, `payments-service-referral`) are
covered by it. If `KAFKA_GROUP` is set in a deployment, the id must still start with that prefix.

| Service | Runtime | Consumer group ids in code | ACL prefix | Topics read |
|---|---|---|---|---|
| authz-sync | Go | `authz-sync` | `authz-sync*` | `at.identity.events`, `at.seller-org.events` |
| bookings | Java | `bookings-service` | `bookings-service*` | `at.payment.events`, `at.seller-compliance.events` |
| catalog | Java | `catalog-service` | `catalog-service*` | `at.offers.events`, `at.seller-compliance.events` |
| content | Java | `content-service` | `content-service*` | `at.ads.events`, `at.order.events` |
| delivery-runs | Java | `delivery-runs-service` | `delivery-runs-service*` | `at.order.events`, `at.payment.events` |
| fulfilment | Java | `fulfilment-service` | `fulfilment-service*` | `at.delivery-runs.events`, `at.identity.events`, `at.order.events` |
| identity | Java | `identity-service` | `identity-service*` | `at.seller-org.events` |
| indexer | Go | `indexer` | `indexer*` | `at.inventory.events`, `at.offers.events`, `at.product.events`, `at.seller-org.events` |
| inventory | Java | `inventory-service` | `inventory-service*` | `at.offers.events`, `at.product.events`, `at.retail.events` |
| notifications | Go | `notifications` | `notifications*` | `at.bookings.events`, `at.content.events`, `at.delivery-runs.events`, `at.identity.events`, `at.inventory.events`, `at.order.events`, `at.payment.events`, `at.payout.events`, `at.product-import.events`, `at.retail.events`, `at.seller-application.events`, `at.seller-compliance.events`, `at.seller-subscription.events`, `at.trustops.events` |
| offers | Java | `offers-service` | `offers-service*` | `at.inventory-inbound.events`, `at.inventory.events`, `at.order.events`, `at.product.events`, `at.retail.events`, `at.seller-compliance.events`, `at.seller-org.events` |
| orders | Java | `orders-service` | `orders-service*` | `at.carrier-shipment.events`, `at.delivery-runs.events`, `at.payment.events` |
| payments | Java | `payments-service`, `payments-service-referral` | `payments-service*` | `at.bookings.events`, `at.carrier-shipment.events`, `at.identity.events`, `at.order.events`, `at.retail.events`, `at.seller-compliance.events`, `at.seller-subscription.events` |
| retail | Java | `retail-service` | `retail-service*` | `at.inventory-reservation.events` |
| seller | Java | `seller-service` | `seller-service*` | `at.bookings.events`, `at.identity.events`, `at.offers.events`, `at.order.events` |
| tax-returns | Java | `tax-returns-service` | `tax-returns-service*` | `at.carrier-shipment.events`, `at.order.events`, `at.payment.events` |
| trust-ops | Java | `trust-ops-service`, `trust-ops-service-audit`, `trust-ops-service-fulfilment`, `trust-ops-service-moderation`, `trust-ops-service-product-reviews`, `trust-ops-service-reviews`, `trust-ops-service-seller-health`, `trust-ops-service-support` | `trust-ops-service*` | `at.ai-handoff.events`, `at.bookings.events`, `at.fulfilment.events`, `at.identity.events`, `at.mcp-tool.events`, `at.order.events`, `at.payout.events`, `at.product-review.events`, `at.product.events`, `at.seller-application.events`, `at.seller-compliance.events` |

## Corrections made to the previous Terraform list

The old `locals.topics` in the three envs declared 17 topics, most of which did not exist (`at.offer.events` instead of
`at.offers.events`; `at.shipment`, `at.seller`, `at.booking`, `at.trust`, `at.media`, `at.return`, `at.notification`), omitted
about 20 real ones, invented consumers (`cart`, `promise`, `gateway-bff`) and `local.services` lacked `authz-sync` and
`delivery-runs` (so neither had Kafka credentials). Both services are added; `delivery-runs` also adds its `deliveryruns`
database to the cloud Postgres, which it needs anyway.

## Not verified here

`tofu fmt` / `tofu validate` were not run (no `tofu`/`terraform` binary in the build container); the HCL was formatted by
hand and the `tofu.yml` workflow runs both. The first `tofu plan` after merge is expected to show: topics replaced by the
real names (the old ones were consumed by nothing real), group ACLs replaced, DLQ write ACLs narrowed to Java consumers,
and two new service accounts, keys and workload identities.
