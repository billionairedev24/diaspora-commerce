-- Re-emit events from a service's transactional outbox (table platform_outbox, db/platform/V0_0_1__outbox_inbox.sql).
--
-- How the outbox works (java/libs/platform-events JdbcOutboxStore + KafkaOutboxRelay):
--   * a business change appends a row with published_at = NULL in the same transaction;
--   * the relay (at.events.relay-enabled=true) locks rows WHERE published_at IS NULL ORDER BY occurred_at, sends each to
--     topic at.<aggregate_type>.events with key = aggregate_id and headers event-id (= the row id), event-type,
--     occurred-at, then sets published_at = now(). Rows are never deleted.
--   Re-publishing therefore means setting published_at back to NULL: the relay sends the SAME event again, with the
--   SAME event-id, so a consumer whose inbox (platform_inbox) already holds (consumer, event-id) ignores it.
--
-- Use it when the event is missing from Kafka (topic deleted or past retention, new consumer group that must see old
-- events, broker data loss). Do NOT use it to retry an event that sits in a DLQ: replay that (tools/ops/kafka-replay).
-- Consumers that already processed an event will NOT process it again (inbox) - that is the point; to make one
-- re-process, see "reprocessing" in docs/runbooks/kafka-replay.md.
--
-- Ordering warning: the relay sends the oldest unpublished rows first, so a re-published old event can arrive AFTER newer
-- events of the same aggregate. Re-publish every later event of the aggregate too (widen the range), or only events
-- whose consumers are version-guarded or order-insensitive.
--
-- Run it against the owning service's database, preview first (default), then execute:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v from='2026-03-01T00:00:00Z' -v to='2026-03-01T06:00:00Z' \
--        -v type_like='order.%' -f tools/ops/outbox-republish.sql                 # preview only
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v event_ids='<uuid>,<uuid>' -v execute=yes -f tools/ops/outbox-republish.sql
--
-- Variables (give event_ids, or from and to):
--   event_ids   comma-separated outbox ids (= the event-id header)
--   from, to    occurred_at range, from inclusive, to exclusive, ISO-8601
--   type_like   optional LIKE filter on type, e.g. 'order.%'
--   aggregate   optional aggregate_type filter, e.g. 'order'
--   max_rows    refuse to touch more rows than this (default 1000)
--   execute     'yes' to update; anything else only previews
--
-- Idempotent: it only touches rows that are published now; running it twice before the relay has picked the rows up
-- changes nothing the second time. Run it while the relay is up (pod/service running) or the rows wait until it is.

\set ON_ERROR_STOP on

\if :{?execute}
\else
  \set execute no
\endif
\if :{?max_rows}
\else
  \set max_rows 1000
\endif
\if :{?event_ids}
\else
  \set event_ids ''
\endif
\if :{?from}
\else
  \set from ''
\endif
\if :{?to}
\else
  \set to ''
\endif
\if :{?type_like}
\else
  \set type_like ''
\endif
\if :{?aggregate}
\else
  \set aggregate ''
\endif

-- Refuse an open-ended selection (the error makes psql exit non-zero).
select (:'event_ids' = '' and (:'from' = '' or :'to' = '')) as no_selection \gset
\if :no_selection
  \echo 'Give -v event_ids=... or both -v from=... and -v to=...'
  select 1 / 0 as refused_no_selection;
\endif

begin;

create temporary table republish_selection on commit drop as
select id, type, aggregate_type, aggregate_id, occurred_at, published_at
from platform_outbox
where published_at is not null
  and (:'event_ids' = '' or id = any (string_to_array(:'event_ids', ',')::uuid[]))
  and occurred_at >= coalesce(nullif(:'from', '')::timestamptz, '-infinity')
  and occurred_at < coalesce(nullif(:'to', '')::timestamptz, 'infinity')
  and (:'type_like' = '' or type like :'type_like')
  and (:'aggregate' = '' or aggregate_type = :'aggregate')
order by occurred_at;

\echo 'Selected (already published) outbox rows, by type:'
select type, aggregate_type, count(*) as events, min(occurred_at) as oldest, max(occurred_at) as newest
from republish_selection group by type, aggregate_type order by events desc;

select count(*) as selected from republish_selection \gset
select (:selected > :max_rows) as too_many \gset
\if :too_many
  \echo 'Refusing: more than max_rows rows selected. Narrow the range or raise -v max_rows=...'
  rollback;
  select 1 / 0 as refused_too_many_rows;
\endif

-- Ids that are not in the outbox at all (typos, or an event of another service).
select wanted.id as unknown_event_id
from unnest(string_to_array(nullif(:'event_ids', ''), ',')::uuid[]) as wanted(id)
where wanted.id not in (select id from platform_outbox);

select (:'execute' = 'yes') as do_it \gset
\if :do_it
  update platform_outbox o
     set published_at = null
    from republish_selection s
   where o.id = s.id
     and o.published_at is not null;
  \echo 'Marked unpublished: the relay will re-send them in occurred_at order (event-id unchanged).'
  commit;
\else
  \echo 'Preview only. Re-run with -v execute=yes to re-publish.'
  rollback;
\endif

-- Verify: unpublished backlog drains to zero once the relay has run.
--   select count(*) from platform_outbox where published_at is null;
