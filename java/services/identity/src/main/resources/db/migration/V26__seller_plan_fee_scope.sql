-- S298: plan fees collected from earnings. The seller-service client asks payments to withhold a plan invoice from an
-- organisation's earnings and to release it again (POST /v1/orgs/{orgId}/plan-fee-withholdings[/{invoice}/release]).
-- Granted to that client only (application-<env>.yml client scopes); no role carries it.
insert into permission (key, module, description) values
    ('payments:plan-fees', 'payments', 'Withhold seller plan fees from earnings and release them (seller service only)')
on conflict (key) do nothing;
