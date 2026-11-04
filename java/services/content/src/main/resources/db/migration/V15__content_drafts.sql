-- Staff authoring of public pages without a developer (docs/runbooks/editing-published-pages.md).
--
-- Legal documents: one working draft per document. Saving a draft never changes what shoppers read; publishing it
-- raises the version (legal_version keeps CURRENT / ARCHIVED only) and needs a person other than the draft's last editor.
create table legal_draft (
    doc_type       varchar(40)  primary key,
    version        varchar(20)  not null,
    effective_date date         not null,
    draft_banner   boolean      not null default true,
    bodies         jsonb        not null,
    needs_review   jsonb        not null default '[]'::jsonb,
    change_summary text,
    base_version   varchar(20)  not null,
    row_version    bigint       not null default 0,
    created_by     varchar(80)  not null,
    created_at     timestamptz  not null,
    updated_by     varchar(80)  not null,
    updated_at     timestamptz  not null
);

-- Help center pages: who changed a page last, one working draft per page, and every published revision (the history
-- the console shows, diffs and restores from).
alter table help_article add column updated_by varchar(80);

create table help_article_draft (
    slug           varchar(60)  primary key references help_article (slug),
    topic          varchar(32)  not null,
    title          varchar(120) not null,
    summary        varchar(300) not null,
    sections       jsonb        not null,
    sort_order     integer      not null default 0,
    change_summary text,
    base_version   bigint       not null,
    row_version    bigint       not null default 0,
    created_by     varchar(80)  not null,
    created_at     timestamptz  not null,
    updated_by     varchar(80)  not null,
    updated_at     timestamptz  not null,
    constraint help_article_draft_topic_ck check (topic in ('ORDERS_DELIVERY', 'RETURNS_REFUNDS', 'ACCOUNT_PAYMENTS', 'SELLING', 'COMPANY'))
);

create table help_article_revision (
    slug           varchar(60)  not null references help_article (slug),
    revision       integer      not null,
    topic          varchar(32)  not null,
    title          varchar(120) not null,
    summary        varchar(300) not null,
    sections       jsonb        not null,
    status         varchar(16)  not null,
    sort_order     integer      not null default 0,
    change_summary text,
    reason         text,
    published_by   varchar(80),
    published_at   timestamptz  not null,
    primary key (slug, revision)
);

-- The pages as they are now are revision 1, so history starts from what shoppers read today.
insert into help_article_revision (slug, revision, topic, title, summary, sections, status, sort_order, change_summary, reason, published_by, published_at)
select slug, 1, topic, title, summary, sections, status, sort_order, 'Initial content', 'Initial load', null, updated_at
from help_article;
