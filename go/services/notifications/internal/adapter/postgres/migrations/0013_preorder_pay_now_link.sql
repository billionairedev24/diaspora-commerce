-- Late pre-order adds: the SMS link now opens AfroTransact's own pay page (/orders/<number>/pay?token=…, Stripe
-- Elements) instead of a Stripe-hosted Checkout page. The signed link is long, so the text is shorter (one GSM-7
-- segment; the delivery date is on the page). The row becomes the next version of the key.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('preorder.late_order.payment_link', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.late_order.payment_link' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: pay {{.total}} for your pre-order: {{.paymentUrl}} Reply STOP to opt out');
