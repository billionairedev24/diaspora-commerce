-- S392: existing and migrated listings against the enforced main-image rules (docs/engineering/bulk-upload-and-images.md,
-- section 6.7). Live listings stay live ("grandfathered"). A back-fill job measures each listing's main image at
-- media-service and stores how it stands: OK, IMPROVE (passes with a warning) or FAIL. Sellers see an "Improve your image"
-- task for IMPROVE and FAIL, with the reasons in plain words. `failing_since` starts the grace period (60 days by default);
-- only when the separate hide flag is on does the hide step set `hidden_at` for listings still failing after it. Nothing is
-- deleted and no listing is closed by this migration: it only adds a table, empty until the job runs.
create table product_image_quality (
    product_id     uuid         not null primary key references product (id) on delete cascade,
    owner_org      varchar(80)  not null,
    state          varchar(10)  not null check (state in ('OK', 'IMPROVE', 'FAIL')),
    -- The findings: [{code, level, message}] (plain English, no ids; Seller Center translates by code).
    reasons        jsonb        not null default '[]'::jsonb,
    main_image_url text,
    checked_at     timestamptz  not null,
    -- When the listing first failed; kept while it keeps failing, cleared when it stops (the grace clock).
    failing_since  timestamptz,
    -- Set by the hide step (flag at.catalog.images.hide-failing); cleared when the listing passes again.
    hidden_at      timestamptz
);
create index product_image_quality_org on product_image_quality (owner_org, state);
create index product_image_quality_checked on product_image_quality (checked_at);
create index product_image_quality_to_hide on product_image_quality (failing_since)
    where state = 'FAIL' and hidden_at is null;
