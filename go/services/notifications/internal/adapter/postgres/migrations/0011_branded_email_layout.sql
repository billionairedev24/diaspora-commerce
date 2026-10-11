-- S277: every email on the shared AfroTransact layout (internal/app/email/layout.html.tmpl): black header with the
-- logo, gold buttons, order packages with photos, totals and address, advertorials below the content, the WhatsApp
-- community block and the footer with socials, app links, phone, address, preferences, unsubscribe and legal links.
-- Bodies are content fragments (no <html>): the renderer wraps them; staff-created full documents still send as-is.
-- SMS: brand prefix, STOP, and the tracked order / booking links. Each row is the next version of its key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('account.welcome', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'account.welcome' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Welcome to AfroTransact, {{.firstName}}!',
 '{{define "preheader"}}Your account is ready: groceries, fashion and services from home, delivered.{{end}}
{{badge "success" "Account ready"}}
<h1>Welcome home, {{.firstName}}!</h1>
<p>Your AfroTransact account is ready. Shop the flavours, fashion and services you grew up with, from sellers who know them, delivered to your door.</p>
{{facts "Fast delivery" "Fulfilled by AfroTransact items arrive in 1 to 2 days" "Pre-orders" "Community delivery runs bring home to your city" "Services" "Book braiders, tailors and caterers near you"}}
{{button .startUrl "Start exploring"}}
<p><small>Tip: add your delivery ZIP so we can show what arrives fastest near you.</small></p>',
 'Welcome home, {{.firstName}}!

Your AfroTransact account is ready. Shop the flavours, fashion and services you grew up with, delivered to your door.

Start exploring: {{.startUrl}}'),
('identity.verification', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'identity.verification' AND t.locale = 'en' AND t.channel = 'email'), 'security',
 'Your AfroTransact verification code: {{.code}}',
 '{{define "preheader"}}Your code is {{.code}}. It expires in {{.expiresInMinutes}} minutes.{{end}}
<h1>Confirm it''s you</h1>
<p>Enter this code to continue:</p>
{{otp .code}}
<p>The code expires in <strong>{{.expiresInMinutes}} minutes</strong>. Never share it: AfroTransact will never ask you for it.</p>
<p><small>Didn''t ask for a code? You can ignore this email; your account stays safe.</small></p>',
 'Your AfroTransact verification code is {{.code}}. It expires in {{.expiresInMinutes}} minutes.

Never share this code. If you didn''t ask for it, ignore this email.'),
('org.invitation', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'org.invitation' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{.inviterName}} invited you to join {{.orgName}} on AfroTransact',
 '{{define "preheader"}}Join {{.orgName}} as {{.roleName}}. The invitation expires in {{.expiresInDays}} days.{{end}}
<h1>You''re invited to {{.orgName}}</h1>
<p>{{.inviterName}} invited you to join <strong>{{.orgName}}</strong> on {{.programName}} as <strong>{{.roleName}}</strong>.</p>
{{button .acceptUrl "Accept the invitation"}}
<p>The invitation expires in {{.expiresInDays}} days. No AfroTransact account yet? You can create one from the link.</p>
<p><small>Weren''t expecting this? You can ignore this email.</small></p>',
 '{{.inviterName}} invited you to join {{.orgName}} on AfroTransact as {{.roleName}}.

Accept the invitation: {{.acceptUrl}}

The invitation expires in {{.expiresInDays}} days. If you weren''t expecting it, ignore this email.'),
('careers.application_received', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'careers.application_received' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Thanks for applying: {{.jobTitle}} ({{.reference}})',
 '{{define "preheader"}}We''ve received your application for {{.jobTitle}}.{{end}}
{{badge "success" "Application received"}}
<h1>Thanks for applying, {{.firstName}}!</h1>
<p>We''ve received your application for <strong>{{.jobTitle}}</strong>. Our team reads every application and will be in touch if there''s a fit.</p>
{{facts "Role" .jobTitle "Team" .team "Location" .location "Reference" .reference}}',
 'Thanks for applying, {{.firstName}}!

We''ve received your application for {{.jobTitle}} (ref {{.reference}}). Our team reads every application and will be in touch if there''s a fit.'),
('newsletter.confirm', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'newsletter.confirm' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Confirm your AfroTransact newsletter subscription',
 '{{define "preheader"}}One click to get new arrivals, market launches and pre-order drops.{{end}}
<h1>One quick step</h1>
<p>Confirm your email to get new arrivals, market launches and pre-order drops from AfroTransact.</p>
{{button .confirmUrl "Confirm subscription"}}
<p><small>Didn''t sign up? Just ignore this email and you won''t hear from us.</small></p>',
 'Confirm your email to get new arrivals, market launches and pre-order drops from AfroTransact:
{{.confirmUrl}}

Didn''t sign up? Just ignore this email.'),
('order.confirmed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'order.confirmed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Order {{.orderNumber}} is confirmed',
 '{{define "preheader"}}{{.total}}, arriving {{.estimatedDelivery}}.{{end}}
{{badge "success" "Order confirmed"}}
<h1>Thanks for your order, {{.firstName}}!</h1>
<p>We''ve got order <strong>{{.orderNumber}}</strong> and it''s being prepared. Arriving <strong>{{.estimatedDelivery}}</strong>; we''ll email you as each package ships.</p>
{{button .orderUrl "View your order"}}
{{facts "Order number" .orderNumber "Placed" (index . "placedDate") "Items" (index . "itemCountText")}}
<h2>What you ordered</h2>
{{template "packages" (index . "packages")}}
{{template "totals" (index . "totals")}}
{{template "address" (index . "shipTo")}}
{{with index . "helpUrl"}}<p><small>Need help with this order? Visit the <a href="{{.}}">Help Center</a> or reply to this email.</small></p>{{end}}',
 'Thanks for your order, {{.firstName}}!

Order {{.orderNumber}} is confirmed ({{.total}}). Arriving {{.estimatedDelivery}}.

View your order: {{.orderUrl}}'),
('order.cancelled', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'order.cancelled' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Order {{.orderNumber}} has been cancelled',
 '{{define "preheader"}}Any payment taken goes back to your original payment method.{{end}}
{{badge "info" "Order cancelled"}}
<h1>Your order was cancelled</h1>
<p>Hi {{.firstName}}, order <strong>{{.orderNumber}}</strong> has been cancelled.</p>
{{callout "info" "Any payment taken will be refunded to your original payment method. Refunds can take 5 to 10 business days to show."}}
{{secondaryButton .orderUrl "View the order"}}
{{with index . "helpUrl"}}<p><small>Need help with this order? Visit the <a href="{{.}}">Help Center</a> or reply to this email.</small></p>{{end}}',
 'Hi {{.firstName}}, order {{.orderNumber}} has been cancelled. Any payment taken will be refunded to your original payment method (5 to 10 business days).

Details: {{.orderUrl}}'),
('shipment.shipped', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.shipped' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Good news: order {{.orderNumber}} is on its way',
 '{{define "preheader"}}Arriving by {{.eta}}. Track it any time.{{end}}
{{badge "success" "Shipped"}}
<h1>It''s on its way, {{.firstName}}!</h1>
<p>A package from order <strong>{{.orderNumber}}</strong> has shipped and should arrive by <strong>{{.eta}}</strong>.</p>
{{button .trackingUrl "Track your package"}}
{{facts "Order number" .orderNumber "Arriving by" .eta "Tracking" .trackingLine}}
{{template "packages" (index . "packages")}}
<p><a href="{{.orderUrl}}">View the whole order</a></p>
{{with index . "helpUrl"}}<p><small>Need help with this order? Visit the <a href="{{.}}">Help Center</a> or reply to this email.</small></p>{{end}}',
 'It''s on its way, {{.firstName}}!

A package from order {{.orderNumber}} has shipped and should arrive by {{.eta}}. {{.trackingLine}}

Track it: {{.trackingUrl}}
Your order: {{.orderUrl}}'),
('shipment.out_for_delivery', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.out_for_delivery' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Order {{.orderNumber}} is out for delivery',
 '{{define "preheader"}}Arriving today. Keep your phone close.{{end}}
{{badge "success" "Out for delivery"}}
<h1>Arriving today</h1>
<p>Hi {{.firstName}}, a package from order <strong>{{.orderNumber}}</strong> is out for delivery.</p>
{{callout "warning" "If your delivery needs a handover code, you''ll find it on the order page in the app. Only share it with your driver."}}
{{button .orderUrl "Track your delivery"}}
{{template "packages" (index . "packages")}}
{{with index . "helpUrl"}}<p><small>Need help with this order? Visit the <a href="{{.}}">Help Center</a> or reply to this email.</small></p>{{end}}',
 'Hi {{.firstName}}, a package from order {{.orderNumber}} is out for delivery today.

If your delivery needs a handover code, find it on the order page. Only share it with your driver.

Track it: {{.orderUrl}}'),
('shipment.ready_for_pickup', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.ready_for_pickup' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Order {{.orderNumber}} is ready for pickup',
 '{{define "preheader"}}Bring your pickup code from the order page.{{end}}
{{badge "success" "Ready for pickup"}}
<h1>Ready when you are, {{.firstName}}</h1>
<p>A package from order <strong>{{.orderNumber}}</strong> is ready to collect. Bring the pickup code from the order page.</p>
{{button .orderUrl "See pickup details"}}
{{template "packages" (index . "packages")}}
{{with index . "helpUrl"}}<p><small>Need help with this order? Visit the <a href="{{.}}">Help Center</a> or reply to this email.</small></p>{{end}}',
 'Ready when you are, {{.firstName}}! A package from order {{.orderNumber}} is ready to collect. Bring the pickup code from the order page.

Pickup details: {{.orderUrl}}'),
('shipment.delivery_failed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.delivery_failed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'We missed you: order {{.orderNumber}}',
 '{{define "preheader"}}We couldn''t complete your delivery. Here''s what happens next.{{end}}
{{badge "warning" "Delivery attempted"}}
<h1>We couldn''t deliver your package</h1>
<p>Hi {{.firstName}}, we tried to deliver a package from order <strong>{{.orderNumber}}</strong> but couldn''t complete it. We''ll be in touch about the next attempt, or you can change the delivery details from the order page.</p>
{{button .orderUrl "View delivery options"}}
{{template "packages" (index . "packages")}}
{{with index . "helpUrl"}}<p><small>Need help with this order? Visit the <a href="{{.}}">Help Center</a> or reply to this email.</small></p>{{end}}',
 'Hi {{.firstName}}, we tried to deliver a package from order {{.orderNumber}} but couldn''t complete it. We''ll be in touch about the next attempt.

Delivery options: {{.orderUrl}}'),
('shipment.delivered', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.delivered' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Delivered: order {{.orderNumber}}',
 '{{define "preheader"}}Your package has arrived. Enjoy!{{end}}
{{badge "success" "Delivered"}}
<h1>Delivered! Enjoy, {{.firstName}}</h1>
<p>Your package from order <strong>{{.orderNumber}}</strong> was delivered{{if .deliveredDate}} on {{.deliveredDate}}{{end}}.</p>
{{template "packages" (index . "packages")}}
{{button .reviewUrl "Rate your items"}}
<p>Something not right? Report a problem from the <a href="{{.orderUrl}}">order page</a> within 30 days.</p>
{{with index . "helpUrl"}}<p><small>Need help with this order? Visit the <a href="{{.}}">Help Center</a> or reply to this email.</small></p>{{end}}',
 'Delivered! Enjoy, {{.firstName}}. Your package from order {{.orderNumber}} has arrived.

Something not right? Report it from the order page: {{.orderUrl}}'),
('shipment.cancelled', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.cancelled' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Part of order {{.orderNumber}} was cancelled',
 '{{define "preheader"}}{{.refundNote}}{{end}}
{{badge "info" "Package cancelled"}}
<h1>Some items were cancelled</h1>
<p>Hi {{.firstName}}, a package from order <strong>{{.orderNumber}}</strong> was cancelled.</p>
{{callout "info" .refundNote}}
{{template "packages" (index . "packages")}}
{{secondaryButton .orderUrl "View your order"}}
{{with index . "helpUrl"}}<p><small>Need help with this order? Visit the <a href="{{.}}">Help Center</a> or reply to this email.</small></p>{{end}}',
 'Hi {{.firstName}}, a package from order {{.orderNumber}} was cancelled. {{.refundNote}}

Your order: {{.orderUrl}}'),
('payment.authentication_required', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.authentication_required' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Authentication needed for your {{.amount}} payment',
 '{{define "preheader"}}Your bank needs you to confirm a payment of {{.amount}}.{{end}}
{{badge "warning" "Action needed"}}
<h1>Please confirm your payment</h1>
<p>Hi {{.firstName}}, your bank needs you to confirm the <strong>{{.amount}}</strong> payment for {{.reference}} before we can complete it. It only takes a moment.</p>
{{button .actionUrl "Confirm payment"}}
<p><small>Until you confirm, the payment stays on hold. We''ll never ask for your card details by email.</small></p>',
 'Hi {{.firstName}}, your bank needs you to confirm the {{.amount}} payment for {{.reference}}.

Confirm it: {{.actionUrl}}'),
('preorder.announce', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 '{{.name}} is open: order by {{.closesDisplay}}',
 '{{define "preheader"}}Order by {{.closesDisplay}}, delivered {{.deliversDisplay}}.{{end}}
{{badge "warning" "Pre-order open"}}
<h1>{{if .headline}}{{.headline}}{{else}}Pre-order is open in {{.where}}{{end}}</h1>
<p>Hi {{.firstName}}, <strong>{{.name}}</strong> is open. Order with your neighbours and we deliver everything on one day.</p>
{{facts "Order by" .closesDisplay "Delivered" .deliversDisplay "Door delivery" .doorFee "Pickup" .pickupFee "Pickup window" .pickupWindow}}
{{button .emailUrl "Pre-order now"}}
<p><small>You''re charged at checkout and can change or cancel pre-order items for a full refund until the pre-order closes.</small></p>',
 'Hi {{.firstName}}, {{.name}} is open. Order by {{.closesDisplay}} and we deliver on {{.deliversDisplay}}.
{{if .doorFee}}Door delivery: {{.doorFee}} flat.
{{end}}{{if .pickupFee}}Pickup: {{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}.
{{end}}You''re charged at checkout and can change or cancel pre-order items for a full refund until the pre-order closes.
Pre-order now: {{.emailUrl}}{{if .unsubscribeUrl}}

Unsubscribe from {{.where}} pre-order alerts: {{.unsubscribeUrl}}{{end}}'),
('preorder.waitlist.opened', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.waitlist.opened' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'Pre-order is open in {{.where}}',
 '{{define "preheader"}}{{.name}} is open until {{.closesDisplay}}.{{end}}
{{badge "warning" "You asked, we''re here"}}
<h1>We deliver to {{.where}}</h1>
<p>Hi {{.firstName}}, you asked us to tell you when a pre-order opens in {{.where}}. <strong>{{.name}}</strong> is open now.</p>
{{facts "Order by" .closesDisplay "Delivered" .deliversDisplay "Door delivery" .doorFee "Pickup" .pickupFee "Pickup window" .pickupWindow}}
{{button .emailUrl "See what''s in the pre-order"}}',
 'Hi {{.firstName}}, you asked us to tell you when a pre-order opens in {{.where}}. {{.name}} is open until {{.closesDisplay}}, delivered on {{.deliversDisplay}}.{{if .doorFee}} Door delivery is {{.doorFee}} flat.{{end}}{{if .pickupFee}} Pickup is {{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}.{{end}}
See what''s in the pre-order: {{.emailUrl}}{{if .unsubscribeUrl}}

Leave the waitlist: {{.unsubscribeUrl}}{{end}}'),
('preorder.reminder', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.reminder' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 '48 hours left: {{.name}} closes {{.closesDisplay}}',
 '{{define "preheader"}}Order by {{.closesDisplay}} to get it on {{.deliversDisplay}}.{{end}}
{{badge "warning" "48 hours left"}}
<h1>48 hours left to pre-order</h1>
<p>Hi {{.firstName}}, <strong>{{.name}}</strong> closes on <strong>{{.closesDisplay}}</strong>. Everything ordered by then arrives on <strong>{{.deliversDisplay}}</strong>.</p>
{{facts "Order by" .closesDisplay "Delivered" .deliversDisplay "Door delivery" .doorFee "Pickup" .pickupFee "Pickup window" .pickupWindow}}
{{button .emailUrl "Finish your pre-order"}}',
 'Hi {{.firstName}}, {{.name}} closes on {{.closesDisplay}}. Everything ordered by then arrives on {{.deliversDisplay}}{{if .doorFee}} with door delivery for {{.doorFee}}{{end}}{{if .pickupFee}}{{if .doorFee}} or{{else}} with{{end}} pickup ({{.pickupFee}}){{end}}.
Finish your pre-order: {{.emailUrl}}{{if .unsubscribeUrl}}

Unsubscribe: {{.unsubscribeUrl}}{{end}}'),
('preorder.closed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.closed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{.name}} is closed: delivering {{.deliversDisplay}}',
 '{{define "preheader"}}Your pre-order is locked in for {{.deliversDisplay}}.{{end}}
{{badge "success" "Locked in"}}
<h1>Your pre-order is locked in</h1>
<p>Hi {{.firstName}}, <strong>{{.name}}</strong> is now closed. We deliver your pre-order on <strong>{{.deliversDisplay}}</strong>{{if .pickupWindow}} (pickup {{.pickupWindow}}){{end}}.</p>
{{button .emailUrl "View your pre-order"}}
<p><small>If you opted in to texts, we''ll message you when it''s out for delivery.</small></p>',
 'Hi {{.firstName}}, {{.name}} is now closed. We deliver your pre-order on {{.deliversDisplay}}{{if .pickupWindow}} (pickup {{.pickupWindow}}){{end}}. If you opted in to texts, we''ll message you when it''s out for delivery. {{.emailUrl}}'),
('preorder.confirmed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.confirmed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'You''re in: {{.name}}',
 '{{define "preheader"}}Pre-order {{.preorderNumber}}, delivered {{.deliversDisplay}}. Charged {{.total}}.{{end}}
{{badge "success" "You''re in"}}
<h1>You''re in: {{.name}}</h1>
<p>Hi {{.firstName}}, thanks for your pre-order. We deliver it with the rest of the community on <strong>{{.deliversDisplay}}</strong>.</p>
{{facts "Pre-order number" .preorderNumber "Delivered" .deliversDisplay .fulfilmentText (or .doorFee .pickupFee) "Charged" .total}}
{{button .emailUrl "View your pre-order"}}
{{template "packages" (index . "packages")}}
{{template "totals" (index . "totals")}}
{{template "address" (index . "shipTo")}}
{{callout "info" (printf "%sPre-order items can be changed or cancelled for a full refund until %s." (or .nowNote "") .cancelUntil)}}
{{with index . "helpUrl"}}<p><small>Need help with this order? Visit the <a href="{{.}}">Help Center</a> or reply to this email.</small></p>{{end}}',
 'Hi {{.firstName}}, you''re in: {{.name}}.
Pre-order #: {{.preorderNumber}}
Delivered: {{.deliversDisplay}}
{{.fulfilmentText}}: {{if .doorFee}}{{.doorFee}}{{else}}{{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}{{end}}
Charged: {{.total}}
{{if .nowNote}}{{.nowNote}} {{end}}Pre-order items can be changed or cancelled for a full refund until {{.cancelUntil}}. If you''ve opted in, we''ll text you on delivery day.
{{.emailUrl}}'),
('preorder.delivery_missed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.delivery_missed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'We missed your delivery: {{.name}}',
 '{{define "preheader"}}We''re sorry. Choose a new date or a full refund.{{end}}
{{badge "danger" "Delivery missed"}}
<h1>We''re sorry, we missed your delivery</h1>
<p>Hi {{.firstName}}, we couldn''t deliver <strong>{{.name}}</strong> (order {{.orderNumber}}) on {{.missedDisplay}}. Choose what works for you:</p>
{{if .newDateUrl}}{{button .newDateUrl (printf "Deliver it on %s" .newDateDisplay)}}{{end}}
{{if .refundUrl}}{{secondaryButton .refundUrl (printf "Get a full refund of %s" .total)}}{{end}}
{{template "packages" (index . "packages")}}
{{with index . "helpUrl"}}<p><small>Need help with this order? Visit the <a href="{{.}}">Help Center</a> or reply to this email.</small></p>{{end}}',
 'Hi {{.firstName}}, we''re sorry: we couldn''t deliver {{.name}} (order {{.orderNumber}}) on {{.missedDisplay}}. Choose what works for you:
{{if .newDateUrl}}Deliver it on {{.newDateDisplay}}: {{.newDateUrl}}
{{end}}{{if .refundUrl}}Get a full refund of {{.total}}: {{.refundUrl}}
{{end}}Your orders: {{.orderUrl}}'),
('booking.confirmed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.confirmed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Booking confirmed: {{.serviceName}} on {{.startsAt}}',
 '{{define "preheader"}}{{.serviceName}} with {{.providerName}}, {{.startsAt}}.{{end}}
{{badge "success" "Booking confirmed"}}
<h1>You''re booked, {{.firstName}}!</h1>
{{template "bookingCard" .}}
{{button .manageUrl "Manage your booking"}}
<p><small>Need to change plans? Reschedule or cancel from the booking page; the provider''s cancellation policy applies.</small></p>',
 'You''re booked, {{.firstName}}! {{.serviceName}} with {{.providerName}}, {{.startsAt}} at {{.location}}. Reference {{.bookingReference}}.

Manage: {{.manageUrl}}'),
('booking.reminder', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.reminder' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'Reminder: {{.serviceName}} on {{.startsAt}}',
 '{{define "preheader"}}See you soon: {{.serviceName}} with {{.providerName}}.{{end}}
{{badge "info" "Coming up"}}
<h1>See you soon, {{.firstName}}</h1>
{{template "bookingCard" .}}
{{button .manageUrl "Manage your booking"}}',
 'See you soon, {{.firstName}}: {{.serviceName}} with {{.providerName}}, {{.startsAt}} at {{.location}}.

Manage: {{.manageUrl}}'),
('booking.rescheduled', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.rescheduled' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'New time: {{.serviceName}} on {{.startsAt}}',
 '{{define "preheader"}}Your booking is now on {{.startsAt}}.{{end}}
{{badge "warning" "New time"}}
<h1>Your booking has a new time</h1>
<p>Hi {{.firstName}}, your appointment is now on <strong>{{.startsAt}}</strong>.</p>
{{template "bookingCard" .}}
{{button .manageUrl "Manage your booking"}}',
 'Hi {{.firstName}}, {{.serviceName}} with {{.providerName}} is now on {{.startsAt}} at {{.location}}. Reference {{.bookingReference}}.

Manage: {{.manageUrl}}'),
('booking.cancelled', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.cancelled' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Booking cancelled: {{.serviceName}} on {{.startsAt}}',
 '{{define "preheader"}}Your appointment with {{.providerName}} was cancelled.{{end}}
{{badge "info" "Booking cancelled"}}
<h1>Your booking was cancelled</h1>
<p>Hi {{.firstName}}, this appointment has been cancelled.</p>
{{template "bookingCard" .}}
{{callout "info" .refundNote}}
{{secondaryButton .manageUrl "View booking"}}',
 'Hi {{.firstName}}, {{.serviceName}} with {{.providerName}} on {{.startsAt}} (ref {{.bookingReference}}) has been cancelled. {{.refundNote}}

View booking: {{.manageUrl}}'),
('booking.declined', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.declined' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{.providerName}} couldn''t take your booking',
 '{{define "preheader"}}Your deposit hold has been released. Let''s find another time.{{end}}
{{badge "info" "Not accepted"}}
<h1>Let''s find another time</h1>
<p>Hi {{.firstName}}, {{.providerName}} couldn''t accept your request. Your deposit hold has been released.</p>
{{template "bookingCard" .}}
{{button .manageUrl "Book another time"}}',
 'Hi {{.firstName}}, {{.providerName}} couldn''t accept your {{.serviceName}} request for {{.startsAt}}. Your deposit hold has been released.

Book another time: {{.manageUrl}}'),
('booking.balance_unpaid', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.balance_unpaid' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Action needed: balance for {{.serviceName}} on {{.startsAt}}',
 '{{define "preheader"}}We couldn''t charge the {{.balance}} balance. Update your card to keep the booking.{{end}}
{{badge "warning" "Action needed"}}
<h1>We couldn''t charge your balance</h1>
<p>Hi {{.firstName}}, the <strong>{{.balance}}</strong> balance for this booking didn''t go through on your saved card.</p>
{{template "bookingCard" .}}
{{button .manageUrl "Update payment"}}
<p><small>Please update your card or confirm the payment with your bank so your booking stays on track.</small></p>',
 'Hi {{.firstName}}, we couldn''t charge the {{.balance}} balance for {{.serviceName}} with {{.providerName}} on {{.startsAt}} (ref {{.bookingReference}}).

Update payment: {{.manageUrl}}'),
('claim.opened', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.opened' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'We received your claim {{.reference}}',
 '{{define "preheader"}}The seller has until {{.dueDate}} to respond.{{end}}
{{badge "info" "Claim open"}}
<h1>Your claim is open</h1>
<p>Hi {{.firstName}}, we received your A-to-z claim for order <strong>{{.orderNumber}}</strong>.</p>
{{facts "Claim" .reference "Type" .claimType "Order" .orderNumber "Amount" .amount "Seller responds by" .dueDate}}
<p>After the seller responds, or the deadline passes, our team reviews it and emails you the decision.</p>
{{secondaryButton .claimsUrl "View your orders"}}',
 'Hi {{.firstName}}, we received your A-to-z claim {{.reference}} ({{.claimType}}) for order {{.orderNumber}}. The seller has until {{.dueDate}} to respond.

Your orders: {{.claimsUrl}}'),
('claim.decided', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.decided' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Decision on your claim {{.reference}}',
 '{{define "preheader"}}{{.outcomeText}}{{end}}
{{badge "info" "Claim decided"}}
<h1>Your claim has a decision</h1>
<p>Hi {{.firstName}}, we''ve reviewed claim <strong>{{.reference}}</strong> for order {{.orderNumber}}.</p>
{{callout "success" .outcomeText}}
{{secondaryButton .claimsUrl "View your orders"}}',
 'Hi {{.firstName}}, we''ve reviewed claim {{.reference}} for order {{.orderNumber}}. {{.outcomeText}}

Your orders: {{.claimsUrl}}'),
('claim.decided.seller', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.decided.seller' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Claim {{.reference}} on order {{.orderNumber}} was decided',
 '{{define "preheader"}}{{.sellerOutcomeText}}{{end}}
{{badge "info" "Claim decided"}}
<h1>Claim decided</h1>
<p>Hi {{.firstName}}, our team decided claim <strong>{{.reference}}</strong> ({{.claimType}}) on order {{.orderNumber}}.</p>
{{callout "info" .sellerOutcomeText}}
{{button .actionUrl "View orders"}}',
 'Hi {{.firstName}}, claim {{.reference}} ({{.claimType}}) on order {{.orderNumber}} was decided. {{.sellerOutcomeText}}

Orders: {{.actionUrl}}'),
('claim.seller_response_due', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.seller_response_due' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Respond by {{.dueDate}}: claim {{.reference}} on order {{.orderNumber}}',
 '{{define "preheader"}}A customer opened a claim. Respond by {{.dueDate}}.{{end}}
{{badge "warning" "Response needed"}}
<h1>A customer opened a claim</h1>
<p>Hi {{.firstName}}, a customer opened an A-to-z claim on order <strong>{{.orderNumber}}</strong>. Please respond with your side and any evidence.</p>
{{facts "Claim" .reference "Type" .claimType "Amount" .amount "Respond by" .dueDate}}
<p><small>Claims without a response go to our team for a decision.</small></p>
{{button .actionUrl "Respond now"}}',
 'Hi {{.firstName}}, a customer opened claim {{.reference}} ({{.claimType}}, {{.amount}}) on order {{.orderNumber}}. Respond by {{.dueDate}}: {{.actionUrl}}'),
('claim.seller_response_reminder', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.seller_response_reminder' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Reminder: respond to claim {{.reference}} by {{.dueDate}}',
 '{{define "preheader"}}Claim {{.reference}} still needs your response.{{end}}
{{badge "warning" "Due soon"}}
<h1>Your response is due soon</h1>
<p>Hi {{.firstName}}, claim <strong>{{.reference}}</strong> on order <strong>{{.orderNumber}}</strong> still needs your response by <strong>{{.dueDate}}</strong>. If we don''t hear from you, our team decides without your input.</p>
{{button .actionUrl "Respond now"}}',
 'Hi {{.firstName}}, claim {{.reference}} on order {{.orderNumber}} needs your response by {{.dueDate}}: {{.actionUrl}}'),
('claim.seller_response_lapsed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.seller_response_lapsed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Response window closed for claim {{.reference}}',
 '{{define "preheader"}}Our team will now decide the claim.{{end}}
{{badge "danger" "Window closed"}}
<h1>The response window has closed</h1>
<p>Hi {{.firstName}}, we didn''t receive a response to claim <strong>{{.reference}}</strong> on order <strong>{{.orderNumber}}</strong> in time. Our team will now decide based on the information we have and let you know the outcome.</p>
{{button .actionUrl "View the claim"}}',
 'Hi {{.firstName}}, the response window for claim {{.reference}} on order {{.orderNumber}} has closed. Our team will decide it.

{{.actionUrl}}'),
('claim.withdrawn', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'claim.withdrawn' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Claim {{.reference}} was withdrawn',
 '{{define "preheader"}}No action is needed.{{end}}
{{badge "success" "Withdrawn"}}
<h1>Claim withdrawn</h1>
<p>Hi {{.firstName}}, the customer withdrew claim <strong>{{.reference}}</strong> on order {{.orderNumber}}. No action is needed.</p>
{{button .actionUrl "View orders"}}',
 'Hi {{.firstName}}, the customer withdrew claim {{.reference}} on order {{.orderNumber}}. No action is needed.'),
('listing.rejected', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'listing.rejected' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your listing "{{.title}}" wasn''t approved',
 '{{define "preheader"}}Reason: {{.reasonText}}. Edit and resubmit.{{end}}
{{badge "warning" "Not approved"}}
<h1>Listing not approved</h1>
<p>Hi {{.firstName}}, we reviewed <strong>{{.title}}</strong> and couldn''t approve it.</p>
{{facts "Listing" .title "Reason" .reasonText}}
{{if .note}}{{callout "info" (printf "Note from our Trust and Safety team: %s" .note)}}{{end}}
<p>You can edit the listing and submit it again.</p>
{{button .actionUrl "Edit your listings"}}',
 'Hi {{.firstName}}, we couldn''t approve "{{.title}}" (reason: {{.reasonText}}).{{if .note}} Note: {{.note}}{{end}} Edit and resubmit: {{.actionUrl}}'),
('listing.taken_down', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'listing.taken_down' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your listing "{{.title}}" was taken down',
 '{{define "preheader"}}Reason: {{.reasonText}}.{{end}}
{{badge "danger" "Taken down"}}
<h1>Listing taken down</h1>
<p>Hi {{.firstName}}, after a review we removed <strong>{{.title}}</strong> from the storefront. Customers can no longer see or buy it.</p>
{{facts "Listing" .title "Reason" .reasonText}}
{{if .note}}{{callout "info" (printf "Note from our Trust and Safety team: %s" .note)}}{{end}}

{{button .actionUrl "Review your listings"}}',
 'Hi {{.firstName}}, we removed "{{.title}}" from the storefront (reason: {{.reasonText}}).{{if .note}} Note: {{.note}}{{end}} {{.actionUrl}}'),
('seller.application.submitted', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.submitted' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'We''ve received your {{.programName}} application ({{.reference}})',
 '{{define "preheader"}}We''ll get back to you within 2 business days.{{end}}
{{badge "success" "Application received"}}
<h1>Application received</h1>
<p>Hi {{.firstName}}, thanks for applying to {{.programName}}. Our team is reviewing the application for <strong>{{.displayName}}</strong> and will get back to you within 2 business days.</p>
{{facts "Business" .displayName "Reference" .reference}}
{{button .portalUrl "Check your application"}}',
 'Hi {{.firstName}}, we received the {{.programName}} application for {{.displayName}} (ref {{.reference}}). We''ll get back to you within 2 business days.

{{.portalUrl}}'),
('seller.application.info_requested', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.info_requested' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'A few more details for application {{.reference}}',
 '{{define "preheader"}}We need {{.requestedItemsText}} to finish your review.{{end}}
{{badge "warning" "Action needed"}}
<h1>We need a little more from you</h1>
<p>Hi {{.firstName}}, to finish reviewing your {{.programName}} application ({{.reference}}) we need: <strong>{{.requestedItemsText}}</strong>.</p>
{{if .note}}{{callout "info" .note}}{{end}}
{{button .portalUrl "Update your application"}}',
 'Hi {{.firstName}}, to finish application {{.reference}} we need: {{.requestedItemsText}}. {{.note}}

{{.portalUrl}}'),
('seller.application.approved', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.approved' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'You''re approved! Welcome to {{.programName}}',
 '{{define "preheader"}}{{.displayName}} is approved. Set up your storefront.{{end}}
{{badge "success" "Approved"}}
<h1>Congratulations, {{.firstName}}!</h1>
<p><strong>{{.displayName}}</strong> is approved. Sign in to {{.programName}} to set up your storefront and start reaching customers across the diaspora.</p>
{{button .portalUrl "Open {{.programName}}"}}',
 'Congratulations, {{.firstName}}! {{.displayName}} is approved. Open {{.programName}}: {{.portalUrl}}'),
('seller.application.rejected', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.rejected' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'An update on your application {{.reference}}',
 '{{define "preheader"}}An update on your {{.programName}} application.{{end}}
<h1>An update on your application</h1>
<p>Hi {{.firstName}}, thank you for applying to {{.programName}}. After review, we''re unable to approve <strong>{{.displayName}}</strong> (ref {{.reference}}) at this time.</p>
{{if .note}}{{callout "info" .note}}{{end}}
<p>If your circumstances change, you''re welcome to apply again. Questions? Reply to this email.</p>',
 'Hi {{.firstName}}, after review we''re unable to approve {{.displayName}} (ref {{.reference}}) for {{.programName}} at this time. {{.note}}'),
('seller.compliance.action_needed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.compliance.action_needed' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'Action needed on your payout account',
 '{{define "preheader"}}Stripe needs {{.itemsText}} by {{.deadline}}.{{end}}
{{badge "warning" "Action needed"}}
<h1>Stripe needs a few details</h1>
<p>Hi {{.firstName}}, to keep your account in good standing, our payments partner Stripe needs: <strong>{{.itemsText}}</strong>.</p>
{{facts "Needed" .itemsText "Complete by" .deadline}}
{{button .actionUrl "Update your details"}}',
 'Hi {{.firstName}}, Stripe needs {{.itemsText}} by {{.deadline}}: {{.actionUrl}}'),
('seller.compliance.reminder', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.compliance.reminder' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'Reminder: action needed by {{.deadline}} to keep payouts flowing',
 '{{define "preheader"}}Stripe still needs {{.itemsText}}.{{end}}
{{badge "warning" "Reminder"}}
<h1>Keep your payouts flowing</h1>
<p>Hi {{.firstName}}, our payments partner Stripe still needs: <strong>{{.itemsText}}</strong>. Please complete it by <strong>{{.deadline}}</strong>, or payouts may be paused.</p>
{{button .actionUrl "Update your details"}}',
 'Hi {{.firstName}}, Stripe still needs {{.itemsText}}. Complete it by {{.deadline}}: {{.actionUrl}}'),
('payout.requirement_due', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payout.requirement_due' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'Action needed: update your payout details by {{.dueDate}}',
 '{{define "preheader"}}{{.requirement}} by {{.dueDate}}.{{end}}
{{badge "warning" "Action needed"}}
<h1>Keep your payouts flowing</h1>
<p>Hi {{.businessName}}, our payments partner Stripe needs more information to keep your account in compliance.</p>
{{facts "Needed" .requirement "Complete by" .dueDate}}
<p>Otherwise payouts will be paused.</p>
{{button .actionUrl "Update details"}}',
 'Hi {{.businessName}}, Stripe needs: {{.requirement}}. Complete it by {{.dueDate}}: {{.actionUrl}}'),
('seller.listings.hidden', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.listings.hidden' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your listings are temporarily hidden',
 '{{define "preheader"}}Stripe needs {{.itemsText}}.{{end}}
{{badge "danger" "Listings hidden"}}
<h1>Your listings are hidden for now</h1>
<p>Hi {{.firstName}}, customers can''t see or buy your listings until your payment account is sorted. Stripe needs: <strong>{{.itemsText}}</strong>.</p>
{{button .actionUrl "Fix it now"}}',
 'Hi {{.firstName}}, your listings are hidden until Stripe receives {{.itemsText}}: {{.actionUrl}}'),
('seller.listings.hidden.suspended', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.listings.hidden.suspended' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your listings are hidden while your account is suspended',
 '{{define "preheader"}}Reason: {{.reasonText}}.{{end}}
{{badge "danger" "Listings hidden"}}
<h1>Your listings are hidden</h1>
<p>Hi {{.firstName}}, because your seller account is suspended (reason: {{.reasonText}}), customers can''t see or buy your listings and services for now.</p>
{{if .note}}{{callout "info" (printf "Note from our Trust and Safety team: %s" .note)}}{{end}}

{{button .actionUrl "View account health"}}',
 'Hi {{.firstName}}, your listings are hidden while your account is suspended ({{.reasonText}}). {{.note}}

{{.actionUrl}}'),
('seller.listings.restored', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.listings.restored' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your listings are live again',
 '{{define "preheader"}}Customers can see and buy your listings again.{{end}}
{{badge "success" "Live again"}}
<h1>You''re back in business</h1>
<p>Hi {{.firstName}}, your listings are visible to customers again.</p>
{{button .actionUrl "Go to your dashboard"}}',
 'Hi {{.firstName}}, your listings are visible to customers again. {{.actionUrl}}'),
('seller.listings.restored.reinstated', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.listings.restored.reinstated' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your account is reinstated: listings are live again',
 '{{define "preheader"}}Your listings are visible again.{{end}}
{{badge "success" "Reinstated"}}
<h1>You''re back in business</h1>
<p>Hi {{.firstName}}, your seller account has been reinstated and your listings are visible to customers again.</p>
{{button .actionUrl "View account health"}}',
 'Hi {{.firstName}}, your seller account is reinstated and your listings are live again. {{.actionUrl}}'),
('seller.payouts.paused', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.payouts.paused' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your payouts are paused',
 '{{define "preheader"}}We need {{.itemsText}} to release them.{{end}}
{{badge "danger" "Payouts paused"}}
<h1>Your payouts are on hold</h1>
<p>Hi {{.firstName}}, we''ve paused payouts until we receive: <strong>{{.itemsText}}</strong>. Your earnings keep adding up and are released once it''s sorted.</p>
{{button .actionUrl "Resolve now"}}',
 'Hi {{.firstName}}, payouts are paused until we receive {{.itemsText}}. Resolve: {{.actionUrl}}'),
('seller.payouts.paused.suspended', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.payouts.paused.suspended' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your payouts are on hold while your account is suspended',
 '{{define "preheader"}}Reason: {{.reasonText}}. Your earnings are kept safe.{{end}}
{{badge "danger" "Payouts on hold"}}
<h1>Payouts on hold</h1>
<p>Hi {{.firstName}}, because your seller account is suspended (reason: {{.reasonText}}), we''ve put your payouts on hold. Your earnings are kept safe and released if your account is reinstated.</p>
{{if .note}}{{callout "info" (printf "Note from our Trust and Safety team: %s" .note)}}{{end}}

{{button .actionUrl "View account health"}}',
 'Hi {{.firstName}}, payouts are on hold while your account is suspended (reason: {{.reasonText}}). {{.note}}

{{.actionUrl}}'),
('seller.payouts.resumed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.payouts.resumed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your payouts are back on',
 '{{define "preheader"}}Held earnings go out on your next payout.{{end}}
{{badge "success" "Payouts resumed"}}
<h1>Payouts resumed</h1>
<p>Good news, {{.firstName}}! Your payouts are active again, and any held earnings go out on your next payout.</p>
{{button .actionUrl "View payouts"}}',
 'Good news, {{.firstName}}! Your payouts are active again. {{.actionUrl}}'),
('seller.payouts.resumed.reinstated', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.payouts.resumed.reinstated' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your account is reinstated: payouts are back on',
 '{{define "preheader"}}Held earnings go out on your next payout.{{end}}
{{badge "success" "Reinstated"}}
<h1>Payouts resumed</h1>
<p>Good news, {{.firstName}}! Your seller account has been reinstated, so payouts are active again and held earnings go out on your next payout.</p>
{{button .actionUrl "View account health"}}',
 'Good news, {{.firstName}}! Your seller account is reinstated and payouts are back on. {{.actionUrl}}'),
('seller.suspended', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.suspended' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your AfroTransact seller account is suspended',
 '{{define "preheader"}}Reason: {{.reasonText}}. Listings hidden, payouts held.{{end}}
{{badge "danger" "Account suspended"}}
<h1>Your account is suspended</h1>
<p>Hi {{.firstName}}, our Trust and Safety team has suspended your seller account (reason: {{.reasonText}}).</p>
{{callout "warning" "While it''s suspended, your listings are hidden and payouts are on hold. Your earnings are kept safe."}}
{{if .note}}{{callout "info" (printf "Note from our Trust and Safety team: %s" .note)}}{{end}}

{{button .actionUrl "View account health"}}',
 'Hi {{.firstName}}, your seller account is suspended (reason: {{.reasonText}}). Listings are hidden and payouts held. {{.note}}

{{.actionUrl}}'),
('seller.reinstated', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.reinstated' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your AfroTransact seller account is reinstated',
 '{{define "preheader"}}Listings return and payouts resume.{{end}}
{{badge "success" "Reinstated"}}
<h1>Welcome back</h1>
<p>Good news, {{.firstName}}! Your seller account has been reinstated. Your listings return and payouts resume unless a separate payment-account requirement still holds them.</p>
{{button .actionUrl "View account health"}}',
 'Good news, {{.firstName}}! Your seller account is reinstated. {{.actionUrl}}'),
('payout.paid', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payout.paid' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your {{.amount}} payout has arrived',
 '{{define "preheader"}}{{.amount}} is on its way to your bank.{{end}}
{{badge "success" "Payout sent"}}
<h1>Payout sent</h1>
<p>Hi {{.firstName}}, <strong>{{.amount}}</strong> has been paid to your bank account. It usually shows within 1 to 2 business days.</p>
{{facts "Amount" .amount "Status" "Paid"}}
{{button .actionUrl "View payouts"}}',
 'Hi {{.firstName}}, {{.amount}} has been paid to your bank account. {{.actionUrl}}'),
('payout.failed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payout.failed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your {{.amount}} payout didn''t go through',
 '{{define "preheader"}}Check your bank details. The amount is available again.{{end}}
{{badge "danger" "Payout failed"}}
<h1>Payout failed</h1>
<p>Hi {{.firstName}}, we couldn''t send your <strong>{{.amount}}</strong> payout. The amount is available again; please check your bank details and try again.</p>
{{button .actionUrl "Check bank details"}}',
 'Hi {{.firstName}}, your {{.amount}} payout failed. Check your bank details: {{.actionUrl}}'),
('retail.purchase_order.sent', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'retail.purchase_order.sent' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Purchase order {{.poNumber}} from AfroTransact Retail',
 '{{define "preheader"}}Purchase order {{.poNumber}}, total {{.total}}. PDF attached.{{end}}
<h1>Purchase order {{.poNumber}}</h1>
<p>Hello {{.contactName}},</p>
<p>AfroTransact Retail has issued purchase order <strong>{{.poNumber}}</strong> to {{.supplierName}}.</p>
{{facts "Order date" .orderDate "Deliver by" .deliverBy "Incoterm" .incoterm "Payment terms" .paymentTerms "Total" .total}}
<p>The purchase order PDF is attached. If you can''t see the attachment, download it here:</p>
{{button .documentUrl (printf "Download %s (PDF)" .poNumber)}}
{{if .documentExpiresOn}}<p><small>This link works until {{.documentExpiresOn}}.</small></p>{{end}}
<p>Please confirm the order and your expected ship date with your AfroTransact Retail buyer.</p>',
 'Hello {{.contactName}},

AfroTransact Retail has issued purchase order {{.poNumber}} to {{.supplierName}}.

Order date: {{.orderDate}}
{{if .deliverBy}}Deliver by: {{.deliverBy}}
{{end}}{{if .incoterm}}Incoterm: {{.incoterm}}
{{end}}{{if .paymentTerms}}Payment terms: {{.paymentTerms}}
{{end}}Total: {{.total}}

The purchase order PDF is attached. If you can''t see it, download it here{{if .documentExpiresOn}} (link valid until {{.documentExpiresOn}}){{end}}:
{{.documentUrl}}

Please confirm the order and your expected ship date with your AfroTransact Retail buyer.'),
('stock.back_in_stock', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'stock.back_in_stock' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'It''s back: {{.productTitle}}',
 '{{define "preheader"}}{{.productTitle}} is back in stock. Popular items go fast.{{end}}
{{badge "success" "Back in stock"}}
<h1>Good news, {{.firstName}}, it''s back!</h1>
<p><strong>{{.productTitle}}</strong> is back in stock. Popular items go fast, so grab yours while you can.</p>
{{button .productUrl "Shop now"}}
<p><small>You asked us to tell you when this item was back. This alert is now done; set a new one from the product page.</small></p>',
 'Good news, {{.firstName}}! {{.productTitle}} is back in stock: {{.productUrl}}'),
('order.confirmed', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'order.confirmed' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: order {{.orderNumber}} confirmed ({{.total}}), arriving {{.estimatedDelivery}}. {{with index . "smsOrderUrl"}}{{.}} {{end}}Reply STOP to opt out'),
('shipment.out_for_delivery', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.out_for_delivery' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: order {{.orderNumber}} is out for delivery today. {{with index . "smsOrderUrl"}}Track: {{.}} {{end}}Reply STOP to opt out'),
('shipment.ready_for_pickup', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.ready_for_pickup' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: order {{.orderNumber}} is ready for pickup. {{with index . "smsOrderUrl"}}Details: {{.}} {{end}}Reply STOP to opt out'),
('shipment.delivery_failed', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.delivery_failed' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: we could not deliver order {{.orderNumber}}. {{with index . "smsOrderUrl"}}Options: {{.}} {{end}}Reply STOP to opt out'),
('booking.confirmed', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.confirmed' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: {{.serviceName}} with {{.providerName}} confirmed for {{.startsAt}}. {{.manageUrl}} Reply STOP to opt out'),
('booking.rescheduled', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'booking.rescheduled' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: {{.serviceName}} with {{.providerName}} moved to {{.startsAt}}. {{.manageUrl}} Reply STOP to opt out');
