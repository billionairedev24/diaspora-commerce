-- Payment links for late adds last 1–24 hours (orders-service's late-preorder API accepts at most 24); settings saved
-- while the console still allowed up to 168 hours are capped so they load under the stricter rule.
update preorder_settings
set payment = jsonb_set(payment, '{lateAdd,linkExpiryHours}', '24'::jsonb)
where (payment #>> '{lateAdd,linkExpiryHours}')::int > 24;
