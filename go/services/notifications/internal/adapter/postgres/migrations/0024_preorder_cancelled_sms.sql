-- S262: a pre-order AfroTransact cancelled (its window, or the delivery run it was handed to) is texted as well as emailed,
-- the way a cancelled run is: orders.shipment.cancelled of a PREORDER shipment whose actor is not the customer sends this
-- text. smsRefundNote is "Refunded in full, delivery fee included: <total>." once the payment was captured, or that the
-- customer was not charged and the card hold is released. One GSM-7 segment. The push says the same (it only named the
-- order before). Each row becomes the next version of its
-- key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('shipment.cancelled', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.cancelled' AND t.locale = 'en' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: we cancelled your pre-order {{.orderNumber}}. {{.smsRefundNote}} Reply STOP to opt out'),
('shipment.cancelled', 'en', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'shipment.cancelled' AND t.locale = 'en' AND t.channel = 'push'), 'transactional',
 'Shipment cancelled',
 '',
 'A shipment from order {{.orderNumber}} was cancelled. {{.smsRefundNote}}');
