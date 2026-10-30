-- Retention for the platform tables of one service database: platform_inbox (consumer de-duplication) and the
-- published rows of platform_outbox (db/platform/V0_0_1__outbox_inbox.sql).
--
-- Nothing in the code purges either table: both grow forever unless this runs (docs/runbooks/kafka-replay.md, "Retention").
--
-- Why not purge sooner: a duplicate delivery is harmless only while platform_inbox still holds (consumer, event_id).
-- Duplicates come from an offset reset (up to the topic retention, 7 days), a DLQ replay (the record can sit in the DLQ
-- for 14 days after it failed) and an outbox re-publish (any age). So keep inbox rows for
--     topic retention (7) + DLQ retention (14) + margin (14) = 35 days at the very least.
-- The default is 90 days; this script refuses anything under 35. After an event is older than the retention you chose,
-- a re-published or replayed copy WILL be processed again: do not replay or re-publish events older than that.
--
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f tools/ops/platform-purge.sql                       # counts only
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -v days=90 -v execute=yes -f tools/ops/platform-purge.sql
--
-- Variables: days (default 90, minimum 35), batch (rows per transaction, default 5000), execute ('yes' to delete),
--            outbox ('no' to leave platform_outbox alone, default 'yes').
-- Deletes in small committed batches (safe next to live traffic). Idempotent: re-running deletes only what aged out since.
-- No index exists on platform_inbox(processed_at) or platform_outbox(published_at): on a table with tens of millions
-- of rows run it off-peak (or add the indexes in a migration first).

\set ON_ERROR_STOP on
\if :{?days}
\else
  \set days 90
\endif
\if :{?batch}
\else
  \set batch 5000
\endif
\if :{?execute}
\else
  \set execute no
\endif
\if :{?outbox}
\else
  \set outbox yes
\endif

select (:days::int < 35) as too_short \gset
\if :too_short
  \echo 'Refusing: days must be at least 35 (7 topic + 14 DLQ + 14 margin).'
  select 1 / 0 as refused_retention_too_short;
\endif

select set_config('purge.days', :'days', false) as _d, set_config('purge.batch', :'batch', false) as _b \gset

\echo 'Rows older than the retention window:'
select 'platform_inbox' as "table", count(*) as rows_to_delete, min(processed_at) as oldest
from platform_inbox where processed_at < now() - make_interval(days => :days::int)
union all
select 'platform_outbox (published)', count(*), min(published_at)
from platform_outbox
where :'outbox' = 'yes' and published_at is not null and published_at < now() - make_interval(days => :days::int);

select (:'execute' = 'yes') as do_it \gset
\if :do_it
  do $$
  declare
    n int;
    cutoff timestamptz := now() - make_interval(days => current_setting('purge.days')::int);
    batch_size int := current_setting('purge.batch')::int;
  begin
    loop
      delete from platform_inbox
       where ctid in (select ctid from platform_inbox where processed_at < cutoff limit batch_size);
      get diagnostics n = row_count;
      commit;
      exit when n = 0;
    end loop;
  end $$;
  select (:'outbox' = 'yes') as purge_outbox \gset
  \if :purge_outbox
    do $$
    declare
      n int;
      cutoff timestamptz := now() - make_interval(days => current_setting('purge.days')::int);
      batch_size int := current_setting('purge.batch')::int;
    begin
      loop
        delete from platform_outbox
         where ctid in (select ctid from platform_outbox
                         where published_at is not null and published_at < cutoff limit batch_size);
        get diagnostics n = row_count;
        commit;
        exit when n = 0;
      end loop;
    end $$;
  \endif
  \echo 'Purged.'
\else
  \echo 'Counts only. Re-run with -v execute=yes to delete.'
\endif
