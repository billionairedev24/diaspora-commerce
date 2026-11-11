-- Home page ads and composition.
-- New slots: four paid modules (brand banner, sponsored products rail, 2-3 up image ads, advertorial) and `home_block`,
-- the generic rule-based module (deals, spotlights, promo bands, occasions ...). The home body is the placements of
-- home_product_row, display_banner and these slots ordered by priority (highest first); the gateway composes it.
-- Every module may target US states (`states`) besides the placement's own market / ZIP-prefix targeting and schedule.
-- placement_stat counts impressions and clicks of any placement per UTC day (paid modules report through the gateway).

create table placement_stat (
    placement_id varchar(40) not null references placement (id) on delete cascade,
    day          date        not null,
    impressions  bigint      not null default 0,
    clicks       bigint      not null default 0,
    primary key (placement_id, day)
);

-- Rails and banners: more of them fit the composed page; rails may lower or raise the minimum items and target states.
update slot set max_items = 12 where key = 'home_product_row';
update slot set max_items = 6 where key = 'display_banner';

update slot set payload_schema = jsonb_set(payload_schema, '{properties}', (payload_schema -> 'properties') || '{
  "minItems": {"type": "integer", "minimum": 1, "maximum": 24},
  "states": {"type": "array", "maxItems": 52, "uniqueItems": true, "items": {"type": "string", "pattern": "^[A-Z]{2}$"}}}'::jsonb)
where key = 'home_product_row';

update slot set payload_schema = jsonb_set(payload_schema, '{properties}', (payload_schema -> 'properties') || '{
  "states": {"type": "array", "maxItems": 52, "uniqueItems": true, "items": {"type": "string", "pattern": "^[A-Z]{2}$"}}}'::jsonb)
where key = 'display_banner';

insert into slot (key, name, description, max_items, size_spec, payload_schema, sort_order) values
('home_brand_banner', 'Sponsored brand banner',
 'Full-width sponsored banner between product rails: advertiser logo, headline, image, button and an optional strip of up to three products. Always labelled Sponsored.',
 4, 'Image 1500x300 desktop (WebP/AVIF, at most 200 KB); logo 240x80 on transparent background; headline up to 70 characters.',
 '{"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "additionalProperties": false,
   "required": ["advertiser", "headline", "cta", "href"],
   "properties": {
     "advertiser": {"type": "string", "minLength": 1, "maxLength": 120},
     "headline": {"type": "string", "minLength": 1, "maxLength": 70},
     "sub": {"type": "string", "maxLength": 160},
     "cta": {"type": "string", "minLength": 1, "maxLength": 40},
     "href": {"type": "string", "minLength": 1, "maxLength": 300, "pattern": "^(/|https://)"},
     "imageUrl": {"type": ["string", "null"], "maxLength": 500, "pattern": "^https://"},
     "logoUrl": {"type": ["string", "null"], "maxLength": 500, "pattern": "^https://"},
     "tone": {"type": "string", "enum": ["ink", "gold", "green", "sand"]},
     "skus": {"type": "array", "maxItems": 3, "uniqueItems": true, "items": {"type": "string", "minLength": 1, "maxLength": 64}},
     "maxPerSession": {"type": "integer", "minimum": 1, "maximum": 20},
     "states": {"type": "array", "maxItems": 52, "uniqueItems": true, "items": {"type": "string", "pattern": "^[A-Z]{2}$"}}}}'::jsonb, 41),
('home_sponsored_products', 'Sponsored products rail',
 'A rail of products sellers promote, from the sponsored-products auction for the words or department below. Cards carry the Sponsored label; clicks are charged like sponsored search.',
 3, 'No creative: sellers'' campaigns (Seller Center, Advertising) supply the products. Shown only with two or more eligible products.',
 '{"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "additionalProperties": false,
   "required": ["title"],
   "properties": {
     "title": {"type": "string", "minLength": 1, "maxLength": 60},
     "href": {"type": "string", "minLength": 1, "maxLength": 300, "pattern": "^(/|https://)"},
     "query": {"type": "string", "maxLength": 200},
     "department": {"type": "string", "maxLength": 60},
     "states": {"type": "array", "maxItems": 52, "uniqueItems": true, "items": {"type": "string", "pattern": "^[A-Z]{2}$"}}}}'::jsonb, 42),
('home_ad_row', 'Display ad row (2 or 3 up)',
 'A row of two or three image ads with a compact Ad label. Each ad links out to the advertiser''s page or shop.',
 4, 'Two ads 740x300 or three ads 490x300 (WebP/AVIF, at most 150 KB each); alt text required.',
 '{"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "additionalProperties": false,
   "required": ["ads"],
   "properties": {
     "ads": {"type": "array", "minItems": 2, "maxItems": 3, "items": {"type": "object", "additionalProperties": false,
       "required": ["imageUrl", "alt", "href"],
       "properties": {
         "imageUrl": {"type": "string", "minLength": 1, "maxLength": 500, "pattern": "^https://"},
         "alt": {"type": "string", "minLength": 1, "maxLength": 160},
         "href": {"type": "string", "minLength": 1, "maxLength": 300, "pattern": "^(/|https://)"},
         "advertiser": {"type": "string", "minLength": 1, "maxLength": 120}}}},
     "maxPerSession": {"type": "integer", "minimum": 1, "maximum": 20},
     "states": {"type": "array", "maxItems": 52, "uniqueItems": true, "items": {"type": "string", "pattern": "^[A-Z]{2}$"}}}}'::jsonb, 43),
('home_advertorial', 'Advertorial (From our partners)',
 'A paid editorial card: image, headline and two lines of text, labelled Sponsored with the advertiser''s name.',
 4, 'Image 600x400 (WebP/AVIF, at most 120 KB); headline up to 80 characters; text up to 200.',
 '{"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object", "additionalProperties": false,
   "required": ["advertiser", "headline", "text", "href"],
   "properties": {
     "advertiser": {"type": "string", "minLength": 1, "maxLength": 120},
     "headline": {"type": "string", "minLength": 1, "maxLength": 80},
     "text": {"type": "string", "minLength": 1, "maxLength": 200},
     "cta": {"type": "string", "minLength": 1, "maxLength": 40},
     "href": {"type": "string", "minLength": 1, "maxLength": 300, "pattern": "^(/|https://)"},
     "imageUrl": {"type": ["string", "null"], "maxLength": 500, "pattern": "^https://"},
     "maxPerSession": {"type": "integer", "minimum": 1, "maximum": 20},
     "states": {"type": "array", "maxItems": 52, "uniqueItems": true, "items": {"type": "string", "pattern": "^[A-Z]{2}$"}}}}'::jsonb, 44);

insert into slot (key, name, description, max_items, size_spec, payload_schema, sort_order)
select 'home_block', 'Home module',
 'A rule-based home module placed in the page order: deals of the day, pre-orders near you, services near you, community and seller spotlights, membership and sign-in bands, shop by occasion, personal rails. Hidden by itself when it has nothing to show.',
 30, 'Optional image 1500x300 for bands; occasion cells 300x300.',
 jsonb_build_object('$schema', 'https://json-schema.org/draft/2020-12/schema', 'type', 'object', 'additionalProperties', false,
   'required', jsonb_build_array('type'),
   'properties', '{
     "type": {"type": "string", "enum": ["deals", "based-on-orders", "recently-viewed", "preorders", "services-near-you", "community-spotlight", "seller-spotlight", "membership-promo", "signin-promo", "occasions"]},
     "title": {"type": "string", "minLength": 1, "maxLength": 80},
     "sub": {"type": "string", "maxLength": 200},
     "cta": {"type": "string", "minLength": 1, "maxLength": 40},
     "href": {"type": "string", "minLength": 1, "maxLength": 300, "pattern": "^(/|https://)"},
     "tone": {"type": "string", "enum": ["ink", "gold", "green", "sand"]},
     "imageUrl": {"type": ["string", "null"], "maxLength": 500, "pattern": "^https://"},
     "department": {"type": "string", "maxLength": 60},
     "query": {"type": "string", "maxLength": 200},
     "community": {"type": "string", "maxLength": 40},
     "limit": {"type": "integer", "minimum": 1, "maximum": 24},
     "minItems": {"type": "integer", "minimum": 1, "maximum": 24},
     "endsAt": {"type": "string", "maxLength": 40},
     "audience": {"type": "string", "enum": ["all", "signed-in", "signed-out"]},
     "states": {"type": "array", "maxItems": 52, "uniqueItems": true, "items": {"type": "string", "pattern": "^[A-Z]{2}$"}},
     "cells": {"type": "array", "minItems": 1, "maxItems": 6, "items": {}}}'::jsonb),
 45;

-- Occasion cells are the same cell shape as home_tiles (fixed image, or a product rule the gateway borrows a photo from).
update slot set payload_schema = jsonb_set(payload_schema, '{properties,cells,items}',
    (select payload_schema #> '{properties,cells,items}' from slot where key = 'home_tiles'))
where key = 'home_block';
