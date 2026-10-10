-- Read-only access to fee schedules for service clients that show fees to users (gateway-bff Seller Center fees page).
insert into permission (key, module, description) values
    ('payments:fees.read', 'payments', 'Read fee schedules and effective fees (service clients only)')
on conflict (key) do nothing;
