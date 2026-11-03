-- Developers see every staff console module for incident response (V28): Search analytics follows search:analytics.view
-- (V14), which only Super Admin, Marketing, Catalog Manager, Product Owner and Operations held. Read-only: developers
-- do not get search:synonyms.edit.
insert into role_permission (role_key, permission_key)
values ('platform.developer', 'search:analytics.view')
on conflict do nothing;
