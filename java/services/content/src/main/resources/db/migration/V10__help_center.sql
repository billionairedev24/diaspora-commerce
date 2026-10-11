-- Help center (S278): the pages behind the storefront's /help links — the hub, shipping & delivery, returns, Fulfilled
-- by AfroTransact, advertising and press. Copy comes from the Shop design's info pages; site-content staff edit it in
-- the staff console (content:site.edit), so it changes without a deployment.
create table help_article (
    slug        varchar(60)  primary key,
    topic       varchar(32)  not null,
    title       varchar(120) not null,
    summary     varchar(300) not null,
    sections    jsonb        not null,
    status      varchar(16)  not null,
    sort_order  integer      not null default 0,
    updated_at  timestamptz  not null default now(),
    version     bigint       not null default 0,
    constraint help_article_status_ck check (status in ('DRAFT', 'PUBLISHED')),
    constraint help_article_topic_ck check (topic in ('ORDERS_DELIVERY', 'RETURNS_REFUNDS', 'ACCOUNT_PAYMENTS', 'SELLING', 'COMPANY'))
);

create index help_article_listing_ix on help_article (status, topic, sort_order);

insert into help_article (slug, topic, title, summary, sections, status, sort_order) values
('help-center', 'ORDERS_DELIVERY', 'Help center', 'Track a package, return an item or reach us.',
 '[{"heading":"Track a package","body":"[Your orders](/orders) shows every shipment and its live status from carriers and AfroTransact Delivery."},
   {"heading":"Returns","body":"Most items can be returned within 30 days. Food is refundable if damaged or wrong. [Start a return](/account/returns)."},
   {"heading":"Contact us","body":"Chat with Lola any time, or reach a person 8 am–10 pm ET at help@afrotransact.com."}]',
 'PUBLISHED', 0),
('shipping', 'ORDERS_DELIVERY', 'Shipping & delivery', 'Carrier shipping, AfroTransact Delivery and pickup.',
 '[{"heading":"Carrier shipping","body":"Sellers and our warehouses ship with USPS, UPS and FedEx. Tracking updates arrive automatically."},
   {"heading":"AfroTransact Delivery","body":"Our own drivers deliver same-day or next-day in Houston, Edison/Iselin and Atlanta, with a photo and a one-time code."},
   {"heading":"Pickup","body":"Collect from an AfroTransact will-call counter, a locker, or a seller’s own shop where the seller offers pickup."}]',
 'PUBLISHED', 10),
('returns', 'RETURNS_REFUNDS', 'Returns & refunds', 'Return items within 30 days and follow your refund.',
 '[{"heading":"Returns","body":"Most items can be returned within 30 days. Food is refundable if damaged or wrong."},
   {"heading":"Start a return","body":"Go to [Returns & refunds](/account/returns), choose the order and the items, and tell us why. We email a label or arrange a pickup."},
   {"heading":"Refunds","body":"Refunds go back to the original payment method once the return is received or the claim is approved. Read the full [Returns & Refunds policy](/legal/returns)."}]',
 'PUBLISHED', 10),
('fulfilled-by-afrotransact', 'SELLING', 'Fulfilled by AfroTransact', 'Store your stock with us and we pick, pack and deliver.',
 '[{"heading":"We ship for you","body":"Send your stock to an AfroTransact fulfilment centre and we pick, pack and ship every order with USPS, UPS and FedEx."},
   {"heading":"Faster delivery","body":"Fulfilled by AfroTransact items qualify for same-day or next-day AfroTransact Delivery in Houston, Edison/Iselin and Atlanta."},
   {"heading":"Get started","body":"[Sell on AfroTransact](/sell), then send an inbound shipment from Seller Center → Inventory & FBA."}]',
 'PUBLISHED', 10),
('advertise', 'SELLING', 'Advertise on AfroTransact', 'Sponsored products and banners for sellers and brands.',
 '[{"heading":"Sponsored products & banners","body":"Sellers and brands buy sponsored products and banner placements with cost-per-click bids and a daily budget, and pay only for clicks. Contact ads@afrotransact.com to start a campaign."}]',
 'PUBLISHED', 20),
('press', 'COMPANY', 'Press', 'Media enquiries.',
 '[{"heading":"Media enquiries","body":"press@afrotransact.com"}]',
 'PUBLISHED', 10);
