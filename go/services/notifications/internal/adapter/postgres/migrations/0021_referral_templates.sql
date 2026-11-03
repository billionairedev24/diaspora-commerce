-- S322: referral program. payments publishes payments.referral.rewarded to the customer who earned a reward, once per
-- side: the referrer when the friend's first order was delivered and its return window passed, the friend with the same
-- event. AfroTransact pays the reward, as credit or as free membership days. The customer is resolved through identity,
-- so the email goes out in their language (en, fr, es, ar, yo). Each row becomes the next version of its key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('payment.referral.rewarded', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.referral.rewarded' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{if .isReferrer}}You earned {{if .isCredit}}{{.amount}} of credit{{else}}{{.days}} days of membership{{end}} for inviting {{.friendName}}{{else}}Your welcome reward: {{if .isCredit}}{{.amount}} of credit{{else}}{{.days}} days of membership{{end}}{{end}}',
 '{{define "preheader"}}{{if .isReferrer}}Thanks for sharing AfroTransact.{{else}}Thanks for joining with a friend’s code.{{end}} Your reward is ready.{{end}}
<div>
{{badge "success" "Referral reward"}}
<h1>{{if .isReferrer}}You earned {{if .isCredit}}{{.amount}} of credit{{else}}{{.days}} days of membership{{end}}{{else}}Welcome! {{if .isCredit}}{{.amount}} of credit{{else}}{{.days}} days of membership{{end}} is yours{{end}}</h1>
<p>{{if .isReferrer}}{{.friendName}} joined AfroTransact with your code and their first order was delivered. As a thank-you, AfroTransact added {{if .isCredit}}<strong>{{.amount}}</strong> to your credit{{else}}<strong>{{.days}} days</strong> of free membership to your account{{end}}.{{else}}Your first order was delivered, so AfroTransact added {{if .isCredit}}<strong>{{.amount}}</strong> to your credit{{else}}<strong>{{.days}} days</strong> of free membership to your account{{end}} as a welcome from the friend who invited you.{{end}}</p>
{{if .isCredit}}<p>Credit pays for products and services at checkout. It never expires. It can’t be withdrawn, refunded to a card or transferred.</p>
{{button .creditUrl "See my credit"}}{{else}}{{button .referUrl "See my referrals"}}{{end}}
<p><small>Know someone else who would love AfroTransact? Share your code from Account, Refer a friend.</small></p>
</div>',
 '{{if .isReferrer}}{{.friendName}} joined AfroTransact with your code and their first order was delivered. As a thank-you, AfroTransact added {{if .isCredit}}{{.amount}} to your credit{{else}}{{.days}} days of free membership to your account{{end}}.{{else}}Your first order was delivered, so AfroTransact added {{if .isCredit}}{{.amount}} to your credit{{else}}{{.days}} days of free membership to your account{{end}} as a welcome from the friend who invited you.{{end}}
{{if .isCredit}}
Credit pays for products and services at checkout. It never expires. It can’t be withdrawn, refunded to a card or transferred.
{{.creditUrl}}{{else}}
{{.referUrl}}{{end}}

Know someone else who would love AfroTransact? Share your code from Account, Refer a friend.'),
('payment.referral.rewarded', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.referral.rewarded' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 '{{if .isReferrer}}Vous avez gagné {{if .isCredit}}{{.amount}} de crédit{{else}}{{.days}} jours d’abonnement{{end}} en invitant {{.friendName}}{{else}}Votre récompense de bienvenue : {{if .isCredit}}{{.amount}} de crédit{{else}}{{.days}} jours d’abonnement{{end}}{{end}}',
 '{{define "preheader"}}{{if .isReferrer}}Merci d’avoir partagé AfroTransact.{{else}}Merci d’avoir rejoint AfroTransact avec le code d’un ami.{{end}} Votre récompense est prête.{{end}}
<div>
{{badge "success" "Récompense de parrainage"}}
<h1>{{if .isReferrer}}Vous avez gagné {{if .isCredit}}{{.amount}} de crédit{{else}}{{.days}} jours d’abonnement{{end}}{{else}}Bienvenue ! {{if .isCredit}}{{.amount}} de crédit{{else}}{{.days}} jours d’abonnement{{end}} pour vous{{end}}</h1>
<p>{{if .isReferrer}}{{.friendName}} a rejoint AfroTransact avec votre code et sa première commande a été livrée. Pour vous remercier, AfroTransact a ajouté {{if .isCredit}}<strong>{{.amount}}</strong> à votre crédit{{else}}<strong>{{.days}} jours</strong> d’abonnement gratuit à votre compte{{end}}.{{else}}Votre première commande a été livrée : AfroTransact a donc ajouté {{if .isCredit}}<strong>{{.amount}}</strong> à votre crédit{{else}}<strong>{{.days}} jours</strong> d’abonnement gratuit à votre compte{{end}} en cadeau de bienvenue de l’ami qui vous a invité.{{end}}</p>
{{if .isCredit}}<p>Le crédit sert à payer des produits et des services au moment du paiement. Il n’expire jamais. Il ne peut être ni retiré, ni remboursé sur une carte, ni transféré.</p>
{{button .creditUrl "Voir mon crédit"}}{{else}}{{button .referUrl "Voir mes parrainages"}}{{end}}
<p><small>Vous connaissez quelqu’un qui adorerait AfroTransact ? Partagez votre code depuis Compte, Parrainer un ami.</small></p>
</div>',
 '{{if .isReferrer}}{{.friendName}} a rejoint AfroTransact avec votre code et sa première commande a été livrée. Pour vous remercier, AfroTransact a ajouté {{if .isCredit}}{{.amount}} à votre crédit{{else}}{{.days}} jours d’abonnement gratuit à votre compte{{end}}.{{else}}Votre première commande a été livrée : AfroTransact a donc ajouté {{if .isCredit}}{{.amount}} à votre crédit{{else}}{{.days}} jours d’abonnement gratuit à votre compte{{end}} en cadeau de bienvenue de l’ami qui vous a invité.{{end}}
{{if .isCredit}}
Le crédit sert à payer des produits et des services au moment du paiement. Il n’expire jamais. Il ne peut être ni retiré, ni remboursé sur une carte, ni transféré.
{{.creditUrl}}{{else}}
{{.referUrl}}{{end}}

Vous connaissez quelqu’un qui adorerait AfroTransact ? Partagez votre code depuis Compte, Parrainer un ami.'),
('payment.referral.rewarded', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.referral.rewarded' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 '{{if .isReferrer}}Ganaste {{if .isCredit}}{{.amount}} de crédito{{else}}{{.days}} días de membresía{{end}} por invitar a {{.friendName}}{{else}}Tu recompensa de bienvenida: {{if .isCredit}}{{.amount}} de crédito{{else}}{{.days}} días de membresía{{end}}{{end}}',
 '{{define "preheader"}}{{if .isReferrer}}Gracias por compartir AfroTransact.{{else}}Gracias por unirte con el código de un amigo.{{end}} Tu recompensa está lista.{{end}}
<div>
{{badge "success" "Recompensa por invitar"}}
<h1>{{if .isReferrer}}Ganaste {{if .isCredit}}{{.amount}} de crédito{{else}}{{.days}} días de membresía{{end}}{{else}}¡Bienvenido! {{if .isCredit}}{{.amount}} de crédito{{else}}{{.days}} días de membresía{{end}} son tuyos{{end}}</h1>
<p>{{if .isReferrer}}{{.friendName}} se unió a AfroTransact con tu código y su primer pedido fue entregado. Como agradecimiento, AfroTransact añadió {{if .isCredit}}<strong>{{.amount}}</strong> a tu crédito{{else}}<strong>{{.days}} días</strong> de membresía gratis a tu cuenta{{end}}.{{else}}Tu primer pedido fue entregado, así que AfroTransact añadió {{if .isCredit}}<strong>{{.amount}}</strong> a tu crédito{{else}}<strong>{{.days}} días</strong> de membresía gratis a tu cuenta{{end}} como bienvenida del amigo que te invitó.{{end}}</p>
{{if .isCredit}}<p>El crédito sirve para pagar productos y servicios al finalizar la compra. Nunca caduca. No se puede retirar, reembolsar a una tarjeta ni transferir.</p>
{{button .creditUrl "Ver mi crédito"}}{{else}}{{button .referUrl "Ver mis invitaciones"}}{{end}}
<p><small>¿Conoces a alguien a quien le encantaría AfroTransact? Comparte tu código desde Cuenta, Invita a un amigo.</small></p>
</div>',
 '{{if .isReferrer}}{{.friendName}} se unió a AfroTransact con tu código y su primer pedido fue entregado. Como agradecimiento, AfroTransact añadió {{if .isCredit}}{{.amount}} a tu crédito{{else}}{{.days}} días de membresía gratis a tu cuenta{{end}}.{{else}}Tu primer pedido fue entregado, así que AfroTransact añadió {{if .isCredit}}{{.amount}} a tu crédito{{else}}{{.days}} días de membresía gratis a tu cuenta{{end}} como bienvenida del amigo que te invitó.{{end}}
{{if .isCredit}}
El crédito sirve para pagar productos y servicios al finalizar la compra. Nunca caduca. No se puede retirar, reembolsar a una tarjeta ni transferir.
{{.creditUrl}}{{else}}
{{.referUrl}}{{end}}

¿Conoces a alguien a quien le encantaría AfroTransact? Comparte tu código desde Cuenta, Invita a un amigo.'),
('payment.referral.rewarded', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.referral.rewarded' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 '{{if .isReferrer}}ربحت {{if .isCredit}}رصيدًا بقيمة {{.amount}}{{else}}{{.days}} يومًا من العضوية{{end}} لدعوتك {{.friendName}}{{else}}مكافأة الترحيب: {{if .isCredit}}رصيد بقيمة {{.amount}}{{else}}{{.days}} يومًا من العضوية{{end}}{{end}}',
 '{{define "preheader"}}{{if .isReferrer}}شكرًا لأنك شاركت أفروترانزاكت.{{else}}شكرًا لانضمامك برمز صديق.{{end}} مكافأتك جاهزة.{{end}}
<div dir="rtl">
{{badge "success" "مكافأة الدعوة"}}
<h1>{{if .isReferrer}}ربحت {{if .isCredit}}رصيدًا بقيمة {{.amount}}{{else}}{{.days}} يومًا من العضوية{{end}}{{else}}أهلًا بك! {{if .isCredit}}رصيد بقيمة {{.amount}}{{else}}{{.days}} يومًا من العضوية{{end}} لك{{end}}</h1>
<p>{{if .isReferrer}}انضم {{.friendName}} إلى أفروترانزاكت برمزك وتم تسليم أول طلب له. شكرًا لك، أضافت أفروترانزاكت {{if .isCredit}}<strong>{{.amount}}</strong> إلى رصيدك{{else}}<strong>{{.days}} يومًا</strong> من العضوية المجانية إلى حسابك{{end}}.{{else}}تم تسليم طلبك الأول، لذلك أضافت أفروترانزاكت {{if .isCredit}}<strong>{{.amount}}</strong> إلى رصيدك{{else}}<strong>{{.days}} يومًا</strong> من العضوية المجانية إلى حسابك{{end}} هدية ترحيب من الصديق الذي دعاك.{{end}}</p>
{{if .isCredit}}<p>يُستخدم الرصيد لدفع ثمن المنتجات والخدمات عند إتمام الشراء. لا تنتهي صلاحيته. لا يمكن سحبه أو استرداده إلى بطاقة أو تحويله.</p>
{{button .creditUrl "عرض رصيدي"}}{{else}}{{button .referUrl "عرض دعواتي"}}{{end}}
<p><small>هل تعرف شخصًا سيحب أفروترانزاكت؟ شارك رمزك من الحساب ثم ادعُ صديقًا.</small></p>
</div>',
 '{{if .isReferrer}}انضم {{.friendName}} إلى أفروترانزاكت برمزك وتم تسليم أول طلب له. شكرًا لك، أضافت أفروترانزاكت {{if .isCredit}}{{.amount}} إلى رصيدك{{else}}{{.days}} يومًا من العضوية المجانية إلى حسابك{{end}}.{{else}}تم تسليم طلبك الأول، لذلك أضافت أفروترانزاكت {{if .isCredit}}{{.amount}} إلى رصيدك{{else}}{{.days}} يومًا من العضوية المجانية إلى حسابك{{end}} هدية ترحيب من الصديق الذي دعاك.{{end}}
{{if .isCredit}}
يُستخدم الرصيد لدفع ثمن المنتجات والخدمات عند إتمام الشراء. لا تنتهي صلاحيته. لا يمكن سحبه أو استرداده إلى بطاقة أو تحويله.
{{.creditUrl}}{{else}}
{{.referUrl}}{{end}}

هل تعرف شخصًا سيحب أفروترانزاكت؟ شارك رمزك من الحساب ثم ادعُ صديقًا.'),
('payment.referral.rewarded', 'yo', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.referral.rewarded' AND t.locale = 'yo' AND t.channel = 'email'), 'transactional',
 '{{if .isReferrer}}O gba {{if .isCredit}}{{.amount}} owó ìdókòwò{{else}}ọjọ́ {{.days}} ti ẹgbẹ́{{end}} fún pípe {{.friendName}}{{else}}Ẹ̀bùn káàbọ̀ rẹ: {{if .isCredit}}{{.amount}} owó ìdókòwò{{else}}ọjọ́ {{.days}} ti ẹgbẹ́{{end}}{{end}}',
 '{{define "preheader"}}{{if .isReferrer}}A dúpẹ́ fún pínpín AfroTransact.{{else}}A dúpẹ́ fún dídarapọ̀ pẹ̀lú kóòdù ọ̀rẹ́.{{end}} Ẹ̀bùn rẹ ti ṣetán.{{end}}
<div>
{{badge "success" "Ẹ̀bùn ìpè"}}
<h1>{{if .isReferrer}}O gba {{if .isCredit}}{{.amount}} owó ìdókòwò{{else}}ọjọ́ {{.days}} ti ẹgbẹ́{{end}}{{else}}Káàbọ̀! {{if .isCredit}}{{.amount}} owó ìdókòwò{{else}}ọjọ́ {{.days}} ti ẹgbẹ́{{end}} jẹ́ tìrẹ{{end}}</h1>
<p>{{if .isReferrer}}{{.friendName}} darapọ̀ mọ́ AfroTransact pẹ̀lú kóòdù rẹ, a sì ti kó àṣẹ àkọ́kọ́ rẹ̀ dé. Gẹ́gẹ́ bí ọpẹ́, AfroTransact ti fi {{if .isCredit}}<strong>{{.amount}}</strong> kún owó ìdókòwò rẹ{{else}}<strong>ọjọ́ {{.days}}</strong> ti ẹgbẹ́ ọ̀fẹ́ kún àkọọ́lẹ̀ rẹ{{end}}.{{else}}A ti kó àṣẹ àkọ́kọ́ rẹ dé, nítorí náà AfroTransact ti fi {{if .isCredit}}<strong>{{.amount}}</strong> kún owó ìdókòwò rẹ{{else}}<strong>ọjọ́ {{.days}}</strong> ti ẹgbẹ́ ọ̀fẹ́ kún àkọọ́lẹ̀ rẹ{{end}} gẹ́gẹ́ bí káàbọ̀ láti ọ̀dọ̀ ọ̀rẹ́ tó pè ọ́.{{end}}</p>
{{if .isCredit}}<p>Owó ìdókòwò ni a fi ń sanwó fún ọjà àti iṣẹ́ nígbà ìsanwó. Kì í parí. A kò lè yọ ọ́ jáde, dá a padà sí káàdì tàbí gbé e lọ sí ẹlòmíràn.</p>
{{button .creditUrl "Wo owó ìdókòwò mi"}}{{else}}{{button .referUrl "Wo àwọn ìpè mi"}}{{end}}
<p><small>Ǹjẹ́ o mọ ẹnì kan tí yóò fẹ́ràn AfroTransact? Pín kóòdù rẹ láti Àkọọ́lẹ̀, Pe ọ̀rẹ́ kan.</small></p>
</div>',
 '{{if .isReferrer}}{{.friendName}} darapọ̀ mọ́ AfroTransact pẹ̀lú kóòdù rẹ, a sì ti kó àṣẹ àkọ́kọ́ rẹ̀ dé. Gẹ́gẹ́ bí ọpẹ́, AfroTransact ti fi {{if .isCredit}}{{.amount}} kún owó ìdókòwò rẹ{{else}}ọjọ́ {{.days}} ti ẹgbẹ́ ọ̀fẹ́ kún àkọọ́lẹ̀ rẹ{{end}}.{{else}}A ti kó àṣẹ àkọ́kọ́ rẹ dé, nítorí náà AfroTransact ti fi {{if .isCredit}}{{.amount}} kún owó ìdókòwò rẹ{{else}}ọjọ́ {{.days}} ti ẹgbẹ́ ọ̀fẹ́ kún àkọọ́lẹ̀ rẹ{{end}} gẹ́gẹ́ bí káàbọ̀ láti ọ̀dọ̀ ọ̀rẹ́ tó pè ọ́.{{end}}
{{if .isCredit}}
Owó ìdókòwò ni a fi ń sanwó fún ọjà àti iṣẹ́ nígbà ìsanwó. Kì í parí. A kò lè yọ ọ́ jáde, dá a padà sí káàdì tàbí gbé e lọ sí ẹlòmíràn.
{{.creditUrl}}{{else}}
{{.referUrl}}{{end}}

Ǹjẹ́ o mọ ẹnì kan tí yóò fẹ́ràn AfroTransact? Pín kóòdù rẹ láti Àkọọ́lẹ̀, Pe ọ̀rẹ́ kan.');
