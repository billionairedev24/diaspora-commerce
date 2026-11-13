-- S417 and S420: staff-editable home settings (append-only versions with who, why and what changed) and the community
-- registry (canonical key, labels per locale, aliases, optional editorial image).
create table home_settings_version (
    version           int          primary key,
    dynamic           varchar(10)  check (dynamic in ('off', 'shadow', 'on')),
    modules           jsonb        not null,
    caps              jsonb        not null,
    department_labels jsonb        not null default '{}'::jsonb,
    reason            varchar(300) not null check (length(btrim(reason)) >= 3),
    changed_by        varchar(80)  not null,
    changed_at        timestamptz  not null,
    revert_of         int          references home_settings_version (version),
    changes           jsonb        not null default '[]'::jsonb
);

create table community_entry (
    key        varchar(60)  primary key check (key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    labels     jsonb        not null,
    aliases    jsonb        not null default '[]'::jsonb,
    image_url  varchar(500) check (image_url is null or image_url like 'https://%'),
    updated_at timestamptz  not null,
    updated_by varchar(80)  not null
);
