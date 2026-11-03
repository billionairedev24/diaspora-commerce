-- V83: plan entitlements are configured per plan in plan.limits (staff edit them in the plan editor):
--   listings         the most live listings, a number or "unlimited" (enforced by offers-service when a listing goes live)
--   aiListingWriter  "true" when the AI listing writer is included
--   lolaTier         ESSENTIALS | GROWTH | PRO — what Lola, the seller assistant, can do
-- There is no limit on shops or users. Seeds follow the plans' marketing text; staff can change any of them.
update plan set limits = limits || '{"listings":"50","aiListingWriter":"false","lolaTier":"ESSENTIALS"}'::jsonb
    where code = 'seller_essentials';
update plan set limits = limits || '{"listings":"1000","aiListingWriter":"true","lolaTier":"GROWTH"}'::jsonb
    where code = 'seller_growth';
update plan set limits = limits || '{"listings":"unlimited","aiListingWriter":"true","lolaTier":"PRO"}'::jsonb
    where code = 'seller_pro';
update plan set limits = limits || '{"listings":"unlimited","aiListingWriter":"false","lolaTier":"ESSENTIALS"}'::jsonb
    where code = 'provider_solo';
update plan set limits = limits || '{"listings":"unlimited","aiListingWriter":"false","lolaTier":"GROWTH"}'::jsonb
    where code = 'provider_studio';
update plan set limits = limits || '{"listings":"unlimited","aiListingWriter":"false","lolaTier":"PRO"}'::jsonb
    where code = 'provider_business';
