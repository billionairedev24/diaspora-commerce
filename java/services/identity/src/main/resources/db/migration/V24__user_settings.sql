-- Back-office and storefront account settings that are not notification switches: job title (work profile) and display
-- preferences (date and number format, default table density). Language and time zone stay on notification_preference,
-- which notifications-service already reads for templates and quiet hours.
create table user_setting (
    user_id        uuid primary key references user_account (id) on delete cascade,
    job_title      varchar(80),
    date_format    varchar(16) not null default 'AUTO'
        check (date_format in ('AUTO', 'MDY', 'DMY', 'YMD')),
    number_format  varchar(16) not null default 'AUTO'
        check (number_format in ('AUTO', 'COMMA_DOT', 'DOT_COMMA', 'SPACE_COMMA')),
    table_density  varchar(16) not null default 'COMFORTABLE'
        check (table_density in ('COMFORTABLE', 'COMPACT')),
    updated_at     timestamptz not null,
    version        integer     not null default 0
);
