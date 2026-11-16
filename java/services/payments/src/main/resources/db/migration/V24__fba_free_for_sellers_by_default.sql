-- Owner decision: Fulfilled by AfroTransact (FBA) costs the SELLER nothing unless staff explicitly configure it. The delivery
-- fee is paid by the customer and is 100 % AfroTransact revenue (never part of the seller payable). Commission rules are
-- not touched.
--
-- Where a seller-side FBA fee can still be set (platform staff only, audited, versioned, never edited in place): the
-- fee schedule version in the staff "Fees & commissions" editor, which holds
--   * fba_per_unit_minor + fee_schedule_plan_fba (pick, pack and ship per unit),
--   * the SELLER tariff of fee_schedule_delivery_tariff (the pickup fee charged to a seller whose location AfroTransact
--     collects from; default and per-seller overrides),
-- and negotiated FBA fees (negotiated_rate FBA_FEE). Each change is a new version with created_by and a note.
--
-- This migration changes only the untouched launch seed: when the version in force was written by 'system:seed' (nobody
-- from staff ever published a schedule), it adds one new version, a copy of it with the seller-side FBA charges at 0.
-- A schedule staff already published is left exactly as they set it. Re-running changes nothing (the new version is
-- created_by 'system:migration', so it is no longer the seed).
do $$
declare
    src fee_schedule%rowtype;
    dst uuid := gen_random_uuid();
begin
    select * into src from fee_schedule f
     where f.effective_from <= now()
       and not exists (select 1 from fee_schedule_cancellation c where c.schedule_id = f.id)
     order by f.effective_from desc, f.version desc
     limit 1;
    if not found or src.created_by <> 'system:seed' then
        return;
    end if;

    insert into fee_schedule (id, version, effective_from, currency, default_commission_bps, card_pct_bps,
                              card_fixed_minor, fba_per_unit_minor, reverse_commission_on_refund, note, created_by,
                              created_at)
    values (dst, (select max(version) + 1 from fee_schedule), now(), src.currency, src.default_commission_bps,
            src.card_pct_bps, src.card_fixed_minor, 0, src.reverse_commission_on_refund,
            'Fulfilled by AfroTransact is free for sellers by default (the customer pays the delivery fee); '
            || 'commission unchanged', 'system:migration', now());

    insert into fee_schedule_rate (schedule_id, category, commission_bps)
    select dst, category, commission_bps from fee_schedule_rate where schedule_id = src.id;
    insert into fee_schedule_override (schedule_id, org_id, category, commission_bps)
    select dst, org_id, category, commission_bps from fee_schedule_override where schedule_id = src.id;
    insert into fee_schedule_plan_rate (schedule_id, section, plan_code, category, rate_bps)
    select dst, section, plan_code, category, rate_bps from fee_schedule_plan_rate where schedule_id = src.id;
    insert into fee_schedule_connection_fee (schedule_id, default_bps)
    select dst, default_bps from fee_schedule_connection_fee where schedule_id = src.id;
    insert into fee_schedule_connection_fee_rate (schedule_id, category, rate_bps)
    select dst, category, rate_bps from fee_schedule_connection_fee_rate where schedule_id = src.id;

    -- Delivery pricing: the customer's tariff is copied as is; the seller's pickup fee is 0 (flat) with no overrides.
    insert into fee_schedule_delivery (schedule_id, max_radius_miles)
    select dst, max_radius_miles from fee_schedule_delivery where schedule_id = src.id;
    insert into fee_schedule_delivery_tariff (schedule_id, party, org_id, mode, flat_per_package_minor, base_minor,
                                              per_mile_minor, bands, min_minor, max_minor)
    select dst, party, org_id, mode, flat_per_package_minor, base_minor, per_mile_minor, bands, min_minor, max_minor
      from fee_schedule_delivery_tariff where schedule_id = src.id and party = 'CUSTOMER';
    insert into fee_schedule_delivery_tariff (schedule_id, party, org_id, mode, flat_per_package_minor)
    select dst, 'SELLER', '', 'FLAT', 0 from fee_schedule_delivery where schedule_id = dst;
    -- Per-plan FBA fees (fee_schedule_plan_fba) are not copied: every plan pays the new default of 0.
end
$$;
