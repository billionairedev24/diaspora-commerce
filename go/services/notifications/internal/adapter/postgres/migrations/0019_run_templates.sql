-- S262 (ADR 0005): delivery runs. delivery-runs publishes delivery-runs.run.announced / .opened / .cutoff_reminder /
-- .confirmed / .cancelled / .out_for_delivery and delivery-runs.subscription.created / .confirmed on at.delivery-runs.events.
--   run.announced, run.opened, run.cutoff_reminder   → the location's subscribers, per channel they consented to
--   run.out_for_delivery (dispatch), run.confirmed   → the customers on the run (their own handover code at a pickup point)
--   run.cancelled                                    → the customers on a run staff scheduled: refunded in full, fee included
--   run.subscription.confirm / .welcome              → double opt-in link, and the welcome with the WhatsApp community
-- Every link is the run link with UTM per channel (utm_source = utm_medium = the channel, utm_campaign =
-- delivery-run-<slug>-<yyyymmdd>, utm_content = the event). SMS carry the location page with the same UTM (the run id
-- is dropped to keep the text short, so they run to two GSM-7 segments at most).
-- WhatsApp (channel chat) rows are used only when deliveryRuns.whatsapp.enabled is set: subject = the name of a
-- template approved in Meta Business Manager, each body line = one template parameter, in this order:
--   afrotransact_run_opened and afrotransact_run_cutoff_reminder: {{1}} town, {{2}} delivery day, {{3}} cutoff,
--   {{4}} delivery fee, {{5}} link. Create and get those two templates approved (category UTILITY or MARKETING,
--   language en) before switching the flag on. Each row becomes the next version of its key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('run.announced', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.announced' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'A delivery run to {{.town}} on {{.deliveryDay}}',
 '{{define "preheader"}}We are driving to {{.town}} on {{.deliveryDay}}. Ordering opens soon.{{end}}
{{badge "info" "Delivery run announced"}}
<h1>We are coming to {{.town}}</h1>
<p>Hi {{.firstName}}, an AfroTransact delivery run to <strong>{{.town}}</strong> is planned for <strong>{{.deliveryDay}}</strong>. We will tell you the moment ordering opens.</p>
{{facts "Delivery day" .deliveryDay "Delivery window" .windowDisplay "Order by" .cutoffDisplay "Delivery fee" .feeDisplay "How" .dropText}}
{{button .emailUrl "See the run"}}
{{if .whatsappInviteUrl}}<p><small>Join the {{.town}} WhatsApp community for updates from neighbours and our drivers: <a href="{{.whatsappInviteUrl}}">join</a>.</small></p>{{end}}',
 'Hi {{.firstName}}, an AfroTransact delivery run to {{.town}} is planned for {{.deliveryDay}} ({{.windowDisplay}}). Order by {{.cutoffDisplay}}; delivery is {{.feeDisplay}}. {{.dropText}}.
We will tell you the moment ordering opens.
See the run: {{.emailUrl}}{{if .unsubscribeUrl}}

Unsubscribe from {{.town}} run alerts: {{.unsubscribeUrl}}{{end}}'),
('run.announced', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.announced' AND t.locale = 'en' AND t.channel = 'push'), 'reminder',
 'Delivery run to {{.town}}',
 '',
 'We are coming to {{.town}} on {{.deliveryDay}}. Ordering opens soon.'),

('run.opened', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'The {{.town}} delivery run is open: order by {{.cutoffText}}',
 '{{define "preheader"}}Order by {{.cutoffText}}. We deliver {{.deliveryDay}}, {{.windowText}}.{{end}}
{{badge "warning" "Delivery run open"}}
<h1>The {{.town}} delivery run is open</h1>
<p>Hi {{.firstName}}, we are bringing AfroTransact orders to {{.town}} on <strong>{{.deliveryDay}}</strong>. Order before the cutoff and yours goes on the same trip.</p>
{{facts "Delivery day" .deliveryDay "Delivery window" .windowDisplay "Order by" .cutoffDisplay "Delivery fee" .feeDisplay "How" .dropText "Orders so far" .minimumText}}
{{button .emailUrl "See the run"}}
{{if .whatsappInviteUrl}}<p><small>Join the {{.town}} WhatsApp community for updates from neighbours and our drivers: <a href="{{.whatsappInviteUrl}}">join</a>.</small></p>{{end}}',
 'Hi {{.firstName}}, the {{.town}} delivery run is open. We deliver on {{.deliveryDay}} ({{.windowDisplay}}). Order by {{.cutoffDisplay}}; delivery is {{.feeDisplay}}. {{.dropText}}.
See the run: {{.emailUrl}}{{if .unsubscribeUrl}}

Unsubscribe from {{.town}} run alerts: {{.unsubscribeUrl}}{{end}}'),
('run.opened', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'en' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: the {{.town}} delivery run is open. We deliver {{.deliveryDay}}. Order by {{.cutoffText}}. {{.smsUrl}} Reply STOP to opt out'),
('run.opened', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'en' AND t.channel = 'push'), 'reminder',
 'The {{.town}} delivery run is open',
 '',
 'Order by {{.cutoffText}}. Delivered {{.deliveryDay}}.{{if .feeDisplay}} Delivery {{.feeDisplay}}.{{end}}'),
('run.opened', 'en', 'chat', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'en' AND t.channel = 'chat'), 'reminder',
 'afrotransact_run_opened',
 '',
 '{{.town}}
{{.deliveryDay}}
{{.cutoffText}}
{{.feeDisplay}}
{{.whatsappUrl}}'),

('run.cutoff_reminder', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'en' AND t.channel = 'email'), 'reminder',
 'Last chance for the {{.town}} run: order by {{.cutoffText}}',
 '{{define "preheader"}}The {{.town}} run closes {{.cutoffText}}. We deliver {{.deliveryDay}}.{{end}}
{{badge "warning" "Closing soon"}}
<h1>The {{.town}} run closes soon</h1>
<p>Hi {{.firstName}}, ordering for the {{.town}} delivery run closes on <strong>{{.cutoffDisplay}}</strong>. We deliver on <strong>{{.deliveryDay}}</strong>.</p>
{{facts "Order by" .cutoffDisplay "Delivery day" .deliveryDay "Delivery window" .windowDisplay "Delivery fee" .feeDisplay "How" .dropText "Orders so far" .minimumText}}
{{button .emailUrl "See the run"}}',
 'Hi {{.firstName}}, ordering for the {{.town}} delivery run closes on {{.cutoffDisplay}}. We deliver on {{.deliveryDay}} ({{.windowDisplay}}); delivery is {{.feeDisplay}}.
See the run: {{.emailUrl}}{{if .unsubscribeUrl}}

Unsubscribe from {{.town}} run alerts: {{.unsubscribeUrl}}{{end}}'),
('run.cutoff_reminder', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'en' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: last chance for the {{.town}} run. Order by {{.cutoffText}}. We deliver {{.deliveryDay}}. {{.smsUrl}} Reply STOP to opt out'),
('run.cutoff_reminder', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'en' AND t.channel = 'push'), 'reminder',
 'Last chance for the {{.town}} run',
 '',
 'Order by {{.cutoffText}}. Delivered {{.deliveryDay}}.'),
('run.cutoff_reminder', 'en', 'chat', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'en' AND t.channel = 'chat'), 'reminder',
 'afrotransact_run_cutoff_reminder',
 '',
 '{{.town}}
{{.deliveryDay}}
{{.cutoffText}}
{{.feeDisplay}}
{{.whatsappUrl}}'),

('run.out_for_delivery', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your {{.town}} delivery is on its way today',
 '{{define "preheader"}}We left for {{.town}}. Delivery window {{.windowText}}.{{end}}
{{badge "success" "On its way"}}
<h1>We are on our way to {{.town}}</h1>
<p>Hi {{.firstName}}, the {{.town}} delivery run has left. Your order arrives today, {{.windowDisplay}}.</p>
{{facts "Delivery window" .windowDisplay "How" .dropText "Handover code" .handoverCode}}
{{if .handoverCode}}<p>Show this code when you collect your order: <strong>{{.handoverCode}}</strong>.</p>{{end}}
{{button .emailUrl "Follow the run"}}',
 'Hi {{.firstName}}, the {{.town}} delivery run has left. Your order arrives today, {{.windowDisplay}}. {{.dropText}}.{{if .handoverCode}} Handover code: {{.handoverCode}}.{{end}}
Follow the run: {{.emailUrl}}'),
('run.out_for_delivery', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: your {{.town}} order is out for delivery today, {{.windowText}}.{{if .handoverCode}} Handover code {{.handoverCode}}.{{end}} Reply STOP to opt out'),
('run.out_for_delivery', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Your {{.town}} order is on its way',
 '',
 'Arriving today, {{.windowText}}.{{if .handoverCode}} Handover code {{.handoverCode}}.{{end}}'),

('run.confirmed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.confirmed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'The {{.town}} run is on: delivering {{.deliveryDay}}',
 '{{define "preheader"}}Your order is confirmed for {{.deliveryDay}}, {{.windowText}}.{{end}}
{{badge "success" "Run confirmed"}}
<h1>The {{.town}} run is on</h1>
<p>Hi {{.firstName}}, enough orders came in, so the {{.town}} run goes ahead. Your order is confirmed for <strong>{{.deliveryDay}}</strong>.</p>
{{facts "Delivery day" .deliveryDay "Delivery window" .windowDisplay "How" .dropText "Handover code" .handoverCode}}
{{button .emailUrl "View the run"}}',
 'Hi {{.firstName}}, the {{.town}} run goes ahead. Your order is confirmed for {{.deliveryDay}} ({{.windowDisplay}}). {{.dropText}}.{{if .handoverCode}} Handover code: {{.handoverCode}}.{{end}}
View the run: {{.emailUrl}}'),
('run.confirmed', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.confirmed' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'The {{.town}} run is on',
 '',
 'Your order is confirmed for {{.deliveryDay}}, {{.windowText}}.'),

('run.cancelled', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'The {{.town}} delivery run on {{.deliveryDay}} is cancelled',
 '{{define "preheader"}}You are refunded in full, delivery fee included.{{end}}
{{badge "danger" "Run cancelled"}}
<h1>We cancelled the {{.town}} run</h1>
<p>Hi {{.firstName}}, we are sorry: the {{.town}} delivery run planned for <strong>{{.deliveryDay}}</strong> will not happen. {{.reasonText}}</p>
{{callout "info" .refundNote}}
{{button .locationUrl "See the next run"}}',
 'Hi {{.firstName}}, we are sorry: the {{.town}} delivery run planned for {{.deliveryDay}} will not happen. {{.reasonText}}
{{.refundNote}}
See the next run: {{.locationUrl}}'),
('run.cancelled', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: we cancelled the {{.town}} run for {{.deliveryDay}}. Your order is refunded in full, delivery fee included. Reply STOP to opt out'),
('run.cancelled', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'The {{.town}} run is cancelled',
 '',
 'You are refunded in full, delivery fee included.'),

('run.subscription.confirm', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.subscription.confirm' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Confirm your {{.town}} delivery run alerts',
 '{{define "preheader"}}One click and we will tell you when a run to {{.town}} opens.{{end}}
<h1>One quick step</h1>
<p>Confirm your email to hear first when AfroTransact opens a delivery run to <strong>{{.town}}</strong>: when ordering opens, when it is about to close, and when we are on the way. Nothing is sent until you confirm.</p>
{{button .confirmUrl "Confirm my alerts"}}
<p><small>Did not ask for this? Ignore this email and nothing will be sent.</small></p>',
 'Confirm your email to hear first when AfroTransact opens a delivery run to {{.town}}: when ordering opens, when it is about to close, and when we are on the way. Nothing is sent until you confirm.

Confirm my alerts: {{.confirmUrl}}

Did not ask for this? Ignore this email and nothing will be sent.'),
('run.subscription.welcome', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.subscription.welcome' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'You will hear first about {{.town}} delivery runs',
 '{{define "preheader"}}You are subscribed to {{.town}} run alerts by {{.channelList}}.{{end}}
{{badge "success" "Subscribed"}}
<h1>You are on the list for {{.town}}</h1>
<p>Hi {{.firstName}}, we will tell you by {{.channelList}} when a delivery run to <strong>{{.town}}</strong> opens, when it is about to close and when we are on the way.</p>
{{button .locationUrl "See upcoming runs"}}
{{if .hasWhatsappCommunity}}<p>Neighbours and our drivers swap updates in the {{.town}} WhatsApp community: <a href="{{.whatsappInviteUrl}}">join the community</a>.</p>{{end}}
<p><small>You can change this or stop any time from your account, or with the unsubscribe link in every message.</small></p>',
 'Hi {{.firstName}}, we will tell you by {{.channelList}} when a delivery run to {{.town}} opens, when it is about to close and when we are on the way.
See upcoming runs: {{.locationUrl}}{{if .hasWhatsappCommunity}}
Join the {{.town}} WhatsApp community: {{.whatsappInviteUrl}}{{end}}
Unsubscribe any time: {{.unsubscribeUrl}}');
