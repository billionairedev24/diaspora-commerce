-- Payments checks live that a payout requester still holds the owner or finance role in the organisation (the 5-minute
-- token window is closed for money actions). It asks the seller service, which owns the member list, with its own
-- minimal-scope service client grant. Scope for service clients only; no role or shopper token carries it.
insert into permission (key, module, description) values
    ('sellers:membership.read', 'seller', 'Read one user''s live role in one organisation (payments-service client only; payout requests)')
on conflict (key) do nothing;
