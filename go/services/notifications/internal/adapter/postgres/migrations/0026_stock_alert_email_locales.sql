-- Back-in-stock email in the storefront's five locales (en, fr, es, ar, yo), the locale the shopper had when they
-- subscribed. Guests have no first name and may have no product title, so both are optional. Every email carries
-- alertUnsubscribeUrl: a signed one-click link that removes this one alert (no login). The email category stays
-- transactional (the shopper asked for exactly this message); the layout's footer is the transactional one.
-- Each row is the next version of its key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('stock.back_in_stock', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'stock.back_in_stock' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{with index . "productTitle"}}It''s back: {{.}}{{else}}Your item is back in stock{{end}}',
 '{{define "preheader"}}Your item is back in stock. Popular items go fast.{{end}}
{{badge "success" "Back in stock"}}
<h1>{{with index . "firstName"}}Good news, {{.}}, it''s back!{{else}}Good news, it''s back!{{end}}</h1>
<p>{{with index . "productTitle"}}<strong>{{.}}</strong>{{else}}The item you were waiting for{{end}} is back in stock. Popular items go fast, so grab yours while you can.</p>
{{button .productUrl "Shop now"}}
<p><small>You asked us to tell you when this item was back, so this is the only email you will get about it. <a href="{{.alertUnsubscribeUrl}}">Stop this alert</a>.</small></p>',
 'Good news{{with index . "firstName"}}, {{.}}{{end}}! {{with index . "productTitle"}}{{.}}{{else}}The item you were waiting for{{end}} is back in stock: {{.productUrl}}

You asked us to tell you when it was back, so this is the only email you will get about it. Stop this alert: {{.alertUnsubscribeUrl}}'),
('stock.back_in_stock', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'stock.back_in_stock' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 '{{with index . "productTitle"}}C''est de retour : {{.}}{{else}}Votre article est de retour en stock{{end}}',
 '{{define "preheader"}}Votre article est de retour en stock. Les articles populaires partent vite.{{end}}
{{badge "success" "De retour en stock"}}
<h1>{{with index . "firstName"}}Bonne nouvelle, {{.}}, c''est de retour !{{else}}Bonne nouvelle, c''est de retour !{{end}}</h1>
<p>{{with index . "productTitle"}}<strong>{{.}}</strong>{{else}}L''article que vous attendiez{{end}} est de nouveau en stock. Les articles populaires partent vite, profitez-en dès maintenant.</p>
{{button .productUrl "Voir l''article"}}
<p><small>Vous avez demandé à être prévenu(e) du retour de cet article : c''est le seul e-mail que vous recevrez à ce sujet. <a href="{{.alertUnsubscribeUrl}}">Arrêter cette alerte</a>.</small></p>',
 'Bonne nouvelle{{with index . "firstName"}}, {{.}}{{end}} ! {{with index . "productTitle"}}{{.}}{{else}}L''article que vous attendiez{{end}} est de retour en stock : {{.productUrl}}

Vous avez demandé à être prévenu(e) de son retour : c''est le seul e-mail que vous recevrez à ce sujet. Arrêter cette alerte : {{.alertUnsubscribeUrl}}'),
('stock.back_in_stock', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'stock.back_in_stock' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 '{{with index . "productTitle"}}Ya está de vuelta: {{.}}{{else}}Tu artículo ya está disponible{{end}}',
 '{{define "preheader"}}Tu artículo vuelve a estar disponible. Los más populares se agotan rápido.{{end}}
{{badge "success" "De nuevo disponible"}}
<h1>{{with index . "firstName"}}¡Buenas noticias, {{.}}, ya está de vuelta!{{else}}¡Buenas noticias, ya está de vuelta!{{end}}</h1>
<p>{{with index . "productTitle"}}<strong>{{.}}</strong>{{else}}El artículo que esperabas{{end}} vuelve a estar disponible. Los productos más populares se agotan rápido, así que aprovecha ahora.</p>
{{button .productUrl "Comprar ahora"}}
<p><small>Pediste que te avisáramos cuando este artículo volviera, así que este es el único correo que recibirás sobre él. <a href="{{.alertUnsubscribeUrl}}">Detener este aviso</a>.</small></p>',
 '¡Buenas noticias{{with index . "firstName"}}, {{.}}{{end}}! {{with index . "productTitle"}}{{.}}{{else}}El artículo que esperabas{{end}} vuelve a estar disponible: {{.productUrl}}

Pediste que te avisáramos cuando volviera, así que este es el único correo que recibirás sobre él. Detener este aviso: {{.alertUnsubscribeUrl}}'),
('stock.back_in_stock', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'stock.back_in_stock' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 '{{with index . "productTitle"}}عاد للتوفر: {{.}}{{else}}المنتج الذي تنتظره عاد للتوفر{{end}}',
 '{{define "preheader"}}المنتج الذي تنتظره عاد للتوفر. المنتجات الشائعة تنفد بسرعة.{{end}}
<div dir="rtl" style="text-align:right">
{{badge "success" "عاد للتوفر"}}
<h1>{{with index . "firstName"}}أخبار سارة يا {{.}}، لقد عاد!{{else}}أخبار سارة، لقد عاد!{{end}}</h1>
<p>{{with index . "productTitle"}}<strong>{{.}}</strong>{{else}}المنتج الذي كنت تنتظره{{end}} متوفر الآن من جديد. المنتجات الشائعة تنفد بسرعة، فاغتنم الفرصة.</p>
{{button .productUrl "تسوّق الآن"}}
<p><small>طلبتَ أن نُعلمك عند عودة هذا المنتج، وهذه هي الرسالة الوحيدة التي ستصلك بشأنه. <a href="{{.alertUnsubscribeUrl}}">إيقاف هذا التنبيه</a>.</small></p>
</div>',
 'أخبار سارة{{with index . "firstName"}} يا {{.}}{{end}}! {{with index . "productTitle"}}{{.}}{{else}}المنتج الذي كنت تنتظره{{end}} متوفر من جديد: {{.productUrl}}

طلبتَ أن نُعلمك عند عودته، وهذه هي الرسالة الوحيدة التي ستصلك بشأنه. إيقاف هذا التنبيه: {{.alertUnsubscribeUrl}}'),
('stock.back_in_stock', 'yo', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'stock.back_in_stock' AND t.locale = 'yo' AND t.channel = 'email'), 'transactional',
 '{{with index . "productTitle"}}Ó ti padà dé: {{.}}{{else}}Ọjà tí o ń retí ti padà dé{{end}}',
 '{{define "preheader"}}Ọjà tí o ń retí ti padà dé. Àwọn ọjà tó gbajúmọ̀ máa ń tán kíákíá.{{end}}
{{badge "success" "Ó ti padà dé"}}
<h1>{{with index . "firstName"}}Ìròyìn ayọ̀, {{.}}, ó ti padà dé!{{else}}Ìròyìn ayọ̀, ó ti padà dé!{{end}}</h1>
<p>{{with index . "productTitle"}}<strong>{{.}}</strong>{{else}}Ọjà tí o ń retí{{end}} ti wà ní ọjà padà. Àwọn ọjà tó gbajúmọ̀ máa ń tán kíákíá, nítorí náà yára ra tìrẹ.</p>
{{button .productUrl "Ra báyìí"}}
<p><small>O béèrè pé kí a sọ fún ọ nígbà tí ọjà yìí bá padà dé, nítorí náà ímeèlì yìí nìkan ni o máa gbà nípa rẹ̀. <a href="{{.alertUnsubscribeUrl}}">Dá ìkìlọ̀ yìí dúró</a>.</small></p>',
 'Ìròyìn ayọ̀{{with index . "firstName"}}, {{.}}{{end}}! {{with index . "productTitle"}}{{.}}{{else}}Ọjà tí o ń retí{{end}} ti padà dé: {{.productUrl}}

O béèrè pé kí a sọ fún ọ nígbà tí ó bá padà dé, nítorí náà ímeèlì yìí nìkan ni o máa gbà nípa rẹ̀. Dá ìkìlọ̀ yìí dúró: {{.alertUnsubscribeUrl}}');
