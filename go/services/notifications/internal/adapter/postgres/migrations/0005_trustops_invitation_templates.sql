-- Templates for S230 (trust-ops claims, moderation and seller enforcement), organisation invitations (S231), phone
-- verification (S234), suspension / reinstatement wording for seller.payouts.* / seller.listings.* (reason SUSPENDED /
-- REINSTATED), booking.balance_unpaid and payments.intent.action_required. Each row becomes the next version of its
-- key/locale/channel, so it also lands on top of versions staff created.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('identity.verification.phone', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'identity.verification.phone' AND t.locale = 'en' AND t.channel = 'sms'), 'security',
 '',
 '',
 'Your AfroTransact code is {{.code}}. It expires in {{.expiresInMinutes}} min. Never share this code.'),
('org.invitation', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'org.invitation' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{.inviterName}} invited you to join {{.orgName}} on AfroTransact',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">You''re invited to {{.orgName}}</h1>
<p>{{.inviterName}} invited you to join <strong>{{.orgName}}</strong> on AfroTransact as <strong>{{.roleName}}</strong>.</p>
<p>The invitation expires in {{.expiresInDays}} days. If you don''t have an AfroTransact account yet, you can create one from the link.</p>
<p>If you weren''t expecting this, you can ignore this email.</p>
<p><a href="{{.acceptUrl}}" style="color:#111;font-weight:700">Accept the invitation</a></p>
</body></html>',
 '{{.inviterName}} invited you to join {{.orgName}} on AfroTransact as {{.roleName}}. Accept within {{.expiresInDays}} days: {{.acceptUrl}} If you weren''t expecting this, ignore this email.'),
('seller.payouts.paused.suspended', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.payouts.paused.suspended' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your payouts are on hold while your account is suspended',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Payouts on hold</h1>
<p>Hi {{.firstName}}, because your seller account is suspended (reason: {{.reasonText}}), we''ve put your payouts on hold. Your earnings are kept safe and will be released if your account is reinstated.</p>
{{if .note}}<p>Note from our Trust and Safety team: {{.note}}</p>{{end}}
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View account health</a></p>
</body></html>',
 'Hi {{.firstName}}, your payouts are on hold because your seller account is suspended (reason: {{.reasonText}}). Earnings are kept safe until a decision.{{if .note}} Note: {{.note}}{{end}} {{.actionUrl}}'),
('seller.listings.hidden.suspended', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.listings.hidden.suspended' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your listings are hidden while your account is suspended',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your listings are hidden</h1>
<p>Hi {{.firstName}}, because your seller account is suspended (reason: {{.reasonText}}), customers can''t see or buy your listings and services for now.</p>
{{if .note}}<p>Note from our Trust and Safety team: {{.note}}</p>{{end}}
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View account health</a></p>
</body></html>',
 'Hi {{.firstName}}, your listings are hidden because your seller account is suspended (reason: {{.reasonText}}).{{if .note}} Note: {{.note}}{{end}} {{.actionUrl}}'),
('seller.payouts.resumed.reinstated', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.payouts.resumed.reinstated' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your account is reinstated: payouts are back on',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Payouts resumed</h1>
<p>Good news, {{.firstName}}! Your seller account has been reinstated, so your payouts are active again and held earnings will go out on your next payout.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View account health</a></p>
</body></html>',
 'Good news, {{.firstName}}! Your seller account is reinstated and payouts are active again. {{.actionUrl}}'),
('seller.listings.restored.reinstated', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.listings.restored.reinstated' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your account is reinstated: listings are live again',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">You''re back in business</h1>
<p>Hi {{.firstName}}, your seller account has been reinstated and your listings are visible to customers again.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View account health</a></p>
</body></html>',
 'Hi {{.firstName}}, your seller account is reinstated and your listings are live again. {{.actionUrl}}'),
('booking.balance_unpaid', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.balance_unpaid' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Action needed: balance for {{.serviceName}} on {{.startsAt}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">We couldn''t charge your balance</h1>
<p>Hi {{.firstName}}, we tried to charge the <strong>{{.balance}}</strong> balance for <strong>{{.serviceName}}</strong> with {{.providerName}} on {{.startsAt}} (ref {{.bookingReference}}) to your saved card, but it didn''t go through.</p>
<p>Please update your card or confirm the payment with your bank so your booking stays on track.</p>
<p><a href="{{.manageUrl}}" style="color:#111;font-weight:700">Update payment</a></p>
</body></html>',
 'Hi {{.firstName}}, we couldn''t charge the {{.balance}} balance for {{.serviceName}} with {{.providerName}} on {{.startsAt}} (ref {{.bookingReference}}). Please update your card or authenticate the payment: {{.manageUrl}}'),
('booking.balance_unpaid', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.balance_unpaid' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Payment needed',
 '',
 'We couldn''t charge the {{.balance}} balance for {{.serviceName}}. Update your card or authenticate the payment.'),
('payment.authentication_required', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.authentication_required' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Authentication needed for your {{.amount}} payment',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Please confirm your payment</h1>
<p>Hi {{.firstName}}, your bank needs you to confirm the <strong>{{.amount}}</strong> payment for {{.reference}} before we can complete it.</p>
<p>It only takes a moment. Until you confirm, the payment stays on hold.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Confirm payment</a></p>
</body></html>',
 'Hi {{.firstName}}, your bank needs you to confirm the {{.amount}} payment for {{.reference}}. Confirm it here: {{.actionUrl}}'),
('payment.authentication_required', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.authentication_required' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Authentication needed',
 '',
 'Confirm your {{.amount}} payment for {{.reference}} with your bank.'),
('claim.opened', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.opened' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'We received your claim {{.reference}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your claim is open</h1>
<p>Hi {{.firstName}}, we received your A-to-z claim <strong>{{.reference}}</strong> ({{.claimType}}) for order {{.orderNumber}}.</p>
<p>The seller has until {{.dueDate}} to respond. After that our team reviews it and we''ll email you the decision.</p>
<p><a href="{{.claimsUrl}}" style="color:#111;font-weight:700">View your orders</a></p>
</body></html>',
 'Hi {{.firstName}}, we received your claim {{.reference}} ({{.claimType}}) for order {{.orderNumber}}. The seller has until {{.dueDate}} to respond; we''ll email you the decision. {{.claimsUrl}}'),
('claim.opened', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.opened' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Claim received',
 '',
 'Claim {{.reference}} for order {{.orderNumber}} is open. We''ll keep you posted.'),
('claim.seller_response_due', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.seller_response_due' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Respond by {{.dueDate}}: claim {{.reference}} on order {{.orderNumber}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">A customer opened a claim</h1>
<p>Hi {{.firstName}}, a customer opened an A-to-z claim <strong>{{.reference}}</strong> ({{.claimType}}, {{.amount}}) on order <strong>{{.orderNumber}}</strong>.</p>
<p>Please respond by <strong>{{.dueDate}}</strong> with your side and any evidence. Claims without a response go to our team for a decision.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Respond now</a></p>
</body></html>',
 'Hi {{.firstName}}, a customer opened claim {{.reference}} ({{.claimType}}, {{.amount}}) on order {{.orderNumber}}. Respond by {{.dueDate}}: {{.actionUrl}}'),
('claim.seller_response_due', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.seller_response_due' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: claim {{.reference}} on order {{.orderNumber}} needs your response by {{.dueDate}}. {{.actionUrl}} Reply STOP to opt out.'),
('claim.decided', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.decided' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Decision on your claim {{.reference}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your claim has a decision</h1>
<p>Hi {{.firstName}}, we''ve reviewed claim <strong>{{.reference}}</strong> for order {{.orderNumber}}.</p>
<p>{{.outcomeText}}</p>
<p><a href="{{.claimsUrl}}" style="color:#111;font-weight:700">View your orders</a></p>
</body></html>',
 'Hi {{.firstName}}, we''ve reviewed claim {{.reference}} for order {{.orderNumber}}. {{.outcomeText}} {{.claimsUrl}}'),
('claim.decided', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.decided' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Claim decided',
 '',
 'Claim {{.reference}}: {{.outcomeText}}'),
('claim.decided.seller', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.decided.seller' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Claim {{.reference}} on order {{.orderNumber}} was decided',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Claim decided</h1>
<p>Hi {{.firstName}}, our team decided claim <strong>{{.reference}}</strong> ({{.claimType}}) on order {{.orderNumber}}.</p>
<p>{{.sellerOutcomeText}}</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View orders</a></p>
</body></html>',
 'Hi {{.firstName}}, claim {{.reference}} ({{.claimType}}) on order {{.orderNumber}} was decided. {{.sellerOutcomeText}} {{.actionUrl}}'),
('claim.withdrawn', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.withdrawn' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Claim {{.reference}} was withdrawn',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Claim withdrawn</h1>
<p>Hi {{.firstName}}, the customer withdrew claim <strong>{{.reference}}</strong> on order {{.orderNumber}}. No action is needed.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View orders</a></p>
</body></html>',
 'Hi {{.firstName}}, the customer withdrew claim {{.reference}} on order {{.orderNumber}}. No action is needed. {{.actionUrl}}'),
('listing.rejected', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'listing.rejected' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your listing "{{.title}}" wasn''t approved',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Listing not approved</h1>
<p>Hi {{.firstName}}, we reviewed <strong>{{.title}}</strong> and couldn''t approve it (reason: {{.reasonText}}).</p>
{{if .note}}<p>Note from our Trust and Safety team: {{.note}}</p>{{end}}
<p>You can edit the listing and submit it again.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Edit your listings</a></p>
</body></html>',
 'Hi {{.firstName}}, your listing "{{.title}}" wasn''t approved (reason: {{.reasonText}}).{{if .note}} Note: {{.note}}{{end}} Edit and resubmit: {{.actionUrl}}'),
('listing.taken_down', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'listing.taken_down' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your listing "{{.title}}" was taken down',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Listing taken down</h1>
<p>Hi {{.firstName}}, after a review we removed <strong>{{.title}}</strong> from the storefront (reason: {{.reasonText}}). Customers can no longer see or buy it.</p>
{{if .note}}<p>Note from our Trust and Safety team: {{.note}}</p>{{end}}
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Review your listings</a></p>
</body></html>',
 'Hi {{.firstName}}, your listing "{{.title}}" was taken down after a review (reason: {{.reasonText}}).{{if .note}} Note: {{.note}}{{end}} {{.actionUrl}}'),
('listing.taken_down', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'listing.taken_down' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: your listing "{{.title}}" was taken down ({{.reasonText}}). Details: {{.actionUrl}} Reply STOP to opt out.'),
('seller.suspended', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.suspended' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your AfroTransact seller account is suspended',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your account is suspended</h1>
<p>Hi {{.firstName}}, our Trust and Safety team has suspended your seller account (reason: {{.reasonText}}).</p>
<p>While it''s suspended, your listings are hidden and payouts are on hold. Your earnings are kept safe.</p>
{{if .note}}<p>Note from our Trust and Safety team: {{.note}}</p>{{end}}
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View account health</a></p>
</body></html>',
 'Hi {{.firstName}}, your seller account is suspended (reason: {{.reasonText}}). Listings are hidden and payouts are on hold.{{if .note}} Note: {{.note}}{{end}} {{.actionUrl}}'),
('seller.suspended', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.suspended' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: your seller account is suspended ({{.reasonText}}). Listings are hidden and payouts held. {{.actionUrl}} Reply STOP to opt out.'),
('seller.reinstated', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.reinstated' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your AfroTransact seller account is reinstated',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Welcome back</h1>
<p>Good news, {{.firstName}}! Your seller account has been reinstated. Your listings return and payouts resume unless a separate payment-account requirement still holds them.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View account health</a></p>
</body></html>',
 'Good news, {{.firstName}}! Your seller account is reinstated. Listings return and payouts resume unless a payment-account requirement still holds them. {{.actionUrl}}');
