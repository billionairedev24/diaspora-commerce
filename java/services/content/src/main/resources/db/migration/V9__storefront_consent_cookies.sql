-- S192: the storefront's own consent and attribution cookies belong in the Cookie Notice table.
-- at_consent stores the visitor's choice (strictly necessary: without it the banner would reappear on every page);
-- at_attr keeps the pre-order campaign touch for 30 days only with analytics or advertising consent (S273).
insert into cookie_definition (name, provider, purpose, duration, category) values
    ('at_consent', 'AfroTransact', 'Remembers your cookie choices and "Do not sell or share"', '12 months', 'necessary'),
    ('at_attr', 'AfroTransact', 'Which campaign link brought you to a pre-order (last touch)', '30 days', 'analytics')
on conflict (name) do nothing;
