-- A negotiated FBA fee per unit for one organisation (every FBA line), next to negotiated commission and connection
-- fees: same dates, reason and audit. Its amount is in minor units (amount_minor); rate_bps stays 0.
alter table negotiated_rate drop constraint negotiated_rate_type_check;
alter table negotiated_rate add constraint negotiated_rate_type_check
    check (type in ('COMMISSION', 'CONNECTION_FEE', 'FBA_FEE'));
alter table negotiated_rate add column amount_minor bigint check (amount_minor is null or amount_minor >= 0);
alter table negotiated_rate add constraint negotiated_rate_amount check ((type = 'FBA_FEE') = (amount_minor is not null));

-- The FBA fee per unit negotiated when the line was priced (null = the plan's or the schedule's fee applied).
alter table payment_line add column negotiated_fba_minor bigint check (negotiated_fba_minor is null or negotiated_fba_minor >= 0);
