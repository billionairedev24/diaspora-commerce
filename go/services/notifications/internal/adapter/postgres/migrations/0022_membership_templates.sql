-- S323: membership emails. payments publishes payments.membership.changed; notifications emails the member, in their own
-- language (en, fr, es, ar, yo), when a membership renews or a trial converts, when a renewal payment fails (and when it
-- lapses after the last retry) and a few days before a free trial ends. Each row becomes the next version of its
-- key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('payment.membership.renewed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.renewed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{if .isTrialConverted}}Your {{.tierName}} trial has ended: you are now a member{{else}}Your {{.tierName}} membership renewed{{end}}',
 '{{define "preheader"}}{{if .isTrialConverted}}Welcome to {{.tierName}}{{else}}Your {{.tierName}} membership renewed{{end}}{{end}}
<div>
{{badge "info" "Membership"}}
<h1>{{if .isTrialConverted}}Welcome to {{.tierName}}{{else}}Your {{.tierName}} membership renewed{{end}}</h1>
<p>We charged {{.amount}} to your card for {{if .isYearly}}the next year{{else}}the next month{{end}}. Your {{.tierName}} benefits continue until <strong>{{.date}}</strong>. You can change plan or cancel any time from your account.</p>
{{button .manageUrl "Manage my membership"}}
</div>',
 'We charged {{.amount}} to your card for {{if .isYearly}}the next year{{else}}the next month{{end}}. Your {{.tierName}} benefits continue until {{.date}}. You can change plan or cancel any time from your account.

{{.manageUrl}}'),
('payment.membership.payment_failed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.payment_failed' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{if .isLapsed}}Your {{.tierName}} membership has ended{{else}}We could not renew your {{.tierName}} membership{{end}}',
 '{{define "preheader"}}{{if .isLapsed}}Your {{.tierName}} membership has ended{{else}}Your membership payment did not go through{{end}}{{end}}
<div>
{{badge "info" "Membership payment"}}
<h1>{{if .isLapsed}}Your {{.tierName}} membership has ended{{else}}Your membership payment did not go through{{end}}</h1>
<p>{{if .isLapsed}}We tried to charge {{.amount}} several times and it did not work, so your {{.tierName}} benefits have stopped. You can join again any time.{{else}}We could not charge {{.amount}} to your card. Your {{.tierName}} benefits stay on while we try again in the next few days. Please check that your card is up to date.{{end}}</p>
{{button .actionUrl "{{if .isLapsed}}See membership{{else}}Update my card{{end}}"}}
</div>',
 '{{if .isLapsed}}We tried to charge {{.amount}} several times and it did not work, so your {{.tierName}} benefits have stopped. You can join again any time.{{else}}We could not charge {{.amount}} to your card. Your {{.tierName}} benefits stay on while we try again in the next few days. Please check that your card is up to date.{{end}}

{{.actionUrl}}'),
('payment.membership.trial_ending', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.trial_ending' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 'Your {{.tierName}} free trial ends on {{.date}}',
 '{{define "preheader"}}Your free trial ends on {{.date}}{{end}}
<div>
{{badge "info" "Membership"}}
<h1>Your free trial ends on {{.date}}</h1>
<p>After that your {{.tierName}} membership continues at <strong>{{.amount}}</strong> {{if .isYearly}}a year{{else}}a month{{end}}, charged to your saved card. If you do not want to continue, cancel before then and you will not be charged.</p>
{{button .manageUrl "Manage my membership"}}
</div>',
 'After that your {{.tierName}} membership continues at {{.amount}} {{if .isYearly}}a year{{else}}a month{{end}}, charged to your saved card. If you do not want to continue, cancel before then and you will not be charged.

{{.manageUrl}}'),
('payment.membership.renewed', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.renewed' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 '{{if .isTrialConverted}}Votre essai {{.tierName}} est terminé : vous êtes maintenant membre{{else}}Votre abonnement {{.tierName}} a été renouvelé{{end}}',
 '{{define "preheader"}}{{if .isTrialConverted}}Bienvenue chez {{.tierName}}{{else}}Votre abonnement {{.tierName}} a été renouvelé{{end}}{{end}}
<div>
{{badge "info" "Abonnement"}}
<h1>{{if .isTrialConverted}}Bienvenue chez {{.tierName}}{{else}}Votre abonnement {{.tierName}} a été renouvelé{{end}}</h1>
<p>Nous avons débité {{.amount}} sur votre carte pour {{if .isYearly}}l’année suivante{{else}}le mois suivant{{end}}. Vos avantages {{.tierName}} continuent jusqu’au <strong>{{.date}}</strong>. Vous pouvez changer de formule ou résilier à tout moment depuis votre compte.</p>
{{button .manageUrl "Gérer mon abonnement"}}
</div>',
 'Nous avons débité {{.amount}} sur votre carte pour {{if .isYearly}}l’année suivante{{else}}le mois suivant{{end}}. Vos avantages {{.tierName}} continuent jusqu’au {{.date}}. Vous pouvez changer de formule ou résilier à tout moment depuis votre compte.

{{.manageUrl}}'),
('payment.membership.payment_failed', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.payment_failed' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 '{{if .isLapsed}}Votre abonnement {{.tierName}} est terminé{{else}}Nous n’avons pas pu renouveler votre abonnement {{.tierName}}{{end}}',
 '{{define "preheader"}}{{if .isLapsed}}Votre abonnement {{.tierName}} est terminé{{else}}Le paiement de votre abonnement a échoué{{end}}{{end}}
<div>
{{badge "info" "Paiement de l’abonnement"}}
<h1>{{if .isLapsed}}Votre abonnement {{.tierName}} est terminé{{else}}Le paiement de votre abonnement a échoué{{end}}</h1>
<p>{{if .isLapsed}}Nous avons essayé de débiter {{.amount}} plusieurs fois sans succès : vos avantages {{.tierName}} sont donc arrêtés. Vous pouvez vous réabonner à tout moment.{{else}}Nous n’avons pas pu débiter {{.amount}} sur votre carte. Vos avantages {{.tierName}} restent actifs pendant que nous réessayons dans les prochains jours. Vérifiez que votre carte est à jour.{{end}}</p>
{{button .actionUrl "{{if .isLapsed}}Voir l’abonnement{{else}}Mettre ma carte à jour{{end}}"}}
</div>',
 '{{if .isLapsed}}Nous avons essayé de débiter {{.amount}} plusieurs fois sans succès : vos avantages {{.tierName}} sont donc arrêtés. Vous pouvez vous réabonner à tout moment.{{else}}Nous n’avons pas pu débiter {{.amount}} sur votre carte. Vos avantages {{.tierName}} restent actifs pendant que nous réessayons dans les prochains jours. Vérifiez que votre carte est à jour.{{end}}

{{.actionUrl}}'),
('payment.membership.trial_ending', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.trial_ending' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 'Votre essai gratuit {{.tierName}} se termine le {{.date}}',
 '{{define "preheader"}}Votre essai gratuit se termine le {{.date}}{{end}}
<div>
{{badge "info" "Abonnement"}}
<h1>Votre essai gratuit se termine le {{.date}}</h1>
<p>Ensuite, votre abonnement {{.tierName}} continue à <strong>{{.amount}}</strong> {{if .isYearly}}par an{{else}}par mois{{end}}, prélevés sur votre carte enregistrée. Si vous ne souhaitez pas continuer, résiliez avant cette date et vous ne serez pas débité.</p>
{{button .manageUrl "Gérer mon abonnement"}}
</div>',
 'Ensuite, votre abonnement {{.tierName}} continue à {{.amount}} {{if .isYearly}}par an{{else}}par mois{{end}}, prélevés sur votre carte enregistrée. Si vous ne souhaitez pas continuer, résiliez avant cette date et vous ne serez pas débité.

{{.manageUrl}}'),
('payment.membership.renewed', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.renewed' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 '{{if .isTrialConverted}}Terminó tu prueba de {{.tierName}}: ya eres miembro{{else}}Tu membresía {{.tierName}} se renovó{{end}}',
 '{{define "preheader"}}{{if .isTrialConverted}}Te damos la bienvenida a {{.tierName}}{{else}}Tu membresía {{.tierName}} se renovó{{end}}{{end}}
<div>
{{badge "info" "Membresía"}}
<h1>{{if .isTrialConverted}}Te damos la bienvenida a {{.tierName}}{{else}}Tu membresía {{.tierName}} se renovó{{end}}</h1>
<p>Cobramos {{.amount}} a tu tarjeta por {{if .isYearly}}el próximo año{{else}}el próximo mes{{end}}. Tus ventajas {{.tierName}} continúan hasta el <strong>{{.date}}</strong>. Puedes cambiar de plan o cancelar cuando quieras desde tu cuenta.</p>
{{button .manageUrl "Gestionar mi membresía"}}
</div>',
 'Cobramos {{.amount}} a tu tarjeta por {{if .isYearly}}el próximo año{{else}}el próximo mes{{end}}. Tus ventajas {{.tierName}} continúan hasta el {{.date}}. Puedes cambiar de plan o cancelar cuando quieras desde tu cuenta.

{{.manageUrl}}'),
('payment.membership.payment_failed', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.payment_failed' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 '{{if .isLapsed}}Tu membresía {{.tierName}} terminó{{else}}No pudimos renovar tu membresía {{.tierName}}{{end}}',
 '{{define "preheader"}}{{if .isLapsed}}Tu membresía {{.tierName}} terminó{{else}}No se pudo procesar el pago de tu membresía{{end}}{{end}}
<div>
{{badge "info" "Pago de la membresía"}}
<h1>{{if .isLapsed}}Tu membresía {{.tierName}} terminó{{else}}No se pudo procesar el pago de tu membresía{{end}}</h1>
<p>{{if .isLapsed}}Intentamos cobrar {{.amount}} varias veces sin éxito, por lo que tus ventajas {{.tierName}} se detuvieron. Puedes volver a unirte cuando quieras.{{else}}No pudimos cobrar {{.amount}} a tu tarjeta. Tus ventajas {{.tierName}} siguen activas mientras lo intentamos de nuevo en los próximos días. Comprueba que tu tarjeta esté al día.{{end}}</p>
{{button .actionUrl "{{if .isLapsed}}Ver la membresía{{else}}Actualizar mi tarjeta{{end}}"}}
</div>',
 '{{if .isLapsed}}Intentamos cobrar {{.amount}} varias veces sin éxito, por lo que tus ventajas {{.tierName}} se detuvieron. Puedes volver a unirte cuando quieras.{{else}}No pudimos cobrar {{.amount}} a tu tarjeta. Tus ventajas {{.tierName}} siguen activas mientras lo intentamos de nuevo en los próximos días. Comprueba que tu tarjeta esté al día.{{end}}

{{.actionUrl}}'),
('payment.membership.trial_ending', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.trial_ending' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 'Tu prueba gratis de {{.tierName}} termina el {{.date}}',
 '{{define "preheader"}}Tu prueba gratis termina el {{.date}}{{end}}
<div>
{{badge "info" "Membresía"}}
<h1>Tu prueba gratis termina el {{.date}}</h1>
<p>Después, tu membresía {{.tierName}} continúa a <strong>{{.amount}}</strong> {{if .isYearly}}al año{{else}}al mes{{end}}, cobrados a tu tarjeta guardada. Si no quieres continuar, cancela antes y no se te cobrará.</p>
{{button .manageUrl "Gestionar mi membresía"}}
</div>',
 'Después, tu membresía {{.tierName}} continúa a {{.amount}} {{if .isYearly}}al año{{else}}al mes{{end}}, cobrados a tu tarjeta guardada. Si no quieres continuar, cancela antes y no se te cobrará.

{{.manageUrl}}'),
('payment.membership.renewed', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.renewed' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 '{{if .isTrialConverted}}انتهت تجربتك لـ {{.tierName}}: أنت الآن عضو{{else}}تم تجديد عضويتك {{.tierName}}{{end}}',
 '{{define "preheader"}}{{if .isTrialConverted}}مرحبًا بك في {{.tierName}}{{else}}تم تجديد عضويتك {{.tierName}}{{end}}{{end}}
<div>
{{badge "info" "العضوية"}}
<h1>{{if .isTrialConverted}}مرحبًا بك في {{.tierName}}{{else}}تم تجديد عضويتك {{.tierName}}{{end}}</h1>
<p>خصمنا {{.amount}} من بطاقتك عن {{if .isYearly}}السنة القادمة{{else}}الشهر القادم{{end}}. تستمر مزايا {{.tierName}} حتى <strong>{{.date}}</strong>. يمكنك تغيير الخطة أو الإلغاء في أي وقت من حسابك.</p>
{{button .manageUrl "إدارة عضويتي"}}
</div>',
 'خصمنا {{.amount}} من بطاقتك عن {{if .isYearly}}السنة القادمة{{else}}الشهر القادم{{end}}. تستمر مزايا {{.tierName}} حتى {{.date}}. يمكنك تغيير الخطة أو الإلغاء في أي وقت من حسابك.

{{.manageUrl}}'),
('payment.membership.payment_failed', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.payment_failed' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 '{{if .isLapsed}}انتهت عضويتك {{.tierName}}{{else}}تعذّر تجديد عضويتك {{.tierName}}{{end}}',
 '{{define "preheader"}}{{if .isLapsed}}انتهت عضويتك {{.tierName}}{{else}}لم تتم دفعة عضويتك{{end}}{{end}}
<div>
{{badge "info" "دفعة العضوية"}}
<h1>{{if .isLapsed}}انتهت عضويتك {{.tierName}}{{else}}لم تتم دفعة عضويتك{{end}}</h1>
<p>{{if .isLapsed}}حاولنا خصم {{.amount}} عدة مرات دون نجاح، لذلك توقفت مزايا {{.tierName}}. يمكنك الانضمام من جديد في أي وقت.{{else}}تعذّر خصم {{.amount}} من بطاقتك. تبقى مزايا {{.tierName}} فعّالة بينما نعيد المحاولة في الأيام القادمة. يرجى التأكد من أن بطاقتك محدّثة.{{end}}</p>
{{button .actionUrl "{{if .isLapsed}}عرض العضوية{{else}}تحديث بطاقتي{{end}}"}}
</div>',
 '{{if .isLapsed}}حاولنا خصم {{.amount}} عدة مرات دون نجاح، لذلك توقفت مزايا {{.tierName}}. يمكنك الانضمام من جديد في أي وقت.{{else}}تعذّر خصم {{.amount}} من بطاقتك. تبقى مزايا {{.tierName}} فعّالة بينما نعيد المحاولة في الأيام القادمة. يرجى التأكد من أن بطاقتك محدّثة.{{end}}

{{.actionUrl}}'),
('payment.membership.trial_ending', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.trial_ending' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 'تنتهي تجربتك المجانية لـ {{.tierName}} في {{.date}}',
 '{{define "preheader"}}تنتهي تجربتك المجانية في {{.date}}{{end}}
<div>
{{badge "info" "العضوية"}}
<h1>تنتهي تجربتك المجانية في {{.date}}</h1>
<p>بعد ذلك تستمر عضويتك {{.tierName}} بمبلغ <strong>{{.amount}}</strong> {{if .isYearly}}سنويًا{{else}}شهريًا{{end}} يُخصم من بطاقتك المحفوظة. إذا لم ترغب في المتابعة، ألغِ قبل ذلك ولن يتم الخصم منك.</p>
{{button .manageUrl "إدارة عضويتي"}}
</div>',
 'بعد ذلك تستمر عضويتك {{.tierName}} بمبلغ {{.amount}} {{if .isYearly}}سنويًا{{else}}شهريًا{{end}} يُخصم من بطاقتك المحفوظة. إذا لم ترغب في المتابعة، ألغِ قبل ذلك ولن يتم الخصم منك.

{{.manageUrl}}'),
('payment.membership.renewed', 'yo', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.renewed' AND t.locale = 'yo' AND t.channel = 'email'), 'transactional',
 '{{if .isTrialConverted}}Ìdánwò {{.tierName}} yín ti parí: ọmọ ẹgbẹ́ ni yín báyìí{{else}}A ti tún ẹ̀tọ́ ọmọ ẹgbẹ́ {{.tierName}} yín ṣe{{end}}',
 '{{define "preheader"}}{{if .isTrialConverted}}Káàbọ̀ sí {{.tierName}}{{else}}A ti tún ẹ̀tọ́ ọmọ ẹgbẹ́ {{.tierName}} yín ṣe{{end}}{{end}}
<div>
{{badge "info" "Ẹ̀tọ́ ọmọ ẹgbẹ́"}}
<h1>{{if .isTrialConverted}}Káàbọ̀ sí {{.tierName}}{{else}}A ti tún ẹ̀tọ́ ọmọ ẹgbẹ́ {{.tierName}} yín ṣe{{end}}</h1>
<p>A gba {{.amount}} lórí káàdì yín fún {{if .isYearly}}ọdún tó kàn{{else}}oṣù tó kàn{{end}}. Àwọn àǹfààní {{.tierName}} yín ń tẹ̀síwájú títí di <strong>{{.date}}</strong>. Ẹ lè yí ètò padà tàbí fagilé nígbàkigbà láti inú àkọọ́lẹ̀ yín.</p>
{{button .manageUrl "Ṣàkóso ẹ̀tọ́ ọmọ ẹgbẹ́ mi"}}
</div>',
 'A gba {{.amount}} lórí káàdì yín fún {{if .isYearly}}ọdún tó kàn{{else}}oṣù tó kàn{{end}}. Àwọn àǹfààní {{.tierName}} yín ń tẹ̀síwájú títí di {{.date}}. Ẹ lè yí ètò padà tàbí fagilé nígbàkigbà láti inú àkọọ́lẹ̀ yín.

{{.manageUrl}}'),
('payment.membership.payment_failed', 'yo', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.payment_failed' AND t.locale = 'yo' AND t.channel = 'email'), 'transactional',
 '{{if .isLapsed}}Ẹ̀tọ́ ọmọ ẹgbẹ́ {{.tierName}} yín ti parí{{else}}A kò lè tún ẹ̀tọ́ ọmọ ẹgbẹ́ {{.tierName}} yín ṣe{{end}}',
 '{{define "preheader"}}{{if .isLapsed}}Ẹ̀tọ́ ọmọ ẹgbẹ́ {{.tierName}} yín ti parí{{else}}Ìsanwó ẹ̀tọ́ ọmọ ẹgbẹ́ yín kò ṣeéṣe{{end}}{{end}}
<div>
{{badge "info" "Ìsanwó ẹ̀tọ́ ọmọ ẹgbẹ́"}}
<h1>{{if .isLapsed}}Ẹ̀tọ́ ọmọ ẹgbẹ́ {{.tierName}} yín ti parí{{else}}Ìsanwó ẹ̀tọ́ ọmọ ẹgbẹ́ yín kò ṣeéṣe{{end}}</h1>
<p>{{if .isLapsed}}A gbìyànjú láti gba {{.amount}} ní ọ̀pọ̀ ìgbà láìṣeéṣe, nítorí náà àwọn àǹfààní {{.tierName}} yín ti dúró. Ẹ lè darapọ̀ padà nígbàkigbà.{{else}}A kò lè gba {{.amount}} lórí káàdì yín. Àwọn àǹfààní {{.tierName}} yín ṣì wà lọ́wọ́ nígbà tí a ó tún gbìyànjú láàrín ọjọ́ díẹ̀ sí i. Ẹ jọ̀wọ́ ṣàyẹ̀wò pé káàdì yín wà ní àkókò.{{end}}</p>
{{button .actionUrl "{{if .isLapsed}}Wo ẹ̀tọ́ ọmọ ẹgbẹ́{{else}}Ṣe àtúnṣe káàdì mi{{end}}"}}
</div>',
 '{{if .isLapsed}}A gbìyànjú láti gba {{.amount}} ní ọ̀pọ̀ ìgbà láìṣeéṣe, nítorí náà àwọn àǹfààní {{.tierName}} yín ti dúró. Ẹ lè darapọ̀ padà nígbàkigbà.{{else}}A kò lè gba {{.amount}} lórí káàdì yín. Àwọn àǹfààní {{.tierName}} yín ṣì wà lọ́wọ́ nígbà tí a ó tún gbìyànjú láàrín ọjọ́ díẹ̀ sí i. Ẹ jọ̀wọ́ ṣàyẹ̀wò pé káàdì yín wà ní àkókò.{{end}}

{{.actionUrl}}'),
('payment.membership.trial_ending', 'yo', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'payment.membership.trial_ending' AND t.locale = 'yo' AND t.channel = 'email'), 'transactional',
 'Ìdánwò ọ̀fẹ́ {{.tierName}} yín parí ní {{.date}}',
 '{{define "preheader"}}Ìdánwò ọ̀fẹ́ yín parí ní {{.date}}{{end}}
<div>
{{badge "info" "Ẹ̀tọ́ ọmọ ẹgbẹ́"}}
<h1>Ìdánwò ọ̀fẹ́ yín parí ní {{.date}}</h1>
<p>Lẹ́yìn náà ẹ̀tọ́ ọmọ ẹgbẹ́ {{.tierName}} yín ń tẹ̀síwájú ní <strong>{{.amount}}</strong> {{if .isYearly}}lọ́dún{{else}}lóṣooṣù{{end}}, a ó gba lórí káàdì yín tí a fi pamọ́. Bí ẹ kò bá fẹ́ tẹ̀síwájú, ẹ fagilé ṣáájú, a kò sì ní gba owó lọ́wọ́ yín.</p>
{{button .manageUrl "Ṣàkóso ẹ̀tọ́ ọmọ ẹgbẹ́ mi"}}
</div>',
 'Lẹ́yìn náà ẹ̀tọ́ ọmọ ẹgbẹ́ {{.tierName}} yín ń tẹ̀síwájú ní {{.amount}} {{if .isYearly}}lọ́dún{{else}}lóṣooṣù{{end}}, a ó gba lórí káàdì yín tí a fi pamọ́. Bí ẹ kò bá fẹ́ tẹ̀síwájú, ẹ fagilé ṣáájú, a kò sì ní gba owó lọ́wọ́ yín.

{{.manageUrl}}');
