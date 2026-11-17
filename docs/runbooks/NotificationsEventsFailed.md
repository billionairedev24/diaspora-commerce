# NotificationsEventsFailed

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/kafka.yaml` · **Metric:** `notifications_events_failed_total{reason}`

## What it means
The notifications consumer set events aside instead of delivering them: it recorded each in the `failed_event` table,
committed the offset and moved on, so later events are not blocked. `reason`:

- `upstream_client_error`: identity or delivery-runs answered 4xx (not 408/429). The usual cause is a 403
  `identity.service-lookup-only`: the `notifications-service` token does not carry `users:read`, or identity did not accept it.
- `retries_exhausted`: a transient failure (5xx, timeout, connection refused) outlasted every retry (about two minutes).
- `rejected`: the notifier refused the request as invalid.

A deleted account (identity 404) is not a failure: those recipients are skipped silently (debug log).

## Diagnose
1. Log (no recipient data): WARN `event dead-lettered; offset committed, consumer continues` with `eventId`, `type`,
   `reason`, `upstreamStatus`, `upstreamCode`.
2. `SELECT event_id, event_type, reason, upstream_status, upstream_code, attempts, failed_at FROM failed_event WHERE replayed_at IS NULL ORDER BY failed_at;` (database of the notifications module).
3. 403 `identity.service-lookup-only`: check the client in identity (`notifications-service` has `users:read`; the secret
   `NOTIFICATIONS_CLIENT_SECRET` matches on both sides) and that identity is the release with the service-token verifier.
   Never print the secret.

## Mitigate
Fix the cause, then replay: the payload is in `failed_event.payload`; re-publish it with the same `event-id` header to its
topic (see [kafka-replay](kafka-replay.md); the inbox ignores events already processed) and set `replayed_at = now()`.
Events that cannot be delivered any more (deleted users, expired offers) are left; they are kept for audit.
