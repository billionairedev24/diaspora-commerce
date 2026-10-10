-- S243: retail's supplier purchase-order email (notification.requested on at.retail.events, templateKey
-- retail.purchase_order.sent, channel email, recipient = the supplier contact's address, never looked up in identity).
-- Fields from retail: contactName, poNumber, supplierName, orderDate, deliverBy, incoterm, paymentTerms, total,
-- documentUrl, documentExpiresAt; the ingest derives documentExpiresOn and turns nulls into "". The PDF is attached
-- when the provider supports attachments; the link is always in the body as the fallback.
-- Becomes the next version of its key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('retail.purchase_order.sent', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'retail.purchase_order.sent' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Purchase order {{.poNumber}} from AfroTransact Retail',
 '<!doctype html><html><body style="font-family:Figtree,Arial,sans-serif;background:#ffffff;color:#111">
<h1 style="font-size:20px">Purchase order {{.poNumber}}</h1>
<p>Hello {{.contactName}},</p>
<p>AfroTransact Retail has issued purchase order <strong>{{.poNumber}}</strong> to {{.supplierName}}.</p>
<table style="border-collapse:collapse;font-size:14px">
<tr><td style="padding:4px 16px 4px 0;color:#555">Order date</td><td style="padding:4px 0">{{.orderDate}}</td></tr>
{{if .deliverBy}}<tr><td style="padding:4px 16px 4px 0;color:#555">Deliver by</td><td style="padding:4px 0">{{.deliverBy}}</td></tr>{{end}}
{{if .incoterm}}<tr><td style="padding:4px 16px 4px 0;color:#555">Incoterm</td><td style="padding:4px 0">{{.incoterm}}</td></tr>{{end}}
{{if .paymentTerms}}<tr><td style="padding:4px 16px 4px 0;color:#555">Payment terms</td><td style="padding:4px 0">{{.paymentTerms}}</td></tr>{{end}}
<tr><td style="padding:4px 16px 4px 0;color:#555">Total</td><td style="padding:4px 0"><strong>{{.total}}</strong></td></tr>
</table>
<p>The purchase order PDF is attached to this email. If you can''t see the attachment, download it here:</p>
<p><a href="{{.documentUrl}}" style="color:#111;font-weight:700">Download purchase order {{.poNumber}} (PDF)</a>{{if .documentExpiresOn}}<br><span style="color:#555;font-size:13px">This link works until {{.documentExpiresOn}}.</span>{{end}}</p>
<p>Please confirm the order and your expected ship date with your AfroTransact Retail buyer.</p>
</body></html>',
 'Hello {{.contactName}},

AfroTransact Retail has issued purchase order {{.poNumber}} to {{.supplierName}}.

Order date: {{.orderDate}}
{{if .deliverBy}}Deliver by: {{.deliverBy}}
{{end}}{{if .incoterm}}Incoterm: {{.incoterm}}
{{end}}{{if .paymentTerms}}Payment terms: {{.paymentTerms}}
{{end}}Total: {{.total}}

The purchase order PDF is attached. If you can''t see it, download it here{{if .documentExpiresOn}} (link valid until {{.documentExpiresOn}}){{end}}:
{{.documentUrl}}

Please confirm the order and your expected ship date with your AfroTransact Retail buyer.');
