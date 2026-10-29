-- Texas sales tax rates aligned with the Texas Comptroller (not legal advice; finance should re-verify quarterly).
--
-- State (Tax Code §151.051): 6.25% on taxable tangible personal property and taxable services.
-- Grocery (Tax Code §151.314; Pub 96-280): unprepared food for home consumption — state rate 0%.
-- Prepared / ready-to-eat food (Rule §3.293): taxed at the general state rate.
-- Local (Tax Code §321.207): when state tax does not apply (exempt grocery), local tax does not apply either;
--   applies_to_grocery stays false on all TX local rows below.
-- Local add-ons below are COMBINED city + district + MTA fractions only (state 0.0625 is separate in tax_state_rule).
-- Sources: Comptroller sales tax overview; 2025 Q4 EDI rate file taxrates254.txt (city + MTA/SPD columns).
--
-- Combined examples: Houston/Dallas/Austin/Fort Worth general goods ≈ 8.25% (0.0625 + 0.02).
-- San Antonio (Bexar) ≈ 8.0% (0.0625 + 0.0175).

insert into tax_state_rule (country, state, name, general_rate, grocery_rate, prepared_food_rate, shipping_taxable,
                            facilitator)
values ('US', 'TX', 'Texas', 0.062500, 0.000000, 0.062500, true, true)
on conflict (country, state) do update set
    name = excluded.name,
    general_rate = excluded.general_rate,
    grocery_rate = excluded.grocery_rate,
    prepared_food_rate = excluded.prepared_food_rate,
    shipping_taxable = excluded.shipping_taxable,
    facilitator = excluded.facilitator,
    updated_at = now(),
    version = tax_state_rule.version + 1;

insert into tax_local_rate (state, zip_prefix, name, rate, applies_to_grocery) values
    ('TX', '770', 'Houston area (770*)', 0.020000, false),
    ('TX', '77019', 'Houston, TX 77019', 0.020000, false),
    ('TX', '77042', 'Houston, TX 77042', 0.020000, false),
    ('TX', '771', 'Houston area (771*)', 0.020000, false),
    ('TX', '772', 'Houston area (772*)', 0.020000, false),
    ('TX', '773', 'North Houston / suburbs (773*)', 0.020000, false),
    ('TX', '774', 'West Houston / Katy (774*)', 0.020000, false),
    ('TX', '775', 'Galveston / Bay area (775*)', 0.020000, false),
    ('TX', '752', 'Dallas (752*)', 0.020000, false),
    ('TX', '750', 'North Dallas / Plano (750*)', 0.020000, false),
    ('TX', '751', 'South Dallas (751*)', 0.020000, false),
    ('TX', '787', 'Austin (787*)', 0.020000, false),
    ('TX', '786', 'Austin suburbs (786*)', 0.020000, false),
    ('TX', '782', 'San Antonio (782*)', 0.017500, false),
    ('TX', '781', 'San Antonio north (781*)', 0.017500, false),
    ('TX', '761', 'Fort Worth (761*)', 0.020000, false)
on conflict (state, zip_prefix) do update set
    name = excluded.name,
    rate = excluded.rate,
    applies_to_grocery = excluded.applies_to_grocery,
    updated_at = now();
