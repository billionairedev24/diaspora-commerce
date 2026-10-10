-- Templates for the producer events mapped in internal/app/rules.go (S217) and back-in-stock alerts (S211).
-- Each row becomes the next version of its key/locale/channel, so it also lands on top of versions staff created.
-- order.confirmed and shipment.out_for_delivery get a new version: the real producer payloads carry no first name,
-- formatted total or handover code, so the new version uses the fields the ingest derives (see README, Templates).

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('account.welcome', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'account.welcome' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Welcome to AfroTransact, {{.firstName}}!',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Welcome home, {{.firstName}}!</h1>
<p>Your AfroTransact account is ready. Shop the flavours, fashion and services you love from home, delivered to your door.</p>
<p><a href="{{.startUrl}}" style="color:#111;font-weight:700">Get started</a></p>
</body></html>',
 'Welcome home, {{.firstName}}! Your AfroTransact account is ready. Get started: {{.startUrl}}'),
('order.confirmed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'order.confirmed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Order {{.orderNumber}} is confirmed',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Thanks for your order, {{.firstName}}!</h1>
<p>Order <strong>{{.orderNumber}}</strong> is confirmed: {{.itemCount}} item(s), <strong>{{.total}}</strong>.</p>
<p>Arriving {{.estimatedDelivery}}. We''ll let you know as soon as it ships.</p>
<p><a href="{{.orderUrl}}" style="color:#111;font-weight:700">View your order</a></p>
</body></html>',
 'Thanks for your order, {{.firstName}}! Order {{.orderNumber}} is confirmed ({{.itemCount}} item(s), {{.total}}). Arriving {{.estimatedDelivery}}. View it: {{.orderUrl}}'),
('order.confirmed', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'order.confirmed' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: order {{.orderNumber}} confirmed ({{.total}}). Arriving {{.estimatedDelivery}}. Reply STOP to opt out.'),
('order.confirmed', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'order.confirmed' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Order confirmed',
 '',
 'Order {{.orderNumber}} ({{.total}}) is confirmed. Arriving {{.estimatedDelivery}}.'),
('order.cancelled', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'order.cancelled' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Order {{.orderNumber}} has been cancelled',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your order was cancelled</h1>
<p>Hi {{.firstName}}, order <strong>{{.orderNumber}}</strong> has been cancelled. Any payment taken will be refunded to your original payment method.</p>
<p><a href="{{.orderUrl}}" style="color:#111;font-weight:700">View your orders</a></p>
</body></html>',
 'Hi {{.firstName}}, order {{.orderNumber}} has been cancelled. Any payment taken will be refunded to your original payment method. Details: {{.orderUrl}}'),
('order.cancelled', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'order.cancelled' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Order cancelled',
 '',
 'Order {{.orderNumber}} was cancelled. Any payment taken will be refunded.'),
('shipment.shipped', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.shipped' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Good news: order {{.orderNumber}} is on its way',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">It''s on its way, {{.firstName}}!</h1>
<p>Part or all of order <strong>{{.orderNumber}}</strong> has shipped and should arrive by {{.eta}}.</p>
<p>{{.trackingLine}}</p>
<p><a href="{{.orderUrl}}" style="color:#111;font-weight:700">Track your order</a></p>
</body></html>',
 'It''s on its way, {{.firstName}}! Order {{.orderNumber}} has shipped and should arrive by {{.eta}}. {{.trackingLine}} Track it: {{.orderUrl}}'),
('shipment.shipped', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.shipped' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Your order shipped',
 '',
 'Order {{.orderNumber}} is on its way. Arriving by {{.eta}}.'),
('shipment.out_for_delivery', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.out_for_delivery' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Order {{.orderNumber}} is out for delivery',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Arriving today</h1>
<p>Hi {{.firstName}}, order <strong>{{.orderNumber}}</strong> is out for delivery.</p>
<p>If your delivery needs a handover code, you''ll find it on the order page in the app. Only share it with your driver.</p>
<p><a href="{{.orderUrl}}" style="color:#111;font-weight:700">Track your delivery</a></p>
</body></html>',
 'Hi {{.firstName}}, order {{.orderNumber}} is out for delivery today. If it needs a handover code, find it on the order page: {{.orderUrl}}'),
('shipment.out_for_delivery', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.out_for_delivery' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: order {{.orderNumber}} is out for delivery today. Handover code (if needed) is in the app. Reply STOP to opt out.'),
('shipment.out_for_delivery', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.out_for_delivery' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Out for delivery',
 '',
 'Order {{.orderNumber}} arrives today. Have your handover code ready if asked.'),
('shipment.ready_for_pickup', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.ready_for_pickup' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Order {{.orderNumber}} is ready for pickup',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Ready when you are, {{.firstName}}</h1>
<p>Order <strong>{{.orderNumber}}</strong> is ready to collect. Bring your pickup code from the order page.</p>
<p><a href="{{.orderUrl}}" style="color:#111;font-weight:700">See pickup details</a></p>
</body></html>',
 'Ready when you are, {{.firstName}}! Order {{.orderNumber}} is ready to collect. Pickup details: {{.orderUrl}}'),
('shipment.ready_for_pickup', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.ready_for_pickup' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: order {{.orderNumber}} is ready for pickup. Details: {{.orderUrl}} Reply STOP to opt out.'),
('shipment.ready_for_pickup', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.ready_for_pickup' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Ready for pickup',
 '',
 'Order {{.orderNumber}} is ready to collect.'),
('shipment.delivery_failed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.delivery_failed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'We missed you: order {{.orderNumber}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">We couldn''t deliver your order</h1>
<p>Hi {{.firstName}}, we tried to deliver order <strong>{{.orderNumber}}</strong> but couldn''t complete it. We''ll be in touch about the next attempt.</p>
<p><a href="{{.orderUrl}}" style="color:#111;font-weight:700">View your order</a></p>
</body></html>',
 'Hi {{.firstName}}, we couldn''t deliver order {{.orderNumber}}. We''ll be in touch about the next attempt. Details: {{.orderUrl}}'),
('shipment.delivery_failed', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.delivery_failed' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: we couldn''t deliver order {{.orderNumber}}. Details: {{.orderUrl}} Reply STOP to opt out.'),
('shipment.delivery_failed', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.delivery_failed' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Delivery attempt missed',
 '',
 'We couldn''t deliver order {{.orderNumber}}. Tap for details.'),
('shipment.delivered', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.delivered' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Delivered: order {{.orderNumber}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Delivered! Enjoy, {{.firstName}}</h1>
<p>Your package from order <strong>{{.orderNumber}}</strong> has been delivered. Something not right? Let us know from the order page.</p>
<p><a href="{{.orderUrl}}" style="color:#111;font-weight:700">View your order</a></p>
</body></html>',
 'Delivered! Your package from order {{.orderNumber}} has arrived. Something not right? Let us know: {{.orderUrl}}'),
('shipment.delivered', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.delivered' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Delivered',
 '',
 'Your package from order {{.orderNumber}} has arrived. Enjoy!'),
('shipment.cancelled', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.cancelled' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Part of order {{.orderNumber}} was cancelled',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Some items were cancelled</h1>
<p>Hi {{.firstName}}, a shipment from order <strong>{{.orderNumber}}</strong> was cancelled.</p>
<p>{{.refundNote}}</p>
<p><a href="{{.orderUrl}}" style="color:#111;font-weight:700">View your order</a></p>
</body></html>',
 'Hi {{.firstName}}, a shipment from order {{.orderNumber}} was cancelled. {{.refundNote}} Details: {{.orderUrl}}'),
('shipment.cancelled', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.cancelled' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Shipment cancelled',
 '',
 'A shipment from order {{.orderNumber}} was cancelled.'),
('seller.application.submitted', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.submitted' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'We''ve received your {{.programName}} application ({{.reference}})',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Application received</h1>
<p>Hi {{.firstName}}, thanks for applying to sell with AfroTransact. Our team is reviewing the application for <strong>{{.displayName}}</strong> (ref {{.reference}}) and will get back to you within 2 business days.</p>
<p><a href="{{.portalUrl}}" style="color:#111;font-weight:700">Check your application</a></p>
</body></html>',
 'Hi {{.firstName}}, thanks for applying to sell with AfroTransact. We''re reviewing the application for {{.displayName}} (ref {{.reference}}) and will reply within 2 business days. {{.portalUrl}}'),
('seller.application.info_requested', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.info_requested' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'A few more details for application {{.reference}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">We need a little more from you</h1>
<p>Hi {{.firstName}}, to finish reviewing your {{.programName}} application ({{.reference}}) we need: <strong>{{.requestedItemsText}}</strong>.</p>
<p>{{.note}}</p>
<p><a href="{{.portalUrl}}" style="color:#111;font-weight:700">Update your application</a></p>
</body></html>',
 'Hi {{.firstName}}, to finish reviewing application {{.reference}} we need: {{.requestedItemsText}}. Update it here: {{.portalUrl}}'),
('seller.application.info_requested', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.info_requested' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: we need {{.requestedItemsText}} to finish application {{.reference}}. Update: {{.portalUrl}} Reply STOP to opt out.'),
('seller.application.approved', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.approved' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'You''re approved! Welcome to {{.programName}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Congratulations, {{.firstName}}!</h1>
<p><strong>{{.displayName}}</strong> is approved. Sign in to {{.programName}} to set up your storefront and start reaching customers across the diaspora.</p>
<p><a href="{{.portalUrl}}" style="color:#111;font-weight:700">Open {{.programName}}</a></p>
</body></html>',
 'Congratulations, {{.firstName}}! {{.displayName}} is approved. Sign in to {{.programName}} to get started: {{.portalUrl}}'),
('seller.application.approved', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.approved' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: {{.displayName}} is approved! Sign in to {{.programName}}: {{.portalUrl}} Reply STOP to opt out.'),
('seller.application.approved', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.approved' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'You''re approved!',
 '',
 '{{.displayName}} is approved. Open {{.programName}} to get started.'),
('seller.application.rejected', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.rejected' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'An update on your application {{.reference}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">An update on your application</h1>
<p>Hi {{.firstName}}, thank you for applying to {{.programName}}. After review, we''re unable to approve <strong>{{.displayName}}</strong> (ref {{.reference}}) at this time.</p>
<p>{{.note}}</p>
<p>If your circumstances change, you''re welcome to apply again. Questions? Reply to this email.</p>
</body></html>',
 'Hi {{.firstName}}, thank you for applying to {{.programName}}. We''re unable to approve {{.displayName}} (ref {{.reference}}) at this time. {{.note}} You''re welcome to apply again later.'),
('seller.compliance.reminder', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.compliance.reminder' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'Reminder: action needed by {{.deadline}} to keep payouts flowing',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Keep your payouts flowing</h1>
<p>Hi {{.firstName}}, our payments partner Stripe still needs: <strong>{{.itemsText}}</strong>.</p>
<p>Please complete it by <strong>{{.deadline}}</strong>, or payouts may be paused.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Update your details</a></p>
</body></html>',
 'Hi {{.firstName}}, Stripe still needs {{.itemsText}}. Please complete it by {{.deadline}} or payouts may be paused: {{.actionUrl}}'),
('seller.compliance.reminder', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.compliance.reminder' AND t.locale = 'en' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: action needed by {{.deadline}} to keep payouts active ({{.itemsText}}). {{.actionUrl}} Reply STOP to opt out.'),
('seller.compliance.reminder', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.compliance.reminder' AND t.locale = 'en' AND t.channel = 'push'), 'reminder',
 'Action needed',
 '',
 'Stripe needs {{.itemsText}} by {{.deadline}}.'),
('seller.compliance.action_needed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.compliance.action_needed' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'Action needed on your payout account',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Stripe needs a few details</h1>
<p>Hi {{.firstName}}, to keep your account in good standing, Stripe needs: <strong>{{.itemsText}}</strong>.</p>
<p>Please complete it by <strong>{{.deadline}}</strong>.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Update your details</a></p>
</body></html>',
 'Hi {{.firstName}}, Stripe needs {{.itemsText}} to keep your account in good standing. Please complete it by {{.deadline}}: {{.actionUrl}}'),
('seller.compliance.action_needed', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.compliance.action_needed' AND t.locale = 'en' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: Stripe needs {{.itemsText}} by {{.deadline}}. Update: {{.actionUrl}} Reply STOP to opt out.'),
('seller.compliance.action_needed', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.compliance.action_needed' AND t.locale = 'en' AND t.channel = 'push'), 'reminder',
 'Action needed',
 '',
 'Stripe needs {{.itemsText}} by {{.deadline}}.'),
('seller.payouts.paused', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.payouts.paused' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your payouts are paused',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your payouts are on hold</h1>
<p>Hi {{.firstName}}, we''ve paused payouts until we receive: <strong>{{.itemsText}}</strong>. Your earnings keep adding up and will be released once it''s sorted.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Resolve now</a></p>
</body></html>',
 'Hi {{.firstName}}, payouts are paused until we receive {{.itemsText}}. Your earnings keep adding up. Resolve it: {{.actionUrl}}'),
('seller.payouts.paused', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.payouts.paused' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: payouts are paused until we receive {{.itemsText}}. Resolve: {{.actionUrl}} Reply STOP to opt out.'),
('seller.payouts.resumed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.payouts.resumed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your payouts are back on',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Payouts resumed</h1>
<p>Good news, {{.firstName}}! Your payouts are active again, and any held earnings will go out on your next payout.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View payouts</a></p>
</body></html>',
 'Good news, {{.firstName}}! Your payouts are active again. View them: {{.actionUrl}}'),
('seller.listings.hidden', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.listings.hidden' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your listings are temporarily hidden',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your listings are hidden for now</h1>
<p>Hi {{.firstName}}, customers can''t see or buy your listings until your payment account is sorted. Stripe needs: <strong>{{.itemsText}}</strong>.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Fix it now</a></p>
</body></html>',
 'Hi {{.firstName}}, your listings are hidden until your payment account is sorted. Stripe needs {{.itemsText}}. Fix it: {{.actionUrl}}'),
('seller.listings.hidden', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.listings.hidden' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: your listings are hidden until your payment account is sorted. Fix: {{.actionUrl}} Reply STOP to opt out.'),
('seller.listings.restored', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.listings.restored' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your listings are live again',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">You''re back in business</h1>
<p>Hi {{.firstName}}, your listings are visible to customers again.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Go to your dashboard</a></p>
</body></html>',
 'Hi {{.firstName}}, your listings are live again. {{.actionUrl}}'),
('payout.paid', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payout.paid' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your {{.amount}} payout has arrived',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Payout sent</h1>
<p>Hi {{.firstName}}, <strong>{{.amount}}</strong> has been paid to your bank account.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">View payouts</a></p>
</body></html>',
 'Hi {{.firstName}}, your {{.amount}} payout has been paid to your bank account. {{.actionUrl}}'),
('payout.paid', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payout.paid' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Payout sent',
 '',
 '{{.amount}} is on its way to your bank.'),
('payout.failed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payout.failed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your {{.amount}} payout didn''t go through',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Payout failed</h1>
<p>Hi {{.firstName}}, we couldn''t send your <strong>{{.amount}}</strong> payout. The amount is available again. Please check your bank details and try again.</p>
<p><a href="{{.actionUrl}}" style="color:#111;font-weight:700">Check bank details</a></p>
</body></html>',
 'Hi {{.firstName}}, your {{.amount}} payout failed and the amount is available again. Check your bank details: {{.actionUrl}}'),
('payout.failed', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payout.failed' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: your {{.amount}} payout failed. Check your bank details: {{.actionUrl}} Reply STOP to opt out.'),
('booking.cancelled', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.cancelled' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Booking cancelled: {{.serviceName}} on {{.startsAt}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your booking was cancelled</h1>
<p>Hi {{.firstName}}, your <strong>{{.serviceName}}</strong> appointment with {{.providerName}} on {{.startsAt}} (ref {{.bookingReference}}) has been cancelled.</p>
<p>{{.refundNote}}</p>
<p><a href="{{.manageUrl}}" style="color:#111;font-weight:700">View booking</a></p>
</body></html>',
 'Hi {{.firstName}}, your {{.serviceName}} appointment with {{.providerName}} on {{.startsAt}} (ref {{.bookingReference}}) was cancelled. {{.refundNote}} {{.manageUrl}}'),
('booking.cancelled', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.cancelled' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: {{.serviceName}} with {{.providerName}} on {{.startsAt}} is cancelled. Ref {{.bookingReference}}. Reply STOP to opt out.'),
('booking.cancelled', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.cancelled' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Booking cancelled',
 '',
 '{{.serviceName}} with {{.providerName}}, {{.startsAt}} was cancelled.'),
('booking.declined', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.declined' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{.providerName}} couldn''t take your booking',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Let''s find another time</h1>
<p>Hi {{.firstName}}, {{.providerName}} couldn''t accept your <strong>{{.serviceName}}</strong> request for {{.startsAt}}. Your deposit hold has been released.</p>
<p><a href="{{.manageUrl}}" style="color:#111;font-weight:700">Book another time</a></p>
</body></html>',
 'Hi {{.firstName}}, {{.providerName}} couldn''t accept your {{.serviceName}} request for {{.startsAt}}. Your deposit hold has been released. Book another time: {{.manageUrl}}'),
('booking.declined', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.declined' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Booking not accepted',
 '',
 '{{.providerName}} couldn''t take {{.serviceName}} on {{.startsAt}}. Your hold was released.'),
('booking.rescheduled', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.rescheduled' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'New time: {{.serviceName}} on {{.startsAt}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Your booking has a new time</h1>
<p>Hi {{.firstName}}, <strong>{{.serviceName}}</strong> with {{.providerName}} is now on <strong>{{.startsAt}}</strong> at {{.location}}.</p>
<p>Booking reference: {{.bookingReference}}</p>
<p><a href="{{.manageUrl}}" style="color:#111;font-weight:700">Manage your booking</a></p>
</body></html>',
 'Hi {{.firstName}}, {{.serviceName}} with {{.providerName}} is now on {{.startsAt}} at {{.location}}. Ref {{.bookingReference}}. Manage: {{.manageUrl}}'),
('booking.rescheduled', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.rescheduled' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: {{.serviceName}} with {{.providerName}} moved to {{.startsAt}}. Ref {{.bookingReference}}. Reply STOP to opt out.'),
('booking.rescheduled', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.rescheduled' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Booking rescheduled',
 '',
 '{{.serviceName}} is now on {{.startsAt}}.'),
('booking.reminder', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.reminder' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'Reminder: {{.serviceName}} on {{.startsAt}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">See you soon, {{.firstName}}</h1>
<p><strong>{{.serviceName}}</strong> with {{.providerName}} is coming up on {{.startsAt}} at {{.location}}.</p>
<p><a href="{{.manageUrl}}" style="color:#111;font-weight:700">Manage your booking</a></p>
</body></html>',
 'See you soon, {{.firstName}}! {{.serviceName}} with {{.providerName}}, {{.startsAt}} at {{.location}}. Manage: {{.manageUrl}}'),
('booking.reminder', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.reminder' AND t.locale = 'en' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact reminder: {{.serviceName}} with {{.providerName}}, {{.startsAt}}. Reply STOP to opt out.'),
('booking.reminder', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.reminder' AND t.locale = 'en' AND t.channel = 'push'), 'reminder',
 'Coming up',
 '',
 '{{.serviceName}} with {{.providerName}}, {{.startsAt}}.'),
('careers.application_received', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'careers.application_received' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Thanks for applying: {{.jobTitle}} ({{.reference}})',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Thanks for applying, {{.firstName}}!</h1>
<p>We''ve received your application for <strong>{{.jobTitle}}</strong> (ref {{.reference}}). Our team reads every application and will be in touch if there''s a fit.</p>
</body></html>',
 'Thanks for applying, {{.firstName}}! We''ve received your application for {{.jobTitle}} (ref {{.reference}}) and will be in touch if there''s a fit.'),
('newsletter.confirm', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'newsletter.confirm' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Confirm your AfroTransact newsletter subscription',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">One quick step</h1>
<p>Confirm your email to get new arrivals, market launches and deals from AfroTransact.</p>
<p><a href="{{.confirmUrl}}" style="color:#111;font-weight:700">Confirm subscription</a></p>
<p>Didn''t sign up? Just ignore this email.</p>
</body></html>',
 'Confirm your AfroTransact newsletter subscription: {{.confirmUrl}} Didn''t sign up? Ignore this email.'),
('stock.back_in_stock', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'stock.back_in_stock' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'It''s back: {{.productTitle}}',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Good news, {{.firstName}}, it''s back!</h1>
<p><strong>{{.productTitle}}</strong> is back in stock. Popular items go fast, so grab yours while you can.</p>
<p><a href="{{.productUrl}}" style="color:#111;font-weight:700">Shop now</a></p>
</body></html>',
 'Good news, {{.firstName}}! {{.productTitle}} is back in stock. Shop now: {{.productUrl}}'),
('stock.back_in_stock', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'stock.back_in_stock' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Back in stock',
 '',
 '{{.productTitle}} is back. Grab yours before it''s gone.');
