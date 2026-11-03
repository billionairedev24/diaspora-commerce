-- Books get the fields a book listing needs (the product form shows exactly the attributes of the product type, so a
-- book no longer looks like a branded consumer product). Author, publisher, language and pages already exist; the ISBN
-- stays the identifier (identifier_rule = 'ISBN') and the format (hardcover / paperback) the variation theme.
-- All additions are optional so existing book listings stay valid. Idempotent: keys that exist are not added twice.
update product_type p set attributes = p.attributes || coalesce((
    select jsonb_agg(n.a order by n.ord)
    from jsonb_array_elements('[
        {"key": "subtitle", "label": "Subtitle", "options": [], "required": false, "multiline": false, "units": [], "section": "DETAILS"},
        {"key": "series", "label": "Series", "options": [], "required": false, "multiline": false, "units": [], "section": "DETAILS", "help": "Name of the series or collection, if any"},
        {"key": "edition", "label": "Edition", "options": [], "required": false, "multiline": false, "units": [], "section": "DETAILS", "help": "e.g. 2nd edition, revised"},
        {"key": "pubyear", "label": "Publication year", "options": [], "required": false, "multiline": false, "kind": "NUMBER", "units": [], "section": "DETAILS"},
        {"key": "readingAge", "label": "Reading age", "options": ["Adult", "Young adult", "Ages 9-12", "Ages 6-8", "Ages 3-5", "Baby and toddler"], "required": false, "multiline": false, "kind": "ENUM", "units": [], "section": "DETAILS"}
    ]'::jsonb) with ordinality as n(a, ord)
    where not exists (select 1 from jsonb_array_elements(p.attributes) x where x ->> 'key' = n.a ->> 'key')), '[]'::jsonb)
where p.code = 'book';
