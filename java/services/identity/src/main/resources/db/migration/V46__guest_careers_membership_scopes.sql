-- F14: the two guest paths of the gateway-bff (a signed-out visitor uploading a resume, or reading the membership offer) used a
-- client-credentials token requested with NO scope, which identity issues with every scope the client holds. They now ask for one
-- minimal scope each. Scopes are for service clients only; no role or shopper token carries them.
insert into permission (key, module, description) values
    ('media:career-resume', 'media', 'Upload and complete a career resume for a visitor who is not signed in (gateway-bff service client only)'),
    ('payments:membership-offer.read', 'payments', 'Read the published membership tiers for a visitor who is not signed in (gateway-bff service client only)')
on conflict (key) do nothing;
