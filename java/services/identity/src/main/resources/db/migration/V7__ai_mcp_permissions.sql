-- AI gateway and MCP server permissions (also client-credentials / authorization-code scopes).
--   ai:use       call the AI gateway (Lola, AI-assisted search and listing tools)
--   ai:triage    AI-assisted triage of applications, listings, disputes and support cases
--   mcp:shopper  MCP tools that act for a shopper (search, cart, orders)
--   mcp:seller   MCP tools that act for a seller / provider organisation
--   mcp:staff    MCP tools for platform staff
insert into permission (key, module, description) values
    ('ai:use', 'platform', 'Use AI assistants and AI-assisted tools'),
    ('ai:triage', 'platform', 'AI-assisted triage of applications, listings, disputes and support cases'),
    ('mcp:shopper', 'platform', 'Let an AI agent (MCP) shop and manage orders for this account'),
    ('mcp:seller', 'platform', 'Let an AI agent (MCP) act for this seller or provider organisation'),
    ('mcp:staff', 'platform', 'Let an AI agent (MCP) use staff tools')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    -- ai:use: organisation owners and every staff role (drivers excluded)
    ('seller.owner', 'ai:use'), ('provider.owner', 'ai:use'),
    ('platform.super_admin', 'ai:use'), ('platform.operations_manager', 'ai:use'),
    ('platform.trust_safety', 'ai:use'), ('platform.finance', 'ai:use'),
    ('platform.customer_support', 'ai:use'), ('platform.marketing', 'ai:use'),
    ('platform.catalog_manager', 'ai:use'), ('platform.retail_buyer', 'ai:use'),
    ('platform.fulfilment_ops', 'ai:use'), ('platform.inventory_manager', 'ai:use'),
    ('platform.dispatcher', 'ai:use'), ('platform.product_owner', 'ai:use'),
    ('platform.developer', 'ai:use'), ('platform.auditor', 'ai:use'),
    -- ai:triage: trust & safety and the staff who triage cases
    ('platform.super_admin', 'ai:triage'), ('platform.trust_safety', 'ai:triage'),
    ('platform.operations_manager', 'ai:triage'), ('platform.customer_support', 'ai:triage'),
    -- mcp:shopper: every account
    ('account.customer', 'mcp:shopper'),
    -- mcp:seller: organisation owners and their admins / managers
    ('seller.owner', 'mcp:seller'), ('seller.admin', 'mcp:seller'),
    ('provider.owner', 'mcp:seller'), ('provider.manager', 'mcp:seller'),
    -- mcp:staff: every staff role (drivers excluded)
    ('platform.super_admin', 'mcp:staff'), ('platform.operations_manager', 'mcp:staff'),
    ('platform.trust_safety', 'mcp:staff'), ('platform.finance', 'mcp:staff'),
    ('platform.customer_support', 'mcp:staff'), ('platform.marketing', 'mcp:staff'),
    ('platform.catalog_manager', 'mcp:staff'), ('platform.retail_buyer', 'mcp:staff'),
    ('platform.fulfilment_ops', 'mcp:staff'), ('platform.inventory_manager', 'mcp:staff'),
    ('platform.dispatcher', 'mcp:staff'), ('platform.product_owner', 'mcp:staff'),
    ('platform.developer', 'mcp:staff'), ('platform.auditor', 'mcp:staff')
) as r(role_key, permission_key)
on conflict do nothing;
