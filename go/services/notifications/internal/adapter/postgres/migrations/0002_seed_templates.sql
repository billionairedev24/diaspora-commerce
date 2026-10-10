-- Seed templates (version 1, en). New versions are created through POST /v1/templates.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('identity.verification', 'en', 'email', 1, 'security',
 'Your AfroTransact verification code: {{.code}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Confirm your email</h1>
<p>Enter this code to finish setting up your AfroTransact account:</p>
<p style="font-size:32px;letter-spacing:6px;font-weight:700">{{.code}}</p>
<p>The code expires in {{.expiresInMinutes}} minutes. If you didn''t ask for it, you can ignore this email.</p>
</body></html>',
 'Your AfroTransact verification code is {{.code}}. It expires in {{.expiresInMinutes}} minutes. If you didn''t ask for it, ignore this email.'),
('identity.verification', 'en', 'sms', 1, 'security', '', '',
 'AfroTransact: your verification code is {{.code}}. It expires in {{.expiresInMinutes}} min. Never share this code.'),

('order.confirmed', 'en', 'email', 1, 'transactional',
 'Order {{.orderNumber}} confirmed',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Thanks for your order, {{.firstName}}!</h1>
<p>Order <strong>{{.orderNumber}}</strong> is confirmed. Total: <strong>{{.total}}</strong>.</p>
<p>Estimated delivery: {{.estimatedDelivery}}.</p>
<p><a href="{{.orderUrl}}">View your order</a></p>
</body></html>',
 'Thanks for your order, {{.firstName}}! Order {{.orderNumber}} is confirmed. Total: {{.total}}. Estimated delivery: {{.estimatedDelivery}}. View it at {{.orderUrl}}'),
('order.confirmed', 'en', 'sms', 1, 'transactional', '', '',
 'AfroTransact: order {{.orderNumber}} confirmed ({{.total}}). Arriving {{.estimatedDelivery}}. Reply STOP to opt out.'),
('order.confirmed', 'en', 'push', 1, 'transactional', 'Order confirmed', '',
 'Order {{.orderNumber}} is confirmed. Arriving {{.estimatedDelivery}}.'),

('shipment.out_for_delivery', 'en', 'email', 1, 'transactional',
 'Your order {{.orderNumber}} is out for delivery',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your order is on its way today</h1>
<p>Order <strong>{{.orderNumber}}</strong> is out for delivery and should arrive {{.eta}}.</p>
<p>Give your driver this handover code when they arrive:</p>
<p style="font-size:32px;letter-spacing:6px;font-weight:700">{{.handoverCode}}</p>
</body></html>',
 'Order {{.orderNumber}} is out for delivery and should arrive {{.eta}}. Your handover code is {{.handoverCode}} - give it to the driver.'),
('shipment.out_for_delivery', 'en', 'sms', 1, 'transactional', '', '',
 'AfroTransact: order {{.orderNumber}} is out for delivery, ETA {{.eta}}. Handover code: {{.handoverCode}}. Reply STOP to opt out.'),
('shipment.out_for_delivery', 'en', 'push', 1, 'transactional', 'Out for delivery', '',
 'Order {{.orderNumber}} arrives {{.eta}}. Handover code {{.handoverCode}}.'),

('payout.requirement_due', 'en', 'email', 1, 'reminder',
 'Action needed: update your payout details by {{.dueDate}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Keep your payouts flowing</h1>
<p>Hi {{.businessName}}, our payments partner Stripe needs more information to keep your account in compliance:</p>
<p><strong>{{.requirement}}</strong></p>
<p>Please complete it by <strong>{{.dueDate}}</strong>, or payouts will be paused.</p>
<p><a href="{{.actionUrl}}">Update details in Seller Central</a></p>
</body></html>',
 'Hi {{.businessName}}, Stripe needs more information to keep your payouts active: {{.requirement}}. Please complete it by {{.dueDate}} at {{.actionUrl}} or payouts will be paused.'),
('payout.requirement_due', 'en', 'sms', 1, 'reminder', '', '',
 'AfroTransact: action needed by {{.dueDate}} to keep payouts active ({{.requirement}}). Update at {{.actionUrl}} Reply STOP to opt out.'),

('booking.confirmed', 'en', 'email', 1, 'transactional',
 'Booking confirmed: {{.serviceName}} on {{.startsAt}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">You''re booked, {{.firstName}}!</h1>
<p><strong>{{.serviceName}}</strong> with {{.providerName}}</p>
<p>{{.startsAt}} at {{.location}}</p>
<p>Booking reference: {{.bookingReference}}</p>
<p><a href="{{.manageUrl}}">Manage or cancel your booking</a></p>
</body></html>',
 'You''re booked, {{.firstName}}! {{.serviceName}} with {{.providerName}}, {{.startsAt}} at {{.location}}. Reference {{.bookingReference}}. Manage: {{.manageUrl}}'),
('booking.confirmed', 'en', 'sms', 1, 'transactional', '', '',
 'AfroTransact: {{.serviceName}} with {{.providerName}} is confirmed for {{.startsAt}}. Ref {{.bookingReference}}. Reply STOP to opt out.'),
('booking.confirmed', 'en', 'push', 1, 'transactional', 'Booking confirmed', '',
 '{{.serviceName}} with {{.providerName}}, {{.startsAt}}.');
