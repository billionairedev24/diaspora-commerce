-- S237: seller reminder before the claim response deadline and notice when it lapsed. Each row becomes the next
-- version of its key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('claim.seller_response_reminder', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.seller_response_reminder' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Reminder: respond to claim {{.reference}} by {{.dueDate}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your response is due soon</h1>
<p>Hi {{.firstName}}, claim <strong>{{.reference}}</strong> on order <strong>{{.orderNumber}}</strong> still needs your response.</p>
<p>Please respond by <strong>{{.dueDate}}</strong>. If we don''t hear from you, our team will decide the claim without your input.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Respond now</a></p>
</body></html>',
 'Hi {{.firstName}}, claim {{.reference}} on order {{.orderNumber}} still needs your response by {{.dueDate}}: {{.actionUrl}}'),
('claim.seller_response_reminder', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.seller_response_reminder' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: reminder, claim {{.reference}} needs your response by {{.dueDate}}. {{.actionUrl}} Reply STOP to opt out.'),
('claim.seller_response_lapsed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.seller_response_lapsed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Response window closed for claim {{.reference}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">The response window has closed</h1>
<p>Hi {{.firstName}}, we didn''t receive a response to claim <strong>{{.reference}}</strong> on order <strong>{{.orderNumber}}</strong> in time.</p>
<p>Our team will now decide the claim based on the information we have. We''ll let you know the outcome.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View the claim</a></p>
</body></html>',
 'Hi {{.firstName}}, the response window for claim {{.reference}} on order {{.orderNumber}} has closed. Our team will decide it and let you know: {{.actionUrl}}');
