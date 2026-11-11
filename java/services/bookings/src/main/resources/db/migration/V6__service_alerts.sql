-- "Tell me when one joins": a customer (signed in or not) asks to be emailed when a provider starts serving their
-- place. One open alert per email, ZIP and category; it is closed (notified_at) when the first match is emailed.
create table service_alert (
    id          uuid primary key,
    email       varchar(254) not null,
    zip         char(5)      not null,
    city        varchar(80)  not null,
    state       char(2)      not null,
    lat         double precision not null,
    lon         double precision not null,
    category    varchar(60),
    locale      varchar(5)   not null default 'en',
    created_at  timestamptz  not null,
    notified_at timestamptz
);
create unique index service_alert_open on service_alert (lower(email), zip, coalesce(category, ''))
    where notified_at is null;
create index service_alert_zip on service_alert (zip) where notified_at is null;
