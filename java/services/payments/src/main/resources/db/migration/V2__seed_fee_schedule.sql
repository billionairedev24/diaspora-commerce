-- Default fee schedule (version 1), matching the prototype's "Fees & commissions" defaults:
-- commission by category, card processing 2.9 % + 30¢ (estimate until Stripe reports the real fee),
-- FBA pick, pack & ship 3.25 per unit, commission given back on refunds.
insert into fee_schedule (id, version, effective_from, currency, default_commission_bps, card_pct_bps,
                          card_fixed_minor, fba_per_unit_minor, reverse_commission_on_refund, note, created_by,
                          created_at)
values ('00000000-0000-0000-0000-00000000fee1', 1, timestamptz '2020-01-01 00:00:00+00', 'USD', 1000, 290, 30, 325,
        true, 'Launch defaults', 'system:seed', timestamptz '2020-01-01 00:00:00+00');

insert into fee_schedule_rate (schedule_id, category, commission_bps)
values ('00000000-0000-0000-0000-00000000fee1', 'groceries', 800),
       ('00000000-0000-0000-0000-00000000fee1', 'fashion', 1200),
       ('00000000-0000-0000-0000-00000000fee1', 'beauty', 1000),
       ('00000000-0000-0000-0000-00000000fee1', 'home & art', 1200),
       ('00000000-0000-0000-0000-00000000fee1', 'books', 1000),
       ('00000000-0000-0000-0000-00000000fee1', 'health', 1000),
       ('00000000-0000-0000-0000-00000000fee1', 'party', 1000),
       ('00000000-0000-0000-0000-00000000fee1', 'services', 1000);
