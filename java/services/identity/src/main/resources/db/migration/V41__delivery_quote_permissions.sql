-- Ask for a delivery quote (listing rules D20; docs/runbooks/delivery-quotes.md): customer care prices deliveries to
-- addresses outside the delivery zones. orders-service owns the case and checks these three permissions.
--   delivery-quotes:view    see the queue, the cases and their timeline
--   delivery-quotes:agree   claim a case, agree an amount (reason required) or decline it
--   delivery-quotes:assign  assign, reassign and take over cases; change SLA, escalation and amount settings
-- The two-person rule is not used here: whoever holds a case decides it, and every decision is audited with a reason.
insert into permission (key, module, description) values
    ('delivery-quotes:view', 'orders', 'See delivery quote requests, who has them and their timeline'),
    ('delivery-quotes:agree', 'orders', 'Claim delivery quote requests, agree an amount or decline (reason required, audited)'),
    ('delivery-quotes:assign', 'orders', 'Assign and take over delivery quote requests; manage their SLA, escalation and amount settings')
on conflict (key) do nothing;

insert into role_permission (role_key, permission_key)
select r.role_key, r.permission_key from (values
    ('platform.super_admin', 'delivery-quotes:view'),
    ('platform.super_admin', 'delivery-quotes:agree'),
    ('platform.super_admin', 'delivery-quotes:assign'),
    -- Customer care agrees quotes.
    ('platform.customer_support', 'delivery-quotes:view'),
    ('platform.customer_support', 'delivery-quotes:agree'),
    -- Operations supervises: sees everything, assigns and takes over, sets the SLA and amount bounds.
    ('platform.operations_manager', 'delivery-quotes:view'),
    ('platform.operations_manager', 'delivery-quotes:assign'),
    ('platform.auditor', 'delivery-quotes:view')
) as r(role_key, permission_key)
where exists (select 1 from role where key = r.role_key)
on conflict do nothing;
