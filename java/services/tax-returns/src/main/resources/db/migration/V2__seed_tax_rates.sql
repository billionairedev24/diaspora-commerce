-- Seed rate table for the launch states. ILLUSTRATIVE combined rates for development and testing: finance must
-- confirm every rate (and keep them current) before production use; edit through PUT /v1/tax/rates/**.
-- general / grocery / prepared food are STATE rates; tax_local_rate adds county + city + district rates by ZIP
-- (an exact 5-digit ZIP wins over a 3-digit prefix). facilitator = AfroTransact collects and remits.
insert into tax_state_rule (country, state, name, general_rate, grocery_rate, prepared_food_rate, shipping_taxable,
                            facilitator) values
    ('US', 'TX', 'Texas',          0.062500, 0.000000, 0.062500, true,  true),
    ('US', 'NY', 'New York',       0.040000, 0.000000, 0.040000, true,  true),
    ('US', 'NJ', 'New Jersey',     0.066250, 0.000000, 0.066250, true,  true),
    ('US', 'GA', 'Georgia',        0.040000, 0.000000, 0.040000, true,  true),
    ('US', 'MD', 'Maryland',       0.060000, 0.000000, 0.060000, false, true),
    ('US', 'CA', 'California',     0.072500, 0.000000, 0.072500, false, true),
    ('US', 'IL', 'Illinois',       0.062500, 0.000000, 0.062500, false, true),
    ('US', 'MN', 'Minnesota',      0.068750, 0.000000, 0.068750, true,  true),
    ('US', 'DE', 'Delaware',       0.000000, 0.000000, 0.000000, false, false);

insert into tax_local_rate (state, zip_prefix, name, rate, applies_to_grocery) values
    ('TX', '770', 'Houston, TX',            0.020000, false),
    ('TX', '752', 'Dallas, TX',             0.020000, false),
    ('NY', '100', 'New York City, NY',      0.048750, false),
    ('NY', '112', 'Brooklyn, NY',           0.048750, false),
    ('GA', '303', 'Atlanta, GA',            0.049000, false),
    ('CA', '900', 'Los Angeles, CA',        0.025000, false),
    ('IL', '606', 'Chicago, IL',            0.040000, false),
    ('MN', '554', 'Minneapolis, MN',        0.019250, false),
    ('MD', '20910', 'Silver Spring, MD',    0.000000, false);
