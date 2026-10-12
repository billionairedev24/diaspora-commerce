-- Bulk product upload (S308): catalog.import.completed with notifySeller=true (uploads of 100 rows or more) tells the
-- member who uploaded the file that the import finished, with the counts and a link to the upload's report.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('catalog.import_completed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'catalog.import_completed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your bulk upload {{.reference}} has finished',
 '{{define "preheader"}}{{.createdProducts}} created, {{.updatedProducts}} updated{{if .hasProblems}}, {{.problemRows}} rows need attention{{end}}.{{end}}
<h1>Your bulk upload has finished</h1>
<p>Hi {{.firstName}}, we finished importing <strong>{{.fileName}}</strong> ({{.reference}}).</p>
<p><strong>{{.createdProducts}}</strong> products created and <strong>{{.updatedProducts}}</strong> updated from {{.totalRows}} rows. New products go to review before they appear on the storefront.</p>
{{if .hasProblems}}<p>{{.problemRows}} rows were not imported. Download the report: it lists every row with the reason in an Errors column, so you can fix them and upload them again.</p>{{end}}
{{button .reportUrl "View the upload report"}}',
 'Hi {{.firstName}}, your bulk upload {{.fileName}} ({{.reference}}) has finished: {{.createdProducts}} products created and {{.updatedProducts}} updated from {{.totalRows}} rows.{{if .hasProblems}} {{.problemRows}} rows were not imported; the report lists the reasons.{{end}} {{.reportUrl}}'),
('catalog.import_completed', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'catalog.import_completed' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Bulk upload finished',
 '',
 '{{.reference}}: {{.createdProducts}} created, {{.updatedProducts}} updated{{if .hasProblems}}, {{.problemRows}} rows need attention{{end}}.');
