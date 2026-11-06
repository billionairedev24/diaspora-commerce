-- Location-aware home rules.
-- A home product rail may carry a rule (`kind`, with optional `department`, `maxDays`, `community`) instead of only a
-- query or a SKU list, and a tile cell's `source` accepts the same new kinds: near-you (ships from the shopper's city,
-- state or region), deliverable-fast (promised within maxDays to the shopper's ZIP) and community. The BFF resolves them
-- for the shopper's deliver-to place. Additive: existing payloads stay valid.
update slot set payload_schema = jsonb_set(
    jsonb_set(payload_schema, '{properties}', (payload_schema -> 'properties') || '{
      "kind": {"type": "string", "enum": ["search", "department-bestsellers", "fulfilled-fastest", "near-you", "deliverable-fast", "community"]},
      "department": {"type": "string", "maxLength": 60},
      "maxDays": {"type": "integer", "minimum": 1, "maximum": 14},
      "community": {"type": "string", "maxLength": 40}}'::jsonb),
    '{anyOf}', '[{"required": ["query"]}, {"required": ["skus"]}, {"required": ["kind"]}]'::jsonb)
where key = 'home_product_row';

update slot set payload_schema = jsonb_set(
    jsonb_set(payload_schema, '{properties,cells,items,properties,source,properties}',
              (payload_schema #> '{properties,cells,items,properties,source,properties}') || '{
      "maxDays": {"type": "integer", "minimum": 1, "maximum": 14},
      "community": {"type": "string", "maxLength": 40}}'::jsonb),
    '{properties,cells,items,properties,source,properties,kind,enum}',
    '["search", "department-bestsellers", "fulfilled-fastest", "near-you", "deliverable-fast", "community", "atins"]'::jsonb)
where key = 'home_tiles';
