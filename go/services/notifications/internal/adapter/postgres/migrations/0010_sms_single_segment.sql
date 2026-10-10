-- Template gallery SMS findings: seller.suspended and payout.requirement_due rendered over 160 GSM-7 characters
-- (two segments). Shorter wording, same fields; each row becomes the next version of its key/locale/channel.
-- (booking.* SMS were UCS-2 because startsAt carried "·": the ingest now normalises bookings' display strings to
-- GSM-7, e.g. "Sat, Oct 17 at 10:00 AM", so their templates are unchanged.)

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('seller.suspended', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'seller.suspended' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: seller account suspended ({{.reasonText}}). Listings hidden, payouts held. {{.actionUrl}} Reply STOP to opt out'),
('payout.requirement_due', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payout.requirement_due' AND t.locale = 'en' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: by {{.dueDate}}: {{.requirement}} to keep payouts on. {{.actionUrl}} Reply STOP to opt out');
