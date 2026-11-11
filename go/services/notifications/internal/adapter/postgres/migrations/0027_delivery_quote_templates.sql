-- Delivery quotes (listing rules D20; docs/runbooks/delivery-quotes.md): orders publishes orders.delivery-quote.ready (the
-- agreed charge, when it ends) and orders.delivery-quote.declined; notifications emails the shopper in their own language
-- (en, fr, es, ar, yo). The ready email carries the pay link: back to checkout (/checkout?quote=<id>), where the quote is
-- applied to their cart and address; there is no separate payment page. Declined never shows the staff reason.
-- needsReview: the fr, es, ar and yo copy is machine translated and must be reviewed by a translator before it is relied on
-- (the same state as the storefront catalogues, whose $meta.needsReview is true). Each row becomes the next version of its
-- key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('delivery_quote.ready', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'delivery_quote.ready' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your delivery quote is ready: {{.amount}}',
 '{{define "preheader"}}Delivery to your address will cost {{.amount}}. Use it before {{.expiresDisplay}}.{{end}}
{{badge "success" "Delivery quote"}}
<h1>Your delivery quote is ready</h1>
<p>We priced the delivery to your address. Delivery will cost <strong>{{.amount}}</strong>.</p>
<p>Go back to checkout to use it. It is for the cart and address you asked about, can be used once, and ends on <strong>{{.expiresDisplay}}</strong>. If you change your cart or address, ask again.</p>
{{button .payUrl "Back to checkout"}}
<p><small>{{.reference}}</small></p>',
 'We priced the delivery to your address. Delivery will cost {{.amount}}.

Go back to checkout to use it. It is for the cart and address you asked about, can be used once, and ends on {{.expiresDisplay}}. If you change your cart or address, ask again.

Back to checkout: {{.payUrl}}

{{.reference}}'),
('delivery_quote.declined', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'delivery_quote.declined' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'About your delivery quote {{.reference}}',
 '{{define "preheader"}}We cannot offer delivery to your address right now.{{end}}
{{badge "info" "Delivery quote"}}
<h1>We cannot deliver to your address right now</h1>
<p>Thank you for asking. We looked at the delivery to {{if .place}}{{.place}}{{else}}your address{{end}} and cannot offer it right now.</p>
<p>You can still choose pickup at checkout, or try another address.</p>
{{button .pickupUrl "Back to checkout"}}
<p><small>{{.reference}}</small></p>',
 'Thank you for asking. We looked at the delivery to {{if .place}}{{.place}}{{else}}your address{{end}} and cannot offer it right now.

You can still choose pickup at checkout, or try another address.

{{.pickupUrl}}

{{.reference}}'),
('delivery_quote.ready', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'delivery_quote.ready' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 'Votre devis de livraison est prêt : {{.amount}}',
 '{{define "preheader"}}La livraison à votre adresse coûtera {{.amount}}. Utilisez-le avant le {{.expiresDisplay}}.{{end}}
{{badge "success" "Devis de livraison"}}
<h1>Votre devis de livraison est prêt</h1>
<p>Nous avons chiffré la livraison à votre adresse. Elle coûtera <strong>{{.amount}}</strong>.</p>
<p>Retournez au paiement pour lutiliser. Il vaut pour le panier et ladresse que vous avez indiqués, ne peut servir quune fois et prend fin le <strong>{{.expiresDisplay}}</strong>. Si vous modifiez votre panier ou votre adresse, faites une nouvelle demande.</p>
{{button .payUrl "Retour au paiement"}}
<p><small>{{.reference}}</small></p>',
 'Nous avons chiffré la livraison à votre adresse. Elle coûtera {{.amount}}.

Retournez au paiement pour lutiliser. Il vaut pour le panier et ladresse que vous avez indiqués, ne peut servir quune fois et prend fin le {{.expiresDisplay}}. Si vous modifiez votre panier ou votre adresse, faites une nouvelle demande.

Retour au paiement: {{.payUrl}}

{{.reference}}'),
('delivery_quote.declined', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'delivery_quote.declined' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 'À propos de votre devis de livraison {{.reference}}',
 '{{define "preheader"}}Nous ne pouvons pas livrer à votre adresse pour le moment.{{end}}
{{badge "info" "Devis de livraison"}}
<h1>Nous ne pouvons pas livrer à votre adresse pour le moment</h1>
<p>Merci de votre demande. Nous avons étudié la livraison vers {{if .place}}{{.place}}{{else}}votre adresse{{end}} et ne pouvons pas la proposer pour le moment.</p>
<p>Vous pouvez toujours choisir le retrait au moment du paiement, ou essayer une autre adresse.</p>
{{button .pickupUrl "Retour au paiement"}}
<p><small>{{.reference}}</small></p>',
 'Merci de votre demande. Nous avons étudié la livraison vers {{if .place}}{{.place}}{{else}}votre adresse{{end}} et ne pouvons pas la proposer pour le moment.

Vous pouvez toujours choisir le retrait au moment du paiement, ou essayer une autre adresse.

{{.pickupUrl}}

{{.reference}}'),
('delivery_quote.ready', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'delivery_quote.ready' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 'Su cotización de envío está lista: {{.amount}}',
 '{{define "preheader"}}El envío a su dirección costará {{.amount}}. Úsela antes del {{.expiresDisplay}}.{{end}}
{{badge "success" "Cotización de envío"}}
<h1>Su cotización de envío está lista</h1>
<p>Calculamos el envío a su dirección. Costará <strong>{{.amount}}</strong>.</p>
<p>Vuelva al pago para usarla. Vale para el carrito y la dirección que indicó, se puede usar una sola vez y termina el <strong>{{.expiresDisplay}}</strong>. Si cambia el carrito o la dirección, pida una nueva.</p>
{{button .payUrl "Volver al pago"}}
<p><small>{{.reference}}</small></p>',
 'Calculamos el envío a su dirección. Costará {{.amount}}.

Vuelva al pago para usarla. Vale para el carrito y la dirección que indicó, se puede usar una sola vez y termina el {{.expiresDisplay}}. Si cambia el carrito o la dirección, pida una nueva.

Volver al pago: {{.payUrl}}

{{.reference}}'),
('delivery_quote.declined', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'delivery_quote.declined' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 'Sobre su cotización de envío {{.reference}}',
 '{{define "preheader"}}Por ahora no podemos enviar a su dirección.{{end}}
{{badge "info" "Cotización de envío"}}
<h1>Por ahora no podemos enviar a su dirección</h1>
<p>Gracias por preguntar. Revisamos el envío a {{if .place}}{{.place}}{{else}}su dirección{{end}} y por ahora no podemos ofrecerlo.</p>
<p>Todavía puede elegir la recogida al pagar, o probar con otra dirección.</p>
{{button .pickupUrl "Volver al pago"}}
<p><small>{{.reference}}</small></p>',
 'Gracias por preguntar. Revisamos el envío a {{if .place}}{{.place}}{{else}}su dirección{{end}} y por ahora no podemos ofrecerlo.

Todavía puede elegir la recogida al pagar, o probar con otra dirección.

{{.pickupUrl}}

{{.reference}}'),
('delivery_quote.ready', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'delivery_quote.ready' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 'عرض سعر التوصيل جاهز: {{.amount}}',
 '{{define "preheader"}}سيكلّف التوصيل إلى عنوانك {{.amount}}. استخدمه قبل {{.expiresDisplay}}.{{end}}
{{badge "success" "عرض سعر التوصيل"}}
<h1>عرض سعر التوصيل جاهز</h1>
<p>حسبنا تكلفة التوصيل إلى عنوانك. ستكون <strong>{{.amount}}</strong>.</p>
<p>ارجع إلى صفحة الدفع لاستخدامه. العرض خاص بالسلة والعنوان اللذين سألت عنهما، ويُستخدم مرة واحدة فقط، وينتهي في <strong>{{.expiresDisplay}}</strong>. إذا غيّرت السلة أو العنوان فاطلب عرضًا جديدًا.</p>
{{button .payUrl "العودة إلى الدفع"}}
<p><small>{{.reference}}</small></p>',
 'حسبنا تكلفة التوصيل إلى عنوانك. ستكون {{.amount}}.

ارجع إلى صفحة الدفع لاستخدامه. العرض خاص بالسلة والعنوان اللذين سألت عنهما، ويُستخدم مرة واحدة فقط، وينتهي في {{.expiresDisplay}}. إذا غيّرت السلة أو العنوان فاطلب عرضًا جديدًا.

العودة إلى الدفع: {{.payUrl}}

{{.reference}}'),
('delivery_quote.declined', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'delivery_quote.declined' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 'بخصوص عرض سعر التوصيل {{.reference}}',
 '{{define "preheader"}}لا يمكننا التوصيل إلى عنوانك حاليًا.{{end}}
{{badge "info" "عرض سعر التوصيل"}}
<h1>لا يمكننا التوصيل إلى عنوانك حاليًا</h1>
<p>شكرًا على سؤالك. درسنا التوصيل إلى {{if .place}}{{.place}}{{else}}عنوانك{{end}} ولا يمكننا تقديمه حاليًا.</p>
<p>ما زال بإمكانك اختيار الاستلام من نقطة استلام عند الدفع، أو تجربة عنوان آخر.</p>
{{button .pickupUrl "العودة إلى الدفع"}}
<p><small>{{.reference}}</small></p>',
 'شكرًا على سؤالك. درسنا التوصيل إلى {{if .place}}{{.place}}{{else}}عنوانك{{end}} ولا يمكننا تقديمه حاليًا.

ما زال بإمكانك اختيار الاستلام من نقطة استلام عند الدفع، أو تجربة عنوان آخر.

{{.pickupUrl}}

{{.reference}}'),
('delivery_quote.ready', 'yo', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'delivery_quote.ready' AND t.locale = 'yo' AND t.channel = 'email'), 'transactional',
 'Ìdíyelé ìfijíṣẹ́ rẹ ti ṣetán: {{.amount}}',
 '{{define "preheader"}}Ìfijíṣẹ́ sí àdírẹ́sì rẹ yóò jẹ́ {{.amount}}. Lò ó kí {{.expiresDisplay}} tó pé.{{end}}
{{badge "success" "Ìdíyelé ìfijíṣẹ́"}}
<h1>Ìdíyelé ìfijíṣẹ́ rẹ ti ṣetán</h1>
<p>A ti ṣírò iye ìfijíṣẹ́ sí àdírẹ́sì rẹ. Yóò jẹ́ <strong>{{.amount}}</strong>.</p>
<p>Padà sí ibi ìsanwó láti lò ó. Ó wà fún àpótí ọjà àti àdírẹ́sì tí o béèrè nípa wọn, a lè lò ó lẹ́ẹ̀kan péré, yóò sì parí ní <strong>{{.expiresDisplay}}</strong>. Tí o bá yí àpótí ọjà tàbí àdírẹ́sì padà, béèrè ìdíyelé tuntun.</p>
{{button .payUrl "Padà sí ibi ìsanwó"}}
<p><small>{{.reference}}</small></p>',
 'A ti ṣírò iye ìfijíṣẹ́ sí àdírẹ́sì rẹ. Yóò jẹ́ {{.amount}}.

Padà sí ibi ìsanwó láti lò ó. Ó wà fún àpótí ọjà àti àdírẹ́sì tí o béèrè nípa wọn, a lè lò ó lẹ́ẹ̀kan péré, yóò sì parí ní {{.expiresDisplay}}. Tí o bá yí àpótí ọjà tàbí àdírẹ́sì padà, béèrè ìdíyelé tuntun.

Padà sí ibi ìsanwó: {{.payUrl}}

{{.reference}}'),
('delivery_quote.declined', 'yo', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'delivery_quote.declined' AND t.locale = 'yo' AND t.channel = 'email'), 'transactional',
 'Nípa ìdíyelé ìfijíṣẹ́ rẹ {{.reference}}',
 '{{define "preheader"}}A kò lè fi ọjà ráńṣẹ́ sí àdírẹ́sì rẹ báyìí.{{end}}
{{badge "info" "Ìdíyelé ìfijíṣẹ́"}}
<h1>A kò lè fi ọjà ráńṣẹ́ sí àdírẹ́sì rẹ báyìí</h1>
<p>A dúpẹ́ pé o béèrè. A ti wo ìfijíṣẹ́ sí {{if .place}}{{.place}}{{else}}àdírẹ́sì rẹ{{end}} a kò sì lè ṣe é báyìí.</p>
<p>O ṣì lè yan láti gba ọjà ní ibi ìgbàọjà nígbà ìsanwó, tàbí gbìyànjú àdírẹ́sì mìíràn.</p>
{{button .pickupUrl "Padà sí ibi ìsanwó"}}
<p><small>{{.reference}}</small></p>',
 'A dúpẹ́ pé o béèrè. A ti wo ìfijíṣẹ́ sí {{if .place}}{{.place}}{{else}}àdírẹ́sì rẹ{{end}} a kò sì lè ṣe é báyìí.

O ṣì lè yan láti gba ọjà ní ibi ìgbàọjà nígbà ìsanwó, tàbí gbìyànjú àdírẹ́sì mìíràn.

{{.pickupUrl}}

{{.reference}}');
