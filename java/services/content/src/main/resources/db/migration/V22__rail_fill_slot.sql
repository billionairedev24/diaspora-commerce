-- Rail fill: a tile that fills the empty columns of a short product rail (home, department and category pages).
-- Staff place it like any placement (schedule, market / ZIP-prefix targeting, audit); the gateway picks one per rail by
-- the rail's department. With an `advertiser` the tile is paid and always labelled Sponsored; without one it is a staff
-- editorial tile. Catalogue row only: no placement is created here (demo content comes from the local seed, never from a migration).
insert into slot (key, name, description, max_items, size_spec, payload_schema, sort_order) values
('rail_fill', 'Rail fill tile',
 'Fills the empty space beside a short product rail with one tile: an advertiser''s promo (labelled Sponsored) or a staff editorial tile. Used only when a rail has fewer products than fit the row and no more relevant product can fill it.',
 12, 'Image 800x800 or 3:2 (WebP/AVIF, at most 150 KB), a real photograph; headline up to 60 characters; text up to 140.',
 '{"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "additionalProperties": false,
   "required": ["headline", "cta", "href"],
   "properties": {
     "advertiser": {"type": "string", "minLength": 1, "maxLength": 120},
     "headline": {"type": "string", "minLength": 1, "maxLength": 60},
     "text": {"type": "string", "maxLength": 140},
     "cta": {"type": "string", "minLength": 1, "maxLength": 40},
     "href": {"type": "string", "minLength": 1, "maxLength": 300, "pattern": "^(/|https://)"},
     "imageUrl": {"type": ["string", "null"], "maxLength": 500, "pattern": "^https://"},
     "departments": {"type": "array", "maxItems": 12, "uniqueItems": true, "items": {"type": "string", "minLength": 1, "maxLength": 60}},
     "skus": {"type": "array", "maxItems": 3, "uniqueItems": true, "items": {"type": "string", "minLength": 1, "maxLength": 64}},
     "maxPerSession": {"type": "integer", "minimum": 1, "maximum": 20},
     "states": {"type": "array", "maxItems": 52, "uniqueItems": true, "items": {"type": "string", "pattern": "^[A-Z]{2}$"}}}}'::jsonb, 46);
