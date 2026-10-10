-- AfroTransact fulfilment centres and origin consolidation hubs (design/at-inventory-hub.js WAREHOUSES).
insert into node (id, name, kind, country, timezone, temperature_zones) values
    ('HOU-FC', 'Houston FC', 'FULFILMENT_CENTRE', 'US', 'America/Chicago', '["Ambient", "Chilled", "Hazmat"]'),
    ('EWR-FC', 'Edison FC', 'FULFILMENT_CENTRE', 'US', 'America/New_York', '["Ambient", "Chilled"]'),
    ('ATL-FC', 'Atlanta FC', 'FULFILMENT_CENTRE', 'US', 'America/New_York', '["Ambient"]'),
    ('LAX-FC', 'Los Angeles FC', 'FULFILMENT_CENTRE', 'US', 'America/Los_Angeles', '["Ambient", "Chilled"]'),
    ('TEM-HUB', 'Tema Consolidation Hub', 'ORIGIN_HUB', 'GH', 'Africa/Accra', '["Ambient", "Bonded"]'),
    ('LOS-HUB', 'Lagos Consolidation Hub', 'ORIGIN_HUB', 'NG', 'Africa/Lagos', '["Ambient"]');
