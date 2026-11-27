-- A seller plan charge needs 3-D Secure off-session (seller.billing.payment_action_required on
-- at.seller-subscription.events): the owners confirm it in Plan & billing (Confirm payment) before the deadline.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('seller.billing.payment_action_required', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.billing.payment_action_required' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Action needed: confirm your AfroTransact plan payment',
 '{{define "preheader"}}Your bank asks you to confirm the {{.amount}} plan payment.{{end}}
{{badge "warning" "Action needed"}}
<h1>Confirm your plan payment</h1>
<p>Hi {{.firstName}}, your bank asks you to confirm the <strong>{{.amount}}</strong> payment for {{.invoiceNumber}}. Open Plan &amp; billing and choose <strong>Confirm payment</strong>; your bank will show its check.</p>
{{if .confirmByLine}}<p>{{.confirmByLine}}</p>{{end}}
{{button .actionUrl "Confirm payment"}}',
 'Hi {{.firstName}}, your bank asks you to confirm the {{.amount}} payment for {{.invoiceNumber}}. Open Plan & billing and choose Confirm payment. {{.confirmByLine}} {{.actionUrl}}'),
('seller.billing.payment_action_required', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.billing.payment_action_required' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Action needed',
 '',
 'Confirm your {{.amount}} AfroTransact plan payment in Plan & billing.');
