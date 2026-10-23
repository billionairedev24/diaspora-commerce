# Runbooks

One page per alert (below), plus operational procedures. Every alert's `runbook_url` annotation points here (rules in
`infra/observability/rules/`).

## Procedures

| Procedure | When |
|---|---|
| [First environment](first-environment.md) | bootstrapping dev / uat / prod on AWS, Google Cloud or Azure (state, OIDC, secrets, apply, Argo CD, Temporal, Pages, smoke) |
| [Test environment on one OVHcloud server](ovh-vps.md) | creating and deploying the whole stack on one OVHcloud instance from a pipeline (`make ovh-gh-setup`, then Actions > ovh-vps); not production |
| [Finance: payouts, reconciliation and ledger adjustments](finance-reconciliation.md) | approving a payout, the daily Stripe reconciliation, four-eyes ledger corrections, Stripe lookups |
| [Maintenance mode](maintenance-mode.md) | turn on the maintenance page before planned work, what it pauses, how to clear it without the console |
| [Settings in the staff console](settings.md) | where every platform setting lives, who calculates sales tax (our rules or Stripe Tax), finding a transfer in Stripe |
| [Production readiness review](production-readiness.md) | what was checked, what could not be, and what blocks go-live |
| [Cost](cost.md) | a budget alert fired; the monthly cost review |
| [Carrier sandboxes](carrier-sandboxes.md) | proving USPS / UPS / FedEx / DHL against their sandboxes; carrier credentials |

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
| [NotificationDeliveryBudgetBurn](NotificationDeliveryBudgetBurn.md) | page / ticket |
| [OtelCollectorExportFailures](OtelCollectorExportFailures.md) | ticket |
| [OutboxBacklogGrowing](OutboxBacklogGrowing.md) | ticket |
| [PaymentWebhookLagBudgetBurn](PaymentWebhookLagBudgetBurn.md) | page / ticket |
| [PayoutOnTimeBudgetBurn](PayoutOnTimeBudgetBurn.md) | page / ticket |
| [PostgresConnectionsSaturation](PostgresConnectionsSaturation.md) | page |
| [PostgresDown](PostgresDown.md) | page |
| [SearchLatencyBudgetBurn](SearchLatencyBudgetBurn.md) | page / ticket |
| [ServiceDown](ServiceDown.md) | page |
