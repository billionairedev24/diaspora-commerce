-- Gateway-wide daily spend ceiling: roll-ups per caller (all features) and for the whole gateway, next to the existing
-- per user×feature and per feature ones.
alter table ai_usage_daily drop constraint ai_usage_daily_scope_check;
alter table ai_usage_daily add constraint ai_usage_daily_scope_check
    check (scope in ('USER', 'FEATURE', 'CALLER', 'GLOBAL'));

-- Seed today's totals from the ledger so a deploy in the middle of the day does not reset the ceiling.
insert into ai_usage_daily (usage_day, scope, scope_key, tokens, cost_micros)
select usage_day, 'CALLER', subject, sum(prompt_tokens + completion_tokens), sum(cost_micros)
from ai_usage
where usage_day = (now() at time zone 'utc')::date
group by usage_day, subject
on conflict (usage_day, scope, scope_key) do nothing;

insert into ai_usage_daily (usage_day, scope, scope_key, tokens, cost_micros)
select usage_day, 'GLOBAL', 'all', sum(prompt_tokens + completion_tokens), sum(cost_micros)
from ai_usage
where usage_day = (now() at time zone 'utc')::date
group by usage_day
on conflict (usage_day, scope, scope_key) do nothing;
