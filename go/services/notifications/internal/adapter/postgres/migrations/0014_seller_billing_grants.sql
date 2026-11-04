-- S296–S297: AfroTransact's own rewards and billing adjustments (seller.billing.grant_applied on
-- at.seller-subscription.events). A reward rule's grant thanks the seller for the milestone; a staff adjustment
-- (credit, free days, discount, waived invoice, trial change) says what changed. Both link to Plan & billing.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('seller.billing.reward_earned', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.billing.reward_earned' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'You earned a reward: {{.summary}}',
 '{{define "preheader"}}{{.reason}}: {{.summary}} on your AfroTransact plan.{{end}}
{{badge "success" "Reward earned"}}
<h1>{{.summary}}, on us</h1>
<p>Congratulations, {{.firstName}}! You reached <strong>{{.reason}}</strong>, so we added <strong>{{.summary}}</strong> to your plan. There is nothing to do: it applies automatically.</p>
{{if .trialLine}}<p>{{.trialLine}}</p>{{end}}
{{button .actionUrl "See your plan and rewards"}}',
 'Congratulations, {{.firstName}}! You reached {{.reason}}, so we added {{.summary}} to your plan. {{.trialLine}} See it: {{.actionUrl}}'),
('seller.billing.reward_earned', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.billing.reward_earned' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Reward earned',
 '',
 'You earned {{.summary}} on your AfroTransact plan.'),
('seller.billing.credit_granted', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.billing.credit_granted' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your AfroTransact plan: {{.summary}}',
 '{{define "preheader"}}{{.summary}} on your AfroTransact plan.{{end}}
{{badge "success" "Billing update"}}
<h1>{{.summary}}</h1>
<p>Hi {{.firstName}}, our team applied <strong>{{.summary}}</strong> to your AfroTransact plan.{{if .creditLine}} {{.creditLine}}{{end}}</p>
{{if .trialLine}}<p>{{.trialLine}}</p>{{end}}
{{button .actionUrl "View plan & billing"}}',
 'Hi {{.firstName}}, our team applied {{.summary}} to your AfroTransact plan. {{.creditLine}} {{.trialLine}} {{.actionUrl}}');
