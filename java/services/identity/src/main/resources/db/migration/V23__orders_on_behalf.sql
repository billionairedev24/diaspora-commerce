-- Pre-orders (S272): delivery-runs-service creates customer-service late adds through orders-service
-- (POST /v1/service/orders/late-preorder). Service clients only; no role grants.
insert into permission (key, module, description) values
    ('orders:on-behalf', 'orders', 'Create orders on behalf of a customer (late pre-order adds; service clients only)')
on conflict (key) do nothing;
