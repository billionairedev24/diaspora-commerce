-- Coverage rows get an optimistic-lock version (bumped on every switch) so staff can edit a country's states and
-- cities in place: a region edit sends the fingerprint of the rows it loaded (If-Match) and is refused when anyone
-- added, removed or switched one of them since. Windows keep their own on/off switch (preorder_window.active), which
-- the master switch (preorder_settings.enabled) never rewrites.
alter table preorder_area add column version bigint not null default 0;

comment on column preorder_window.active is
    'The window''s own switch (Pause / Resume). Live on the storefront = preorder_settings.enabled AND active AND an area on.';
