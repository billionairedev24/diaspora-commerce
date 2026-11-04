-- Sales tax needs to know what an item is. One plain question per type whose answer changes the tax; the tax-returns
-- service turns the answer into a tax code (docs/engineering/tax-classification-design.md). The option texts must match
-- tax-returns' tax_classification_rule rows (V5). Not required yet: an unanswered item is taxed as general goods and
-- shows up in the staff tax review queue. Make it required once existing products have been answered.

update product_type set attributes = attributes || jsonb_build_array(jsonb_build_object(
        'key', 'tax_class', 'label', 'What best describes this food? (used for sales tax)',
        'options', jsonb_build_array('Packaged, shelf-stable', 'Fresh fruit or vegetables', 'Frozen, not prepared',
                                     'Hot or ready-to-eat', 'Candy or sweets', 'Soft drink or sweetened drink',
                                     'Bottled water', 'Dietary supplement'),
        'required', false, 'multiline', false))
where code = 'grocery' and not exists (select 1 from jsonb_array_elements(attributes) a where a ->> 'key' = 'tax_class');

update product_type set attributes = attributes || jsonb_build_array(jsonb_build_object(
        'key', 'tax_class', 'label', 'What best describes this food? (used for sales tax)',
        'options', jsonb_build_array('Raw meat or fish', 'Fresh fruit or vegetables', 'Frozen, not prepared',
                                     'Cooked, hot or ready-to-eat'),
        'required', false, 'multiline', false))
where code = 'perishable' and not exists (select 1 from jsonb_array_elements(attributes) a where a ->> 'key' = 'tax_class');

update product_type set attributes = attributes || jsonb_build_array(jsonb_build_object(
        'key', 'tax_class', 'label', 'Is this a medicated product? (used for sales tax)',
        'options', jsonb_build_array('Cosmetic or skincare', 'Medicated (sunscreen, dandruff, acne)'),
        'required', false, 'multiline', false))
where code = 'beauty' and not exists (select 1 from jsonb_array_elements(attributes) a where a ->> 'key' = 'tax_class');
