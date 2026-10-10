-- S127 Sponsored products: seller campaigns (CPC bids, budgets, keyword / category targeting, advertised ATINs),
-- daily delivery stats, charged clicks and 7-day click attribution of orders.
-- search-service runs the auction and publishes ads.impression.recorded / ads.click.recorded (at.ads.events);
-- content consumes them (inbox-deduplicated) to count impressions, charge clicks and pause campaigns at zero budget,
-- and consumes orders.order.placed for attributed sales.

create table ad_campaign (
    id                 varchar(40)  primary key,
    seller_org_id      varchar(40)  not null,
    advertiser         varchar(120) not null,
    name               varchar(120) not null,
    market             char(2)      not null,
    status             varchar(16)  not null check (status in ('ACTIVE', 'PAUSED', 'ENDED')),
    pause_reason       varchar(24)  check (pause_reason in ('MANUAL', 'BUDGET_EXHAUSTED')),
    bid_minor          bigint       not null check (bid_minor > 0),
    daily_budget_minor bigint       check (daily_budget_minor > 0),
    total_budget_minor bigint       not null check (total_budget_minor > 0),
    spent_minor        bigint       not null default 0 check (spent_minor >= 0),
    currency           char(3)      not null,
    keywords           jsonb        not null default '[]',
    categories         jsonb        not null default '[]',
    atins              jsonb        not null default '[]',
    starts_at          timestamptz  not null,
    ends_at            timestamptz,
    created_by         varchar(80)  not null,
    created_at         timestamptz  not null,
    updated_at         timestamptz  not null,
    version            bigint       not null default 0,
    -- Budgets are never overspent: clicks are charged at most what is left.
    constraint ad_campaign_within_budget check (spent_minor <= total_budget_minor),
    constraint ad_campaign_paused_reason check ((status = 'PAUSED') = (pause_reason is not null))
);
create index ad_campaign_serving on ad_campaign (market, status);
create index ad_campaign_seller on ad_campaign (seller_org_id, created_at desc);

create table ad_campaign_daily (
    campaign_id            varchar(40) not null references ad_campaign (id),
    day                    date        not null,
    impressions            bigint      not null default 0,
    clicks                 bigint      not null default 0,
    spend_minor            bigint      not null default 0,
    attributed_orders      bigint      not null default 0,
    attributed_sales_minor bigint      not null default 0,
    primary key (campaign_id, day)
);

-- One row per billable click (click id = ads.click.recorded clickId, stable per served ad token).
create table ad_click (
    click_id      varchar(64)  primary key,
    campaign_id   varchar(40)  not null references ad_campaign (id),
    atin          varchar(16)  not null,
    variant_atins jsonb        not null default '[]',
    customer_id   varchar(80),
    price_minor   bigint       not null,
    charged_minor bigint       not null check (charged_minor >= 0),
    market        char(2)      not null,
    query         varchar(200),
    occurred_at   timestamptz  not null
);
create index ad_click_attribution on ad_click (customer_id, occurred_at desc) where customer_id is not null;
create index ad_click_campaign on ad_click (campaign_id, occurred_at desc);

-- An order line is attributed to at most one click (last click within 7 days, same shopper, same seller).
create table ad_attribution (
    order_id      varchar(64)  not null,
    line_id       varchar(64)  not null,
    click_id      varchar(64)  not null references ad_click (click_id),
    campaign_id   varchar(40)  not null references ad_campaign (id),
    sales_minor   bigint       not null,
    attributed_at timestamptz  not null,
    primary key (order_id, line_id)
);
