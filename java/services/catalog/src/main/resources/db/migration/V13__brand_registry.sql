-- S326 brand registry: brands are rows scoped to the taxonomy (a fashion brand is never offered for a book), typed-in
-- brands start PENDING until staff review them, and whether a product type asks for a brand at all, what it calls it and
-- whether it is required is read from the product type's `brand` attribute (kind BRAND), not from code.

-- The comparison key of a brand name: lower case, accents folded, spaces and punctuation removed, so "Nike", "NIKE",
-- "Levi's" and "Levis" collide. Mirrors BrandNames.normalise in Java (the legacy backfill in V14 relies on it).
create function brand_key(name text) returns text language sql immutable as $$
    select regexp_replace(translate(lower(name), 'ÀÁÂÃÄÅÇÈÉÊËÌÍÎÏÑÒÓÔÕÖÙÚÛÜÝàáâãäåçèéêëìíîïñòóôõöùúûüýÿĀāĂăĄąĆćĈĉĊċČčĎďĒēĔĕĖėĘęĚěĜĝĞğĠġĢģĤĥĨĩĪīĬĭĮįİĴĵĶķĹĺĻļĽľŃńŅņŇňŌōŎŏŐőŔŕŖŗŘřŚśŜŝŞşŠšŢţŤťŨũŪūŬŭŮůŰűŲųŴŵŶŷŸŹźŻżŽžƠơƯưǍǎǏǐǑǒǓǔǕǖǗǘǙǚǛǜǞǟǠǡǦǧǨǩǪǫǬǭǰǴǵǸǹǺǻȀȁȂȃȄȅȆȇȈȉȊȋȌȍȎȏȐȑȒȓȔȕȖȗȘșȚțȞȟȦȧȨȩȪȫȬȭȮȯȰȱȲȳḀḁḂḃḄḅḆḇḈḉḊḋḌḍḎḏḐḑḒḓḔḕḖḗḘḙḚḛḜḝḞḟḠḡḢḣḤḥḦḧḨḩḪḫḬḭḮḯḰḱḲḳḴḵḶḷḸḹḺḻḼḽḾḿṀṁṂṃṄṅṆṇṈṉṊṋṌṍṎṏṐṑṒṓṔṕṖṗṘṙṚṛṜṝṞṟṠṡṢṣṤṥṦṧṨṩṪṫṬṭṮṯṰṱṲṳṴṵṶṷṸṹṺṻṼṽṾṿẀẁẂẃẄẅẆẇẈẉẊẋẌẍẎẏẐẑẒẓẔẕẖẗẘẙẠạẢảẤấẦầẨẩẪẫẬậẮắẰằẲẳẴẵẶặẸẹẺẻẼẽẾếỀềỂểỄễỆệỈỉỊịỌọỎỏỐốỒồỔổỖỗỘộỚớỜờỞởỠỡỢợỤụỦủỨứỪừỬửỮữỰựỲỳỴỵỶỷỸỹ', 'aaaaaaceeeeiiiinooooouuuuyaaaaaaceeeeiiiinooooouuuuyyaaaaaaccccccccddeeeeeeeeeegggggggghhiiiiiiiiijjkkllllllnnnnnnoooooorrrrrrssssssssttttuuuuuuuuuuuuwwyyyzzzzzzoouuaaiioouuuuuuuuuuaaaaggkkoooojggnnaaaaaaeeeeiiiioooorrrruuuusstthhaaeeooooooooyyaabbbbbbccddddddddddeeeeeeeeeeffgghhhhhhhhhhiiiikkkkkkllllllllmmmmmmnnnnnnnnoooooooopppprrrrrrrrssssssssssttttttttuuuuuuuuuuvvvvwwwwwwwwwwxxxxyyzzzzzzhtwyaaaaaaaaaaaaaaaaaaaaaaaaeeeeeeeeeeeeeeeeiiiioooooooooooooooooooooooouuuuuuuuuuuuuuyyyyyyyy'), '[[:punct:][:space:]‘’“”´–—•·]+', '', 'g')
$$;

create table brand (
    id              uuid primary key,
    name            varchar(120) not null,
    normalized_name varchar(120) not null,
    status          varchar(20)  not null check (status in ('APPROVED', 'PENDING', 'REJECTED', 'MERGED_INTO', 'RETIRED')),
    merged_into_id  uuid references brand (id),
    created_by_org  varchar(80),
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    version         bigint       not null default 0,
    decided_by      varchar(120),
    decided_at      timestamptz,
    decision_reason text,
    check ((status = 'MERGED_INTO') = (merged_into_id is not null))
);
create index brand_normalized on brand (normalized_name);
create index brand_status on brand (status, created_at);
create index brand_created_by_org on brand (created_by_org) where created_by_org is not null;

-- Where a brand applies: a department, a category or a product type; it covers everything below that node.
create table brand_scope (
    brand_id uuid not null references brand (id) on delete cascade,
    node_id  uuid not null references category (id),
    primary key (brand_id, node_id)
);
create index brand_scope_node on brand_scope (node_id);

-- Products keep the brand id next to the display name (attributes.brand) and an unbranded reason when they have none.
create index product_brand_id on product ((attributes ->> 'brand_id')) where attributes ? 'brand_id';

alter table taxonomy_audit drop constraint taxonomy_audit_subject_kind_check;
alter table taxonomy_audit add constraint taxonomy_audit_subject_kind_check
    check (subject_kind in ('NODE', 'PRODUCT_TYPE', 'REQUEST', 'BRAND'));

-- Brand per product type. Fashion asks for a brand or label (a no-brand choice satisfies it), beauty and electronics
-- require one, groceries only ask, books have none (the publisher is their imprint), crafts ask for the maker.
update product_type set attributes = (
    select jsonb_agg(case when e ->> 'key' = 'brand' then '{"key": "brand", "label": "Brand or label", "options": ["UNBRANDED", "HANDMADE"], "required": true, "multiline": false, "kind": "BRAND", "units": [], "section": "DETAILS", "help": "The label on the garment. Choose \"No brand\" or \"Handmade\" if it has none.", "applies": true}'::jsonb else e end order by o)
    from jsonb_array_elements(attributes) with ordinality as x(e, o))
where code in ('dress', 'top', 'headwrap', 'menwear', 'kids') and exists (select 1 from jsonb_array_elements(attributes) y where y ->> 'key' = 'brand');
update product_type set attributes = (
    select jsonb_agg(case when e ->> 'key' = 'brand' then '{"key": "brand", "label": "Mill or brand", "options": ["UNBRANDED", "HANDMADE"], "required": false, "multiline": false, "kind": "BRAND", "units": [], "section": "DETAILS", "help": "The mill or print house named on the selvedge, if any.", "applies": true}'::jsonb else e end order by o)
    from jsonb_array_elements(attributes) with ordinality as x(e, o))
where code in ('fabric') and exists (select 1 from jsonb_array_elements(attributes) y where y ->> 'key' = 'brand');
update product_type set attributes = (
    select jsonb_agg(case when e ->> 'key' = 'brand' then '{"key": "brand", "label": "Brand", "options": ["UNBRANDED"], "required": false, "multiline": false, "kind": "BRAND", "units": [], "section": "DETAILS", "help": "The brand on the package. Leave it empty or choose \"No brand\" for loose or unlabelled produce.", "applies": true}'::jsonb else e end order by o)
    from jsonb_array_elements(attributes) with ordinality as x(e, o))
where code in ('grocery', 'perishable') and exists (select 1 from jsonb_array_elements(attributes) y where y ->> 'key' = 'brand');
update product_type set attributes = (
    select jsonb_agg(case when e ->> 'key' = 'brand' then '{"key": "brand", "label": "Brand", "options": [], "required": true, "multiline": false, "kind": "BRAND", "units": [], "section": "DETAILS", "help": "The brand printed on the product. Regulated products must show their brand or responsible business.", "applies": true}'::jsonb else e end order by o)
    from jsonb_array_elements(attributes) with ordinality as x(e, o))
where code in ('beauty', 'hair') and exists (select 1 from jsonb_array_elements(attributes) y where y ->> 'key' = 'brand');
update product_type set attributes = (
    select jsonb_agg(case when e ->> 'key' = 'brand' then '{"key": "brand", "label": "Maker or artisan", "options": ["HANDMADE", "UNBRANDED"], "required": false, "multiline": false, "kind": "BRAND", "units": [], "section": "DETAILS", "help": "Who made it: a studio, a brand or the artisan''s name.", "applies": true}'::jsonb else e end order by o)
    from jsonb_array_elements(attributes) with ordinality as x(e, o))
where code in ('home') and exists (select 1 from jsonb_array_elements(attributes) y where y ->> 'key' = 'brand');
update product_type set attributes = (
    select jsonb_agg(case when e ->> 'key' = 'brand' then '{"key": "brand", "label": "Brand", "options": ["UNBRANDED"], "required": false, "multiline": false, "kind": "BRAND", "units": [], "section": "DETAILS", "help": "The brand, if the product has one.", "applies": true}'::jsonb else e end order by o)
    from jsonb_array_elements(attributes) with ordinality as x(e, o))
where code in ('custom') and exists (select 1 from jsonb_array_elements(attributes) y where y ->> 'key' = 'brand');

-- Books carry no brand: drop the definition if a database ever had one.
update product_type set attributes = (select coalesce(jsonb_agg(e order by o), '[]'::jsonb)
    from jsonb_array_elements(attributes) with ordinality as x(e, o) where e ->> 'key' <> 'brand')
where code = 'book';

-- Consumer electronics: a brand is required and so is the model number (what shoppers and warranty claims go by).
insert into product_type (code, label, identifier_rule, attributes, themes, traits)
values ('electronics', 'Consumer electronics', 'GTIN', '[{"key": "brand", "label": "Brand", "options": [], "required": true, "multiline": false, "kind": "BRAND", "units": [], "section": "DETAILS", "help": "The manufacturer named on the product, e.g. Samsung or Tecno.", "applies": true},
    {"key": "model", "label": "Model number", "options": [], "required": true, "multiline": false, "kind": "TEXT", "units": [], "section": "DETAILS", "help": "Printed on the product label or box, e.g. SM-A546B"},
    {"key": "voltage", "label": "Voltage", "options": ["110-120V (US)", "220-240V", "Dual voltage", "Battery or USB powered"], "required": true, "multiline": false, "kind": "ENUM", "units": [], "section": "DETAILS", "help": "Check it suits the country the item is delivered to"},
    {"key": "warranty", "label": "Warranty", "options": [], "required": false, "multiline": false, "kind": "NUMBER", "units": ["months", "years"], "section": "DETAILS"}]'::jsonb,
    '[{"name": "Color", "dimensions": [{"name": "Color", "options": ["Black", "White", "Silver", "Gold", "Blue"]}]}, {"name": "Storage", "dimensions": [{"name": "Storage", "options": ["64 GB", "128 GB", "256 GB", "512 GB"]}]}]'::jsonb,
    '{}'::jsonb)
on conflict (code) do nothing;

-- A curated starter list, approved and scoped to where each brand is really sold (staff extend it in the console).
create temporary table seed_brand (id uuid not null default gen_random_uuid(), name text not null, scopes text[] not null)
    on commit drop;
insert into seed_brand (name, scopes) values
    ('Nike', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Adidas', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Puma', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Levi''s', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('H&M', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Zara', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Uniqlo', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Gap', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Ralph Lauren', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Tommy Hilfiger', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Calvin Klein', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Under Armour', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Hanes', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Fruit of the Loom', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Champion', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('New Balance', array['fashion-women', 'fashion-men', 'fashion-kids']),
    ('Vlisco', array['fashion-fabrics']),
    ('Woodin', array['fashion-fabrics']),
    ('Uniwax', array['fashion-fabrics']),
    ('GTP', array['fashion-fabrics']),
    ('Maggi', array['groceries']),
    ('Knorr', array['groceries']),
    ('Indomie', array['groceries']),
    ('Golden Penny', array['groceries']),
    ('Dangote', array['groceries']),
    ('Honeywell', array['groceries']),
    ('Tilda', array['groceries']),
    ('Heinz', array['groceries']),
    ('Nestlé', array['groceries']),
    ('Kellogg''s', array['groceries']),
    ('Lipton', array['groceries']),
    ('Twinings', array['groceries']),
    ('Nescafé', array['groceries']),
    ('Milo', array['groceries']),
    ('Peak', array['groceries']),
    ('Palmer''s', array['beauty-skin-care']),
    ('Nivea', array['beauty-skin-care']),
    ('Dove', array['beauty-skin-care']),
    ('Vaseline', array['beauty-skin-care']),
    ('CeraVe', array['beauty-skin-care']),
    ('Olay', array['beauty-skin-care']),
    ('Cantu', array['beauty-hair-care']),
    ('Dark & Lovely', array['beauty-hair-care']),
    ('Carol''s Daughter', array['beauty-hair-care']),
    ('Mielle Organics', array['beauty-hair-care']),
    ('Camille Rose', array['beauty-hair-care']),
    ('Pantene', array['beauty-hair-care']),
    ('Eco Style', array['beauty-hair-care']),
    ('SheaMoisture', array['beauty-skin-care', 'beauty-hair-care']),
    ('L''Oréal Paris', array['beauty-skin-care', 'beauty-hair-care']),
    ('Garnier', array['beauty-skin-care', 'beauty-hair-care']),
    ('X-pression', array['beauty-hair-care-braiding-hair']),
    ('Sensationnel', array['beauty-hair-care-braiding-hair']),
    ('Freetress', array['beauty-hair-care-braiding-hair']),
    ('Outre', array['beauty-hair-care-braiding-hair']),
    ('Janet Collection', array['beauty-hair-care-braiding-hair']);
insert into brand (id, name, normalized_name, status, created_by_org, created_at, updated_at)
select id, name, brand_key(name), 'APPROVED', null, now(), now() from seed_brand;
insert into brand_scope (brand_id, node_id)
select distinct s.id, c.id from seed_brand s cross join lateral unnest(s.scopes) as u(code) join category c on c.code = u.code;
