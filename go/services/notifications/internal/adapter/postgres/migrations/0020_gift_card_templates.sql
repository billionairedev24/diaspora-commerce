-- S321: gift cards. payments publishes payments.gift_card.deliver on the send date (and when staff send a card again):
-- the payload carries the recipient's email, the language the buyer chose (en, fr, es, ar, yo), the buyer's name and
-- message, the amount and the code. The email is the only place the code is ever shown; payments keeps just its hash.
-- A transactional message to the recipient's address: they may not have an account, so there is no preference to honour.
-- Each row becomes the next version of its key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('payment.gift_card.delivered', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.gift_card.delivered' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{if .senderName}}{{.senderName}} sent you{{else}}You have{{end}} an AfroTransact gift card for {{.amount}}',
 '{{define "preheader"}}A gift card for {{.amount}} is waiting for you. Your code is inside.{{end}}
<div>
{{badge "success" "Gift card"}}
<h1>You have a gift card for {{.amount}}</h1>
<p>{{if .recipientName}}Hi {{.recipientName}},{{else}}Hi,{{end}} {{if .senderName}}<strong>{{.senderName}}</strong> sent you{{else}}You have received{{end}} an AfroTransact gift card for <strong>{{.amount}}</strong>.</p>
{{if .message}}<p><small>Their message:</small><br>“{{.message}}”</p>{{end}}
<p>Redeem it in your account and the amount is added to your AfroTransact credit, which pays for products and services. Credit never expires.</p>
<p><strong>Your gift card code</strong></p>
<p style="font-family:''SF Mono'',Menlo,Consolas,monospace;font-size:22px;line-height:30px;font-weight:700;letter-spacing:2px;margin:8px 0 24px 0;">{{.code}}</p>
{{button .redeemUrl "Redeem your gift card"}}
<p><small>Each code works once. Keep it private: anyone who has it can redeem it. Credit can’t be withdrawn, refunded to a card or transferred.</small></p>
</div>',
 '{{if .recipientName}}Hi {{.recipientName}},{{else}}Hi,{{end}} {{if .senderName}}{{.senderName}} sent you{{else}}you have received{{end}} an AfroTransact gift card for {{.amount}}.
{{if .message}}
“{{.message}}”
{{end}}
Redeem it in your account and the amount is added to your AfroTransact credit, which pays for products and services. Credit never expires.

Your gift card code: {{.code}}
{{.redeemUrl}}

Each code works once. Keep it private. Credit can’t be withdrawn, refunded to a card or transferred.'),
('payment.gift_card.delivered', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.gift_card.delivered' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 '{{if .senderName}}{{.senderName}} vous offre{{else}}Vous avez reçu{{end}} une carte cadeau AfroTransact de {{.amount}}',
 '{{define "preheader"}}Une carte cadeau de {{.amount}} vous attend. Votre code est à l’intérieur.{{end}}
<div>
{{badge "success" "Carte cadeau"}}
<h1>Vous avez une carte cadeau de {{.amount}}</h1>
<p>{{if .recipientName}}Bonjour {{.recipientName}},{{else}}Bonjour,{{end}} {{if .senderName}}<strong>{{.senderName}}</strong> vous offre{{else}}Vous avez reçu{{end}} une carte cadeau AfroTransact de <strong>{{.amount}}</strong>.</p>
{{if .message}}<p><small>Son message :</small><br>“{{.message}}”</p>{{end}}
<p>Utilisez-la dans votre compte : le montant est ajouté à votre crédit AfroTransact, qui sert à payer des produits et des services. Le crédit n’expire jamais.</p>
<p><strong>Le code de votre carte cadeau</strong></p>
<p style="font-family:''SF Mono'',Menlo,Consolas,monospace;font-size:22px;line-height:30px;font-weight:700;letter-spacing:2px;margin:8px 0 24px 0;">{{.code}}</p>
{{button .redeemUrl "Utiliser ma carte cadeau"}}
<p><small>Chaque code ne fonctionne qu’une fois. Gardez-le confidentiel : toute personne qui l’a peut l’utiliser. Le crédit ne peut être ni retiré, ni remboursé sur une carte, ni transféré.</small></p>
</div>',
 '{{if .recipientName}}Bonjour {{.recipientName}},{{else}}Bonjour,{{end}} {{if .senderName}}{{.senderName}} vous offre{{else}}vous avez reçu{{end}} une carte cadeau AfroTransact de {{.amount}}.
{{if .message}}
“{{.message}}”
{{end}}
Utilisez-la dans votre compte : le montant est ajouté à votre crédit AfroTransact, qui sert à payer des produits et des services. Le crédit n’expire jamais.

Le code de votre carte cadeau : {{.code}}
{{.redeemUrl}}

Chaque code ne fonctionne qu’une fois. Gardez-le confidentiel. Le crédit ne peut être ni retiré, ni remboursé sur une carte, ni transféré.'),
('payment.gift_card.delivered', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.gift_card.delivered' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 '{{if .senderName}}{{.senderName}} te regala{{else}}Tienes{{end}} una tarjeta de regalo AfroTransact de {{.amount}}',
 '{{define "preheader"}}Te espera una tarjeta de regalo de {{.amount}}. Tu código está dentro.{{end}}
<div>
{{badge "success" "Tarjeta de regalo"}}
<h1>Tienes una tarjeta de regalo de {{.amount}}</h1>
<p>{{if .recipientName}}Hola {{.recipientName}},{{else}}Hola,{{end}} {{if .senderName}}<strong>{{.senderName}}</strong> te regala{{else}}Has recibido{{end}} una tarjeta de regalo AfroTransact de <strong>{{.amount}}</strong>.</p>
{{if .message}}<p><small>Su mensaje:</small><br>“{{.message}}”</p>{{end}}
<p>Canjéala en tu cuenta y el importe se suma a tu crédito AfroTransact, que sirve para pagar productos y servicios. El crédito nunca caduca.</p>
<p><strong>El código de tu tarjeta de regalo</strong></p>
<p style="font-family:''SF Mono'',Menlo,Consolas,monospace;font-size:22px;line-height:30px;font-weight:700;letter-spacing:2px;margin:8px 0 24px 0;">{{.code}}</p>
{{button .redeemUrl "Canjear mi tarjeta de regalo"}}
<p><small>Cada código funciona una sola vez. Mantenlo en privado: quien lo tenga puede canjearlo. El crédito no se puede retirar, reembolsar a una tarjeta ni transferir.</small></p>
</div>',
 '{{if .recipientName}}Hola {{.recipientName}},{{else}}Hola,{{end}} {{if .senderName}}{{.senderName}} te regala{{else}}has recibido{{end}} una tarjeta de regalo AfroTransact de {{.amount}}.
{{if .message}}
“{{.message}}”
{{end}}
Canjéala en tu cuenta y el importe se suma a tu crédito AfroTransact, que sirve para pagar productos y servicios. El crédito nunca caduca.

El código de tu tarjeta de regalo: {{.code}}
{{.redeemUrl}}

Cada código funciona una sola vez. Mantenlo en privado. El crédito no se puede retirar, reembolsar a una tarjeta ni transferir.'),
('payment.gift_card.delivered', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.gift_card.delivered' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 '{{if .senderName}}أرسل لك {{.senderName}}{{else}}لديك{{end}} بطاقة هدية من أفروترانزاكت بقيمة {{.amount}}',
 '{{define "preheader"}}بطاقة هدية بقيمة {{.amount}} في انتظارك. رمزك بالداخل.{{end}}
<div dir="rtl">
{{badge "success" "بطاقة هدية"}}
<h1>لديك بطاقة هدية بقيمة {{.amount}}</h1>
<p>{{if .recipientName}}مرحبًا {{.recipientName}}،{{else}}مرحبًا،{{end}} {{if .senderName}}أرسل لك <strong>{{.senderName}}</strong>{{else}}وصلتك{{end}} بطاقة هدية من أفروترانزاكت بقيمة <strong>{{.amount}}</strong>.</p>
{{if .message}}<p><small>رسالته:</small><br>“{{.message}}”</p>{{end}}
<p>استبدلها من حسابك ليُضاف المبلغ إلى رصيدك في أفروترانزاكت، وهو يُستخدم لدفع ثمن المنتجات والخدمات. لا تنتهي صلاحية الرصيد.</p>
<p><strong>رمز بطاقة الهدية</strong></p>
<p style="font-family:''SF Mono'',Menlo,Consolas,monospace;font-size:22px;line-height:30px;font-weight:700;letter-spacing:2px;margin:8px 0 24px 0;">{{.code}}</p>
{{button .redeemUrl "استبدال بطاقة الهدية"}}
<p><small>يعمل كل رمز مرة واحدة فقط. احتفظ به سريًا: يمكن لأي شخص يملكه استبداله. لا يمكن سحب الرصيد أو استرداده إلى بطاقة أو تحويله.</small></p>
</div>',
 '{{if .recipientName}}مرحبًا {{.recipientName}}،{{else}}مرحبًا،{{end}} {{if .senderName}}أرسل لك {{.senderName}}{{else}}وصلتك{{end}} بطاقة هدية من أفروترانزاكت بقيمة {{.amount}}.
{{if .message}}
“{{.message}}”
{{end}}
استبدلها من حسابك ليُضاف المبلغ إلى رصيدك في أفروترانزاكت، وهو يُستخدم لدفع ثمن المنتجات والخدمات. لا تنتهي صلاحية الرصيد.

رمز بطاقة الهدية: {{.code}}
{{.redeemUrl}}

يعمل كل رمز مرة واحدة فقط. احتفظ به سريًا. لا يمكن سحب الرصيد أو استرداده إلى بطاقة أو تحويله.'),
('payment.gift_card.delivered', 'yo', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.gift_card.delivered' AND t.locale = 'yo' AND t.channel = 'email'), 'transactional',
 '{{if .senderName}}{{.senderName}} fi{{else}}O ní{{end}} káàdì ẹ̀bùn AfroTransact tó tó {{.amount}}{{if .senderName}} ránṣẹ́ sí ọ{{end}}',
 '{{define "preheader"}}Káàdì ẹ̀bùn tó tó {{.amount}} ń dúró dè ọ́. Kóòdù rẹ wà nínú rẹ̀.{{end}}
<div>
{{badge "success" "Káàdì ẹ̀bùn"}}
<h1>O ní káàdì ẹ̀bùn tó tó {{.amount}}</h1>
<p>{{if .recipientName}}Báwo {{.recipientName}},{{else}}Báwo,{{end}} {{if .senderName}}<strong>{{.senderName}}</strong> fi{{else}}O gba{{end}} káàdì ẹ̀bùn AfroTransact tó tó <strong>{{.amount}}</strong>{{if .senderName}} ránṣẹ́ sí ọ{{end}}.</p>
{{if .message}}<p><small>Ọ̀rọ̀ rẹ̀:</small><br>“{{.message}}”</p>{{end}}
<p>Lò ó nínú àkọọ́lẹ̀ rẹ, a ó sì fi iye náà kún owó ìdókòwò AfroTransact rẹ, èyí tí a fi ń ra ọjà àti iṣẹ́. Owó ìdókòwò kì í parí.</p>
<p><strong>Kóòdù káàdì ẹ̀bùn rẹ</strong></p>
<p style="font-family:''SF Mono'',Menlo,Consolas,monospace;font-size:22px;line-height:30px;font-weight:700;letter-spacing:2px;margin:8px 0 24px 0;">{{.code}}</p>
{{button .redeemUrl "Lo káàdì ẹ̀bùn mi"}}
<p><small>Kóòdù kọ̀ọ̀kan ń ṣiṣẹ́ lẹ́ẹ̀kan ṣoṣo. Fi pamọ́: ẹnikẹ́ni tó bá ní i lè lò ó. A kò lè yọ owó ìdókòwò jáde, dá a padà sí káàdì tàbí gbé e lọ sí ẹlòmíràn.</small></p>
</div>',
 '{{if .recipientName}}Báwo {{.recipientName}},{{else}}Báwo,{{end}} {{if .senderName}}{{.senderName}} fi{{else}}o gba{{end}} káàdì ẹ̀bùn AfroTransact tó tó {{.amount}}{{if .senderName}} ránṣẹ́ sí ọ{{end}}.
{{if .message}}
“{{.message}}”
{{end}}
Lò ó nínú àkọọ́lẹ̀ rẹ, a ó sì fi iye náà kún owó ìdókòwò AfroTransact rẹ, èyí tí a fi ń ra ọjà àti iṣẹ́. Owó ìdókòwò kì í parí.

Kóòdù káàdì ẹ̀bùn rẹ: {{.code}}
{{.redeemUrl}}

Kóòdù kọ̀ọ̀kan ń ṣiṣẹ́ lẹ́ẹ̀kan ṣoṣo. Fi pamọ́. A kò lè yọ owó ìdókòwò jáde, dá a padà sí káàdì tàbí gbé e lọ sí ẹlòmíràn.');
