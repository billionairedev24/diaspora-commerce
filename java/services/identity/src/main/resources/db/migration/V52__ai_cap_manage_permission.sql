-- Settings > AI and MCP spend cap becomes editable (docs/runbooks/ai-spend-and-mcp-access.md):
--   ai:cap.manage  change the gateway-wide daily AI spend cap (ai-gateway PUT /v1/ai/spend-cap, bounded by the deployment
--                  ceiling AI_GLOBAL_DAILY_COST_MAX_USD). Viewing the cap and today's spend stays under ai:manage.
-- Granted to Super Admin and Developer only: money-affecting and deploy-adjacent. Product Owner keeps ai:manage (view) and can
-- be given this one in Settings > Roles and permissions (docs/runbooks/add-a-permission-to-a-role.md). It is deliberately not
-- an MCP scope: no MCP tool may change the cap. Idempotent.
insert into permission (key, module, description) values
    ('ai:cap.manage', 'platform', 'Change the daily AI spend cap (USD per day for all AI features together; 0 switches AI off)')
on conflict (key) do update set module = excluded.module, description = excluded.description;

insert into role_permission (role_key, permission_key) values
    ('platform.super_admin', 'ai:cap.manage'),
    ('platform.developer', 'ai:cap.manage')
on conflict do nothing;
