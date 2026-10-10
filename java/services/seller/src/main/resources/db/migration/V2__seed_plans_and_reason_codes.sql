-- Plan catalogue (design/at-plans.js). Quarterly = 3 months less 5 %, annual = 10 months (2 months free); prices
-- per cadence are computed in PlanPricing and exist in Stripe as Prices with these lookup keys.
insert into plan (code, kind, name, tagline, monthly_cents, trial_days, features, sort_order) values
 ('seller_essentials', 'SELLER', 'Seller Essentials', 'Start selling', 0, 0,
  '["Up to 50 listings", "15% referral fee", "Basic storefront", "1 user"]', 10),
 ('seller_growth', 'SELLER', 'Seller Growth', 'Most popular', 3900, 30,
  '["Up to 1,000 listings", "8–12% referral fee by category", "Storefront themes and AI listing writer", "3 users"]', 20),
 ('seller_pro', 'SELLER', 'Seller Pro', 'For established brands', 12900, 14,
  '["Unlimited listings", "6–10% referral fee", "Fulfilled by AfroTransact priority, ad credits, API", "10 users"]', 30),
 ('provider_solo', 'PROVIDER', 'Provider Solo', 'Independent pros', 1900, 30,
  '["1 practitioner", "10% booking fee", "Online booking and deposits", "Public page with 6 photos"]', 10),
 ('provider_studio', 'PROVIDER', 'Provider Studio', 'Most popular', 4900, 30,
  '["Up to 5 practitioners", "8% booking fee", "Waitlist and reminders by SMS", "Public page with 12 photos"]', 20),
 ('provider_business', 'PROVIDER', 'Provider Business', 'Multi-location', 9900, 14,
  '["Unlimited staff", "6% booking fee", "Multiple locations and API", "Public page with 24 photos"]', 30);

insert into plan_price (plan_code, cadence, stripe_lookup_key)
select p.code, c.cadence, p.code || '_' || lower(c.cadence)
from plan p cross join (values ('MONTHLY'), ('QUARTERLY'), ('ANNUAL')) as c (cadence);

-- Review reason codes (design backoffice: application review sheet).
insert into review_reason_code (code, label, applies_to, sort_order) values
 ('ALL_CHECKS_PASSED', 'All automated and document checks passed', 'APPROVE', 10),
 ('PERMIT_MISSING', 'Required permit or licence missing or invalid', 'ANY', 20),
 ('LICENCE_EXPIRED', 'Licence or permit expired', 'ANY', 30),
 ('IDENTITY_MISMATCH', 'Identity verification failed or does not match', 'ANY', 40),
 ('TIN_MISMATCH', 'IRS name/TIN match failed', 'ANY', 50),
 ('DOCUMENT_UNREADABLE', 'Document unreadable, incomplete or wrong type', 'ANY', 60),
 ('INSURANCE_MISSING', 'Proof of insurance missing for a regulated service', 'ANY', 70),
 ('PROHIBITED_CATEGORY', 'Sells or offers a prohibited category', 'ANY', 80),
 ('REPUTATION_RISK', 'Unresolved complaints or reputation risk', 'ANY', 90),
 ('SANCTIONS_HIT', 'Sanctions / PEP screening hit', 'REJECT', 100),
 ('DUPLICATE_ACCOUNT', 'Duplicate of an existing seller account', 'REJECT', 110),
 ('OTHER', 'Other (see note)', 'ANY', 999);
