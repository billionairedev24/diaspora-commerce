-- "You're approved" (seller.application.approved): the button read "Open {{.programName}}" literally, because a
-- template action inside a string argument is not evaluated. The label is now built with printf.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('seller.application.approved', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.application.approved' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'You''re approved! Welcome to {{.programName}}',
 '{{define "preheader"}}{{.displayName}} is approved. Set up your storefront.{{end}}
{{badge "success" "Approved"}}
<h1>Congratulations, {{.firstName}}!</h1>
<p><strong>{{.displayName}}</strong> is approved. Sign in to {{.programName}} to set up your storefront and start reaching customers across the diaspora.</p>
{{button .portalUrl (printf "Open %s" .programName)}}',
 'Congratulations, {{.firstName}}! {{.displayName}} is approved. Open {{.programName}}: {{.portalUrl}}');
