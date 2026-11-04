-- Guest back-in-stock alerts: the gateway-bff subscribes a visitor who is not signed in (an email address, no account)
-- with its own client-credentials token. The scope is for service clients only; no role or shopper token carries it.
insert into permission (key, module, description) values
    ('notifications:stock-alerts.guest', 'notifications', 'Subscribe an email address to a back-in-stock alert without an account (service clients only)')
on conflict (key) do nothing;
