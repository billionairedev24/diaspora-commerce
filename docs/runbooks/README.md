# Runbooks

One page per alert (below), plus operational procedures. Every alert's `runbook_url` annotation points here (rules in
`infra/observability/rules/`).

## Procedures

| Procedure | When |
|---|---|
| [First environment](first-environment.md) | bootstrapping dev / uat / prod on AWS, Google Cloud or Azure (state, OIDC, secrets, apply, Argo CD, Temporal, Pages, smoke) |
| [Test environment on one OVHcloud server](ovh-vps.md) | creating and deploying the whole stack on one OVHcloud instance from a pipeline (`make ovh-gh-setup`, then Actions > ovh-vps); not production |
| [Stripe webhooks](stripe-webhooks.md) | creating, verifying, rotating and troubleshooting the three Stripe webhook endpoints (payments, seller Connect, seller plan fees) for the OVH test environment and the real ones |
| [Finance: payouts, reconciliation and ledger adjustments](finance-reconciliation.md) | approving a payout, the daily Stripe reconciliation, four-eyes ledger corrections, Stripe lookups |
| [Delivery quotes](delivery-quotes.md) | an address is too far for standard delivery: how the shopper asks, how customer care agrees an amount, how the order carries it, SLA and escalation |
| [Maintenance mode](maintenance-mode.md) | turn on the maintenance page before planned work, what it pauses, how to clear it without the console |
| [Settings in the staff console](settings.md) | where every platform setting lives, who calculates sales tax (our rules or Stripe Tax), finding a transfer in Stripe |
| [Production readiness review](production-readiness.md) | what was checked, what could not be, and what blocks go-live |
| [Cost](cost.md) | a budget alert fired; the monthly cost review |
| [Kafka dead letters, replay and recovery](kafka-replay.md) | an event was not applied by a consumer: inspect a DLQ, replay records, reset a consumer group's offsets, re-publish outbox rows, rebuild a read model (`tools/ops/kafka-replay`) |
| [Carrier sandboxes](carrier-sandboxes.md) | proving USPS / UPS / FedEx / DHL against their sandboxes; carrier credentials |
| [CSP violations spike](csp-violations-spike.md) | many `csp.violation` log lines, or blocked images / payment forms / sign-in after a release; rollback to Report-Only |

## Alerts

| Alert | Severity |
|---|---|
| [CheckoutSuccessBudgetBurn](CheckoutSuccessBudgetBurn.md) | page / ticket |
| [DatabasePoolSaturation](DatabasePoolSaturation.md) | ticket |
| [ElasticsearchClusterRed](ElasticsearchClusterRed.md) | page |
| [ElasticsearchClusterYellow](ElasticsearchClusterYellow.md) | ticket |
| [ElasticsearchDiskLow](ElasticsearchDiskLow.md) | ticket |
| [ElasticsearchHeapPressure](ElasticsearchHeapPressure.md) | ticket |
| [HighErrorRate](HighErrorRate.md) | page |
| [HighLatency](HighLatency.md) | ticket |
| [IndexerLagHigh](IndexerLagHigh.md) | page |
| [InventoryReconciliationDrift](InventoryReconciliationDrift.md) | ticket |
| [InventoryReconciliationStale](InventoryReconciliationStale.md) | ticket |
| [KafkaConsumerLagGrowing](KafkaConsumerLagGrowing.md) | page |
| [KafkaConsumerLagHigh](KafkaConsumerLagHigh.md) | ticket |
| [KafkaDeadLetterMessages](KafkaDeadLetterMessages.md) | ticket |
| [KafkaDeadLetterWriteFailing](KafkaDeadLetterWriteFailing.md) | page |
| [NotificationDeliveryBudgetBurn](NotificationDeliveryBudgetBurn.md) | page / ticket |
| [OtelCollectorExportFailures](OtelCollectorExportFailures.md) | ticket |
| [OutboxBacklogGrowing](OutboxBacklogGrowing.md) | ticket |
| [PaymentWebhookLagBudgetBurn](PaymentWebhookLagBudgetBurn.md) | page / ticket |
| [PayoutOnTimeBudgetBurn](PayoutOnTimeBudgetBurn.md) | page / ticket |
| [PostgresConnectionsSaturation](PostgresConnectionsSaturation.md) | page |
| [PostgresDiskUsageHigh](PostgresDiskUsageHigh.md) | page / ticket |
| [PostgresDown](PostgresDown.md) | page |
| [SearchLatencyBudgetBurn](SearchLatencyBudgetBurn.md) | page / ticket |
| [ServiceDown](ServiceDown.md) | page |
| [TransferReversalRefused](TransferReversalRefused.md) | ticket |
