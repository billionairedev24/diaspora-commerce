-- S270–S275: pre-order templates (en). delivery-runs publishes preorder.comms.due {windowId, kind} (ANNOUNCE,
-- REMIND_48H, LAST_CALL_6H, CLOSED, OUT_FOR_DELIVERY) and preorder.late_order.added on at.delivery-runs.events;
-- orders publishes orders.order.placed (pre-order package → "You're in") and orders.preorder.delivery_missed.
-- The ingest derives name, where, opensDisplay / closesDisplay / deliversDisplay, doorFee / pickupFee (the window's
-- effective fees from the payment settings, never literal amounts), pickupWindow, smsCopy (the window's SMS copy with
-- {link} expanded), emailUrl / smsUrl / pushUrl (?src=<channel>&po=<CODE>&cr=<slot>) and the audience member's
-- unsubscribeUrl. Announcement, reminder, last call and waitlist are reminders (preferences and quiet hours apply);
-- the rest are transactional. SMS stay in one GSM-7 segment. Each row becomes the next version of its
-- key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('preorder.announce', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 '{{.name}} is open: order by {{.closesDisplay}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">{{if .headline}}{{.headline}}{{else}}Pre-order is open in {{.where}}{{end}}</h1>
<p>Hi {{.firstName}}, <strong>{{.name}}</strong> is open. Order by <strong>{{.closesDisplay}}</strong> and we deliver on <strong>{{.deliversDisplay}}</strong>.</p>
<table style="border-collapse:collapse;font-size:14px">
{{if .doorFee}}<tr><td style="padding:4px 16px 4px 0;color:#555">Door delivery</td><td style="padding:4px 0">{{.doorFee}} flat</td></tr>{{end}}
{{if .pickupFee}}<tr><td style="padding:4px 16px 4px 0;color:#555">Pickup</td><td style="padding:4px 0">{{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}</td></tr>{{end}}
</table>
<p>You''re charged at checkout and can change or cancel pre-order items for a full refund until the pre-order closes.</p>
<p><a href="{{.emailUrl}}" style="color:#111;font-weight:700">Pre-order now</a></p>
{{if .unsubscribeUrl}}<p style="color:#555;font-size:12px">You get this because you asked for pre-order alerts in {{.where}}. <a href="{{.unsubscribeUrl}}" style="color:#555">Unsubscribe</a></p>{{end}}
</body></html>',
 'Hi {{.firstName}}, {{.name}} is open. Order by {{.closesDisplay}} and we deliver on {{.deliversDisplay}}.
{{if .doorFee}}Door delivery: {{.doorFee}} flat.
{{end}}{{if .pickupFee}}Pickup: {{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}.
{{end}}You''re charged at checkout and can change or cancel pre-order items for a full refund until the pre-order closes.
Pre-order now: {{.emailUrl}}{{if .unsubscribeUrl}}

Unsubscribe from {{.where}} pre-order alerts: {{.unsubscribeUrl}}{{end}}'),
('preorder.announce', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'en' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 '{{.smsCopy}}'),
('preorder.announce', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'en' AND t.channel = 'push'), 'reminder',
 'Pre-order open in {{.where}}',
 '',
 'Order by {{.closesDisplay}}, delivered {{.deliversDisplay}}.{{if .doorFee}} Door delivery {{.doorFee}}.{{end}}'),

('preorder.reminder', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.reminder' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 '48 hours left: {{.name}} closes {{.closesDisplay}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">48 hours left to pre-order</h1>
<p>Hi {{.firstName}}, <strong>{{.name}}</strong> closes on <strong>{{.closesDisplay}}</strong>. Everything ordered by then arrives on <strong>{{.deliversDisplay}}</strong>{{if .doorFee}} with door delivery for {{.doorFee}}{{end}}{{if .pickupFee}}{{if .doorFee}} or{{else}} with{{end}} pickup ({{.pickupFee}}){{end}}.</p>
<p><a href="{{.emailUrl}}" style="color:#111;font-weight:700">Finish your pre-order</a></p>
{{if .unsubscribeUrl}}<p style="color:#555;font-size:12px"><a href="{{.unsubscribeUrl}}" style="color:#555">Unsubscribe from {{.where}} pre-order alerts</a></p>{{end}}
</body></html>',
 'Hi {{.firstName}}, {{.name}} closes on {{.closesDisplay}}. Everything ordered by then arrives on {{.deliversDisplay}}{{if .doorFee}} with door delivery for {{.doorFee}}{{end}}{{if .pickupFee}}{{if .doorFee}} or{{else}} with{{end}} pickup ({{.pickupFee}}){{end}}.
Finish your pre-order: {{.emailUrl}}{{if .unsubscribeUrl}}

Unsubscribe: {{.unsubscribeUrl}}{{end}}'),
('preorder.reminder', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.reminder' AND t.locale = 'en' AND t.channel = 'push'), 'reminder',
 '48 hours left to pre-order',
 '',
 '{{.name}} closes {{.closesDisplay}}. Delivered {{.deliversDisplay}}.'),

('preorder.last_call', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.last_call' AND t.locale = 'en' AND t.channel = 'push'), 'reminder',
 'Last call: {{.name}}',
 '',
 'Pre-order closes {{.closesDisplay}}. Delivered {{.deliversDisplay}}.'),
('preorder.last_call', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.last_call' AND t.locale = 'en' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: last call! {{.name}} closes {{.closesDisplay}}. {{.smsUrl}} Reply STOP to opt out'),

('preorder.closed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.closed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{.name}} is closed: delivering {{.deliversDisplay}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your pre-order is locked in</h1>
<p>Hi {{.firstName}}, <strong>{{.name}}</strong> is now closed. We deliver your pre-order on <strong>{{.deliversDisplay}}</strong>{{if .pickupWindow}} (pickup {{.pickupWindow}}){{end}}.</p>
<p>If you opted in to texts, we''ll message you when it''s out for delivery.</p>
<p><a href="{{.emailUrl}}" style="color:#111;font-weight:700">View your pre-order</a></p>
</body></html>',
 'Hi {{.firstName}}, {{.name}} is now closed. We deliver your pre-order on {{.deliversDisplay}}{{if .pickupWindow}} (pickup {{.pickupWindow}}){{end}}. If you opted in to texts, we''ll message you when it''s out for delivery. {{.emailUrl}}'),

('preorder.out_for_delivery', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.out_for_delivery' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: {{.name}} is out for delivery today{{if .deliveryWindow}}, {{.deliveryWindow}}{{end}}. {{.smsUrl}} Reply STOP to opt out'),
('preorder.out_for_delivery', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.out_for_delivery' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Your pre-order is out for delivery',
 '',
 '{{.name}} arrives today{{if .deliveryWindow}}, {{.deliveryWindow}}{{end}}.'),

('preorder.confirmed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.confirmed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'You''re in: {{.name}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">You''re in: {{.name}}</h1>
<p>Hi {{.firstName}}, thanks for your pre-order.</p>
<table style="border-collapse:collapse;font-size:14px">
<tr><td style="padding:4px 16px 4px 0;color:#555">Pre-order #</td><td style="padding:4px 0">{{.preorderNumber}}</td></tr>
<tr><td style="padding:4px 16px 4px 0;color:#555">Delivered</td><td style="padding:4px 0">{{.deliversDisplay}}</td></tr>
<tr><td style="padding:4px 16px 4px 0;color:#555">{{.fulfilmentText}}</td><td style="padding:4px 0">{{if .doorFee}}{{.doorFee}}{{else}}{{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}{{end}}</td></tr>
<tr><td style="padding:4px 16px 4px 0;color:#555">Charged</td><td style="padding:4px 0"><strong>{{.total}}</strong></td></tr>
</table>
<p>{{if .nowNote}}{{.nowNote}} {{end}}Pre-order items can be changed or cancelled for a full refund until {{.cancelUntil}}. If you''ve opted in, we''ll text you on delivery day.</p>
<p><a href="{{.emailUrl}}" style="color:#111;font-weight:700">View your pre-order</a> &middot; <a href="{{.orderUrl}}" style="color:#111">Your orders</a></p>
</body></html>',
 'Hi {{.firstName}}, you''re in: {{.name}}.
Pre-order #: {{.preorderNumber}}
Delivered: {{.deliversDisplay}}
{{.fulfilmentText}}: {{if .doorFee}}{{.doorFee}}{{else}}{{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}{{end}}
Charged: {{.total}}
{{if .nowNote}}{{.nowNote}} {{end}}Pre-order items can be changed or cancelled for a full refund until {{.cancelUntil}}. If you''ve opted in, we''ll text you on delivery day.
{{.emailUrl}}'),

('preorder.waitlist.opened', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.waitlist.opened' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'Pre-order is open in {{.where}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">We deliver to {{.where}}</h1>
<p>Hi {{.firstName}}, you asked us to tell you when a pre-order opens in {{.where}}. <strong>{{.name}}</strong> is open until <strong>{{.closesDisplay}}</strong>, delivered on <strong>{{.deliversDisplay}}</strong>.</p>
<p>{{if .doorFee}}Door delivery is {{.doorFee}} flat.{{end}}{{if .pickupFee}} Pickup is {{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}.{{end}}</p>
<p><a href="{{.emailUrl}}" style="color:#111;font-weight:700">See what''s in the pre-order</a></p>
{{if .unsubscribeUrl}}<p style="color:#555;font-size:12px"><a href="{{.unsubscribeUrl}}" style="color:#555">Leave the waitlist</a></p>{{end}}
</body></html>',
 'Hi {{.firstName}}, you asked us to tell you when a pre-order opens in {{.where}}. {{.name}} is open until {{.closesDisplay}}, delivered on {{.deliversDisplay}}.{{if .doorFee}} Door delivery is {{.doorFee}} flat.{{end}}{{if .pickupFee}} Pickup is {{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}.{{end}}
See what''s in the pre-order: {{.emailUrl}}{{if .unsubscribeUrl}}

Leave the waitlist: {{.unsubscribeUrl}}{{end}}'),

('preorder.late_order.payment_link', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.late_order.payment_link' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: pay {{.total}} for pre-order {{.number}}, delivered {{.deliversDisplay}}: {{.paymentUrl}} Reply STOP to opt out'),

('preorder.delivery_missed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.delivery_missed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'We missed your delivery: {{.name}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">We''re sorry, we missed your delivery</h1>
<p>Hi {{.firstName}}, we couldn''t deliver <strong>{{.name}}</strong> (order {{.orderNumber}}) on {{.missedDisplay}}. Choose what works for you:</p>
{{if .newDateUrl}}<p><a href="{{.newDateUrl}}" style="color:#111;font-weight:700">Deliver it on {{.newDateDisplay}}</a></p>{{end}}
{{if .refundUrl}}<p><a href="{{.refundUrl}}" style="color:#111;font-weight:700">Get a full refund of {{.total}}</a></p>{{end}}
<p>Questions? Reply to this email or see <a href="{{.orderUrl}}" style="color:#111">your orders</a>.</p>
</body></html>',
 'Hi {{.firstName}}, we''re sorry: we couldn''t deliver {{.name}} (order {{.orderNumber}}) on {{.missedDisplay}}. Choose what works for you:
{{if .newDateUrl}}Deliver it on {{.newDateDisplay}}: {{.newDateUrl}}
{{end}}{{if .refundUrl}}Get a full refund of {{.total}}: {{.refundUrl}}
{{end}}Your orders: {{.orderUrl}}');
