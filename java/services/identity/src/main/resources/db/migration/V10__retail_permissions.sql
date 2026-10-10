-- Retail (1P purchasing and receiving) permissions, granted to the platform staff roles that run AfroTransact Retail.
insert into permission (key, module, description) values
    ('retail:read', 'retail', 'View suppliers, items, purchase orders, shipments and receipts'),
    ('retail:items:write', 'retail', 'Maintain the 1P item master (cost, packs, supplier SKUs)'),
    ('retail:suppliers:write', 'retail', 'Create and change suppliers, contacts and terms'),
    ('retail:po:write', 'retail', 'Draft, submit and cancel purchase orders'),
    ('retail:po:approve', 'retail', 'Approve purchase orders (above-threshold approvals need finance)'),
    ('retail:logistics', 'retail', 'Book inbound shipments and maintain ASNs'),
    ('retail:receive', 'retail', 'Receive goods against ASNs and purchase orders'),
    ('retail:finance', 'retail', 'Landed cost, GL journals and three-way match')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.retail_buyer', 'retail:read'), ('platform.retail_buyer', 'retail:items:write'),
    ('platform.retail_buyer', 'retail:suppliers:write'), ('platform.retail_buyer', 'retail:po:write'),
    ('platform.retail_buyer', 'retail:logistics'),
    ('platform.catalog_manager', 'retail:read'), ('platform.catalog_manager', 'retail:items:write'),
    ('platform.operations_manager', 'retail:read'), ('platform.operations_manager', 'retail:po:approve'),
    ('platform.inventory_manager', 'retail:read'), ('platform.inventory_manager', 'retail:receive'),
    ('platform.fulfilment_ops', 'retail:read'), ('platform.fulfilment_ops', 'retail:receive'),
    ('platform.finance', 'retail:read'), ('platform.finance', 'retail:po:approve'), ('platform.finance', 'retail:finance'),
    ('platform.auditor', 'retail:read'),
    ('platform.super_admin', 'retail:read'), ('platform.super_admin', 'retail:items:write'),
    ('platform.super_admin', 'retail:suppliers:write'), ('platform.super_admin', 'retail:po:write'),
    ('platform.super_admin', 'retail:po:approve'), ('platform.super_admin', 'retail:logistics'),
    ('platform.super_admin', 'retail:receive'), ('platform.super_admin', 'retail:finance')
) as r(role_key, permission_key)
on conflict do nothing;
