-- V84: plans are configuration plus the capabilities they allow (plan.limits), and the plan's feature list is derived from
-- them: only what a plan allows is listed. New capability keys beside V83's listings / aiListingWriter / lolaTier:
--   bulkUpload     "true" when listings can be uploaded in bulk
--   imageLibrary   "true" when the bulk image library is included
--   coupons        "true" when coupon codes are included
--   lolaTier       NONE | ESSENTIALS | GROWTH | PRO (NONE: no assistant)
-- Seeds: Essentials has none of the extras, Growth adds bulk upload, the image library and coupons, Pro has everything.
update plan set limits = limits || '{"bulkUpload":"false","imageLibrary":"false","coupons":"false"}'::jsonb
    where code in ('seller_essentials', 'provider_solo', 'provider_studio', 'provider_business');
update plan set limits = limits || '{"bulkUpload":"true","imageLibrary":"true","coupons":"true"}'::jsonb
    where code in ('seller_growth', 'seller_pro');
update plan set features = '[]'::jsonb;
