-- S262 (ADR 0005): fr, es, ar and yo for the delivery run (run.*) and pre-order (preorder.*) messages that 0009, 0011,
-- 0013 and 0019 seeded in English only. Same keys, channels, categories and placeholders as the English rows: the
-- locale-parity test (TestRunAndPreorderTemplatesExistInEveryLocale) fails when a locale, a channel or a placeholder
-- is missing. The customer's language comes from identity (subscribers carry the locale they signed up in); a
-- language without a row falls back to English.
-- SMS: fr and es stay in one GSM-7 segment (no characters outside the GSM 03.38 alphabet); ar and yo cannot (Arabic
-- script, Yoruba tone marks and dots), so they are UCS-2 and may take more segments.
-- WhatsApp (channel chat) rows carry the same Meta template name as English in the subject and the same positional
-- parameters in the body. Meta approves one template per name and language: submit the translated bodies listed in
-- docs/runbooks/production-readiness.md under the same names with the matching language code before using them.
-- Arabic email bodies are wrapped in dir="rtl". Each row becomes the next version of its key/locale/channel.

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('preorder.announce', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'fr' AND t.channel = 'email'), 'reminder',
 '{{.name}} est ouverte : commandez avant le {{.closesDisplay}}',
 '{{define "preheader"}}Commandez avant le {{.closesDisplay}}, livraison le {{.deliversDisplay}}.{{end}}
{{badge "warning" "Précommande ouverte"}}
<h1>{{if .headline}}{{.headline}}{{else}}La précommande est ouverte à {{.where}}{{end}}</h1>
<p>Bonjour {{.firstName}}, <strong>{{.name}}</strong> est ouverte. Commandez avec vos voisins et nous livrons tout en une seule journée.</p>
{{facts "Commander avant le" .closesDisplay "Livraison" .deliversDisplay "Livraison à domicile" .doorFee "Retrait" .pickupFee "Créneau de retrait" .pickupWindow}}
{{button .emailUrl "Précommander maintenant"}}
<p><small>Vous êtes débité au paiement et vous pouvez modifier ou annuler les articles en précommande avec un remboursement intégral jusqu’à la clôture de la précommande.</small></p>',
 'Bonjour {{.firstName}}, {{.name}} est ouverte. Commandez avant le {{.closesDisplay}} et nous livrons le {{.deliversDisplay}}.
{{if .doorFee}}Livraison à domicile : {{.doorFee}} forfaitaire.
{{end}}{{if .pickupFee}}Retrait : {{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}.
{{end}}Vous êtes débité au paiement et vous pouvez modifier ou annuler les articles en précommande avec un remboursement intégral jusqu’à la clôture de la précommande.
Précommander maintenant : {{.emailUrl}}{{if .unsubscribeUrl}}

Se désabonner des alertes de précommande pour {{.where}} : {{.unsubscribeUrl}}{{end}}'),
('preorder.announce', 'fr', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'fr' AND t.channel = 'push'), 'reminder',
 'Précommande ouverte à {{.where}}',
 '',
 'Commandez avant le {{.closesDisplay}}, livraison le {{.deliversDisplay}}.{{if .doorFee}} Livraison à domicile {{.doorFee}}.{{end}}'),
('preorder.announce', 'fr', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'fr' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 '{{.smsCopy}}'),
('preorder.closed', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.closed' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 '{{.name}} est close : livraison le {{.deliversDisplay}}',
 '{{define "preheader"}}Votre précommande est confirmée pour le {{.deliversDisplay}}.{{end}}
{{badge "success" "C’est confirmé"}}
<h1>Votre précommande est confirmée</h1>
<p>Bonjour {{.firstName}}, <strong>{{.name}}</strong> est maintenant close. Nous livrons votre précommande le <strong>{{.deliversDisplay}}</strong>{{if .pickupWindow}} (retrait {{.pickupWindow}}){{end}}.</p>
{{button .emailUrl "Voir votre précommande"}}
<p><small>Si vous avez accepté les SMS, nous vous écrirons quand elle sera en cours de livraison.</small></p>',
 'Bonjour {{.firstName}}, {{.name}} est maintenant close. Nous livrons votre précommande le {{.deliversDisplay}}{{if .pickupWindow}} (retrait {{.pickupWindow}}){{end}}. Si vous avez accepté les SMS, nous vous écrirons quand elle sera en cours de livraison. {{.emailUrl}}'),
('preorder.confirmed', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.confirmed' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 'C’est réservé : {{.name}}',
 '{{define "preheader"}}Précommande {{.preorderNumber}}, livraison le {{.deliversDisplay}}. Débité : {{.total}}.{{end}}
{{badge "success" "C’est réservé"}}
<h1>C’est réservé : {{.name}}</h1>
<p>Bonjour {{.firstName}}, merci pour votre précommande. Nous la livrons avec celles de la communauté le <strong>{{.deliversDisplay}}</strong>.</p>
{{facts "Numéro de précommande" .preorderNumber "Livraison" .deliversDisplay .fulfilmentText (or .doorFee .pickupFee) "Débité" .total}}
{{button .emailUrl "Voir votre précommande"}}
{{template "packages" (index . "packages")}}
{{template "totals" (index . "totals")}}
{{template "address" (index . "shipTo")}}
{{callout "info" (printf "%sLes articles en précommande peuvent être modifiés ou annulés avec un remboursement intégral jusqu’au %s." (or .nowNote "") .cancelUntil)}}
{{with index . "helpUrl"}}<p><small>Besoin d’aide pour cette commande ? Consultez le <a href="{{.}}">Centre d’aide</a> ou répondez à cet e-mail.</small></p>{{end}}',
 'Bonjour {{.firstName}}, c’est réservé : {{.name}}.
N° de précommande : {{.preorderNumber}}
Livraison : {{.deliversDisplay}}
{{.fulfilmentText}} : {{if .doorFee}}{{.doorFee}}{{else}}{{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}{{end}}
Débité : {{.total}}
{{if .nowNote}}{{.nowNote}} {{end}}Les articles en précommande peuvent être modifiés ou annulés avec un remboursement intégral jusqu’au {{.cancelUntil}}. Si vous avez accepté les SMS, nous vous écrirons le jour de la livraison.
{{.emailUrl}}'),
('preorder.delivery_missed', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.delivery_missed' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 'Nous avons manqué votre livraison : {{.name}}',
 '{{define "preheader"}}Nous sommes désolés. Choisissez une nouvelle date ou un remboursement intégral.{{end}}
{{badge "danger" "Livraison manquée"}}
<h1>Nous sommes désolés, nous avons manqué votre livraison</h1>
<p>Bonjour {{.firstName}}, nous n’avons pas pu livrer <strong>{{.name}}</strong> (commande {{.orderNumber}}) le {{.missedDisplay}}. Choisissez ce qui vous convient :</p>
{{if .newDateUrl}}{{button .newDateUrl (printf "La livrer le %s" .newDateDisplay)}}{{end}}
{{if .refundUrl}}{{secondaryButton .refundUrl (printf "Obtenir un remboursement intégral de %s" .total)}}{{end}}
{{template "packages" (index . "packages")}}
{{with index . "helpUrl"}}<p><small>Besoin d’aide pour cette commande ? Consultez le <a href="{{.}}">Centre d’aide</a> ou répondez à cet e-mail.</small></p>{{end}}',
 'Bonjour {{.firstName}}, nous sommes désolés : nous n’avons pas pu livrer {{.name}} (commande {{.orderNumber}}) le {{.missedDisplay}}. Choisissez ce qui vous convient :
{{if .newDateUrl}}La livrer le {{.newDateDisplay}} : {{.newDateUrl}}
{{end}}{{if .refundUrl}}Obtenir un remboursement intégral de {{.total}} : {{.refundUrl}}
{{end}}Vos commandes : {{.orderUrl}}'),
('preorder.last_call', 'fr', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.last_call' AND t.locale = 'fr' AND t.channel = 'push'), 'reminder',
 'Dernier appel : {{.name}}',
 '',
 'La précommande se termine le {{.closesDisplay}}. Livraison le {{.deliversDisplay}}.'),
('preorder.last_call', 'fr', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.last_call' AND t.locale = 'fr' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: dernier appel ! {{.name}} ferme {{.closesDisplay}}. {{.smsUrl}} STOP pour quitter'),
('preorder.late_order.payment_link', 'fr', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.late_order.payment_link' AND t.locale = 'fr' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: payez {{.total}} votre précommande : {{.paymentUrl}} Rép. STOP pour quitter'),
('preorder.out_for_delivery', 'fr', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.out_for_delivery' AND t.locale = 'fr' AND t.channel = 'push'), 'transactional',
 'Votre précommande est en cours de livraison',
 '',
 '{{.name}} arrive aujourd’hui{{if .deliveryWindow}}, {{.deliveryWindow}}{{end}}.'),
('preorder.out_for_delivery', 'fr', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.out_for_delivery' AND t.locale = 'fr' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: {{.name}} en livraison aujourd''hui{{if .deliveryWindow}}, {{.deliveryWindow}}{{end}}. {{.smsUrl}} Rép. STOP pour quitter'),
('preorder.reminder', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.reminder' AND t.locale = 'fr' AND t.channel = 'email'), 'reminder',
 'Plus que 48 heures : {{.name}} se termine le {{.closesDisplay}}',
 '{{define "preheader"}}Commandez avant le {{.closesDisplay}} pour être livré le {{.deliversDisplay}}.{{end}}
{{badge "warning" "Plus que 48 heures"}}
<h1>Plus que 48 heures pour précommander</h1>
<p>Bonjour {{.firstName}}, <strong>{{.name}}</strong> se termine le <strong>{{.closesDisplay}}</strong>. Tout ce qui est commandé d’ici là arrive le <strong>{{.deliversDisplay}}</strong>.</p>
{{facts "Commander avant le" .closesDisplay "Livraison" .deliversDisplay "Livraison à domicile" .doorFee "Retrait" .pickupFee "Créneau de retrait" .pickupWindow}}
{{button .emailUrl "Terminer votre précommande"}}',
 'Bonjour {{.firstName}}, {{.name}} se termine le {{.closesDisplay}}. Tout ce qui est commandé d’ici là arrive le {{.deliversDisplay}}{{if .doorFee}} avec livraison à domicile pour {{.doorFee}}{{end}}{{if .pickupFee}}{{if .doorFee}} ou{{else}} avec{{end}} retrait ({{.pickupFee}}){{end}}.
Terminer votre précommande : {{.emailUrl}}{{if .unsubscribeUrl}}

Se désabonner : {{.unsubscribeUrl}}{{end}}'),
('preorder.reminder', 'fr', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.reminder' AND t.locale = 'fr' AND t.channel = 'push'), 'reminder',
 'Plus que 48 heures pour précommander',
 '',
 '{{.name}} se termine le {{.closesDisplay}}. Livraison le {{.deliversDisplay}}.'),
('preorder.waitlist.opened', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.waitlist.opened' AND t.locale = 'fr' AND t.channel = 'email'), 'reminder',
 'La précommande est ouverte à {{.where}}',
 '{{define "preheader"}}{{.name}} est ouverte jusqu’au {{.closesDisplay}}.{{end}}
{{badge "warning" "Vous l’avez demandé, c’est là"}}
<h1>Nous livrons à {{.where}}</h1>
<p>Bonjour {{.firstName}}, vous nous avez demandé de vous prévenir quand une précommande ouvre à {{.where}}. <strong>{{.name}}</strong> est ouverte dès maintenant.</p>
{{facts "Commander avant le" .closesDisplay "Livraison" .deliversDisplay "Livraison à domicile" .doorFee "Retrait" .pickupFee "Créneau de retrait" .pickupWindow}}
{{button .emailUrl "Voir le contenu de la précommande"}}',
 'Bonjour {{.firstName}}, vous nous avez demandé de vous prévenir quand une précommande ouvre à {{.where}}. {{.name}} est ouverte jusqu’au {{.closesDisplay}}, livraison le {{.deliversDisplay}}.{{if .doorFee}} La livraison à domicile coûte {{.doorFee}} au forfait.{{end}}{{if .pickupFee}} Le retrait coûte {{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}.{{end}}
Voir le contenu de la précommande : {{.emailUrl}}{{if .unsubscribeUrl}}

Quitter la liste d’attente : {{.unsubscribeUrl}}{{end}}'),
('run.announced', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.announced' AND t.locale = 'fr' AND t.channel = 'email'), 'reminder',
 'Une tournée de livraison à {{.town}} le {{.deliveryDay}}',
 '{{define "preheader"}}Nous prenons la route vers {{.town}} le {{.deliveryDay}}. Les commandes ouvrent bientôt.{{end}}
{{badge "info" "Tournée de livraison annoncée"}}
<h1>Nous venons à {{.town}}</h1>
<p>Bonjour {{.firstName}}, une tournée de livraison AfroTransact vers <strong>{{.town}}</strong> est prévue le <strong>{{.deliveryDay}}</strong>. Nous vous préviendrons dès l’ouverture des commandes.</p>
{{facts "Jour de livraison" .deliveryDay "Créneau de livraison" .windowDisplay "Commander avant le" .cutoffDisplay "Frais de livraison" .feeDisplay "Mode" .dropText}}
{{button .emailUrl "Voir la tournée"}}
{{if .whatsappInviteUrl}}<p><small>Rejoignez la communauté WhatsApp de {{.town}} pour suivre les nouvelles de vos voisins et de nos chauffeurs : <a href="{{.whatsappInviteUrl}}">rejoindre</a>.</small></p>{{end}}',
 'Bonjour {{.firstName}}, une tournée de livraison AfroTransact vers {{.town}} est prévue le {{.deliveryDay}} ({{.windowDisplay}}). Commandez avant le {{.cutoffDisplay}} ; la livraison est {{.feeDisplay}}. {{.dropText}}.
Nous vous préviendrons dès l’ouverture des commandes.
Voir la tournée : {{.emailUrl}}{{if .unsubscribeUrl}}

Se désabonner des alertes de tournée pour {{.town}} : {{.unsubscribeUrl}}{{end}}'),
('run.announced', 'fr', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.announced' AND t.locale = 'fr' AND t.channel = 'push'), 'reminder',
 'Tournée de livraison à {{.town}}',
 '',
 'Nous venons à {{.town}} le {{.deliveryDay}}. Les commandes ouvrent bientôt.'),
('run.cancelled', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 'La tournée de livraison à {{.town}} du {{.deliveryDay}} est annulée',
 '{{define "preheader"}}Vous êtes remboursé intégralement, frais de livraison compris.{{end}}
{{badge "danger" "Tournée annulée"}}
<h1>Nous avons annulé la tournée de {{.town}}</h1>
<p>Bonjour {{.firstName}}, nous sommes désolés : la tournée de livraison à {{.town}} prévue le <strong>{{.deliveryDay}}</strong> n’aura pas lieu. {{.reasonText}}</p>
{{callout "info" .refundNote}}
{{button .locationUrl "Voir la prochaine tournée"}}',
 'Bonjour {{.firstName}}, nous sommes désolés : la tournée de livraison à {{.town}} prévue le {{.deliveryDay}} n’aura pas lieu. {{.reasonText}}
{{.refundNote}}
Voir la prochaine tournée : {{.locationUrl}}'),
('run.cancelled', 'fr', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'fr' AND t.channel = 'push'), 'transactional',
 'La tournée de {{.town}} est annulée',
 '',
 'Vous êtes remboursé intégralement, frais de livraison compris.'),
('run.cancelled', 'fr', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'fr' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: nous avons annulé la tournée de {{.town}} du {{.deliveryDay}}. Votre commande est remboursée intégralement, frais de livraison compris. Rép. STOP pour quitter'),
('run.confirmed', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.confirmed' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 'La tournée de {{.town}} est maintenue : livraison le {{.deliveryDay}}',
 '{{define "preheader"}}Votre commande est confirmée pour le {{.deliveryDay}}, {{.windowText}}.{{end}}
{{badge "success" "Tournée confirmée"}}
<h1>La tournée de {{.town}} est maintenue</h1>
<p>Bonjour {{.firstName}}, assez de commandes sont arrivées, la tournée de {{.town}} a donc bien lieu. Votre commande est confirmée pour le <strong>{{.deliveryDay}}</strong>.</p>
{{facts "Jour de livraison" .deliveryDay "Créneau de livraison" .windowDisplay "Mode" .dropText "Code de remise" .handoverCode}}
{{button .emailUrl "Voir la tournée"}}',
 'Bonjour {{.firstName}}, la tournée de {{.town}} a bien lieu. Votre commande est confirmée pour le {{.deliveryDay}} ({{.windowDisplay}}). {{.dropText}}.{{if .handoverCode}} Code de remise : {{.handoverCode}}.{{end}}
Voir la tournée : {{.emailUrl}}'),
('run.confirmed', 'fr', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.confirmed' AND t.locale = 'fr' AND t.channel = 'push'), 'transactional',
 'La tournée de {{.town}} est maintenue',
 '',
 'Votre commande est confirmée pour le {{.deliveryDay}}, {{.windowText}}.'),
('run.cutoff_reminder', 'fr', 'chat', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'fr' AND t.channel = 'chat'), 'reminder',
 'afrotransact_run_cutoff_reminder',
 '',
 '{{.town}}
{{.deliveryDay}}
{{.cutoffText}}
{{.feeDisplay}}
{{.whatsappUrl}}'),
('run.cutoff_reminder', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'fr' AND t.channel = 'email'), 'reminder',
 'Dernière chance pour la tournée de {{.town}} : commandez avant le {{.cutoffText}}',
 '{{define "preheader"}}La tournée de {{.town}} se termine le {{.cutoffText}}. Livraison le {{.deliveryDay}}.{{end}}
{{badge "warning" "Bientôt terminé"}}
<h1>La tournée de {{.town}} se termine bientôt</h1>
<p>Bonjour {{.firstName}}, les commandes pour la tournée de livraison à {{.town}} se terminent le <strong>{{.cutoffDisplay}}</strong>. Nous livrons le <strong>{{.deliveryDay}}</strong>.</p>
{{facts "Commander avant le" .cutoffDisplay "Jour de livraison" .deliveryDay "Créneau de livraison" .windowDisplay "Frais de livraison" .feeDisplay "Mode" .dropText "Commandes à ce jour" .minimumText}}
{{button .emailUrl "Voir la tournée"}}',
 'Bonjour {{.firstName}}, les commandes pour la tournée de livraison à {{.town}} se terminent le {{.cutoffDisplay}}. Nous livrons le {{.deliveryDay}} ({{.windowDisplay}}) ; la livraison est {{.feeDisplay}}.
Voir la tournée : {{.emailUrl}}{{if .unsubscribeUrl}}

Se désabonner des alertes de tournée pour {{.town}} : {{.unsubscribeUrl}}{{end}}'),
('run.cutoff_reminder', 'fr', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'fr' AND t.channel = 'push'), 'reminder',
 'Dernière chance pour la tournée de {{.town}}',
 '',
 'Commandez avant le {{.cutoffText}}. Livraison le {{.deliveryDay}}.'),
('run.cutoff_reminder', 'fr', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'fr' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: dernière chance, tournée de {{.town}}. Commandez avant {{.cutoffText}}. Livraison {{.deliveryDay}}. {{.smsUrl}} Rép. STOP pour quitter'),
('run.opened', 'fr', 'chat', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'fr' AND t.channel = 'chat'), 'reminder',
 'afrotransact_run_opened',
 '',
 '{{.town}}
{{.deliveryDay}}
{{.cutoffText}}
{{.feeDisplay}}
{{.whatsappUrl}}'),
('run.opened', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'fr' AND t.channel = 'email'), 'reminder',
 'La tournée de livraison à {{.town}} est ouverte : commandez avant le {{.cutoffText}}',
 '{{define "preheader"}}Commandez avant le {{.cutoffText}}. Livraison le {{.deliveryDay}}, {{.windowText}}.{{end}}
{{badge "warning" "Tournée ouverte"}}
<h1>La tournée de livraison à {{.town}} est ouverte</h1>
<p>Bonjour {{.firstName}}, nous apportons des commandes AfroTransact à {{.town}} le <strong>{{.deliveryDay}}</strong>. Commandez avant la clôture et la vôtre part dans le même trajet.</p>
{{facts "Jour de livraison" .deliveryDay "Créneau de livraison" .windowDisplay "Commander avant le" .cutoffDisplay "Frais de livraison" .feeDisplay "Mode" .dropText "Commandes à ce jour" .minimumText}}
{{button .emailUrl "Voir la tournée"}}
{{if .whatsappInviteUrl}}<p><small>Rejoignez la communauté WhatsApp de {{.town}} pour suivre les nouvelles de vos voisins et de nos chauffeurs : <a href="{{.whatsappInviteUrl}}">rejoindre</a>.</small></p>{{end}}',
 'Bonjour {{.firstName}}, la tournée de livraison à {{.town}} est ouverte. Nous livrons le {{.deliveryDay}} ({{.windowDisplay}}). Commandez avant le {{.cutoffDisplay}} ; la livraison est {{.feeDisplay}}. {{.dropText}}.
Voir la tournée : {{.emailUrl}}{{if .unsubscribeUrl}}

Se désabonner des alertes de tournée pour {{.town}} : {{.unsubscribeUrl}}{{end}}'),
('run.opened', 'fr', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'fr' AND t.channel = 'push'), 'reminder',
 'La tournée de livraison à {{.town}} est ouverte',
 '',
 'Commandez avant le {{.cutoffText}}. Livraison le {{.deliveryDay}}.{{if .feeDisplay}} Livraison {{.feeDisplay}}.{{end}}'),
('run.opened', 'fr', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'fr' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: tournée de {{.town}} ouverte. Livraison {{.deliveryDay}}. Commandez avant {{.cutoffText}}. {{.smsUrl}} Rép. STOP pour quitter'),
('run.out_for_delivery', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 'Votre livraison à {{.town}} arrive aujourd’hui',
 '{{define "preheader"}}Nous sommes partis pour {{.town}}. Créneau de livraison {{.windowText}}.{{end}}
{{badge "success" "En route"}}
<h1>Nous sommes en route vers {{.town}}</h1>
<p>Bonjour {{.firstName}}, la tournée de livraison à {{.town}} est partie. Votre commande arrive aujourd’hui, {{.windowDisplay}}.</p>
{{facts "Créneau de livraison" .windowDisplay "Mode" .dropText "Code de remise" .handoverCode}}
{{if .handoverCode}}<p>Présentez ce code pour récupérer votre commande : <strong>{{.handoverCode}}</strong>.</p>{{end}}
{{button .emailUrl "Suivre la tournée"}}',
 'Bonjour {{.firstName}}, la tournée de livraison à {{.town}} est partie. Votre commande arrive aujourd’hui, {{.windowDisplay}}. {{.dropText}}.{{if .handoverCode}} Code de remise : {{.handoverCode}}.{{end}}
Suivre la tournée : {{.emailUrl}}'),
('run.out_for_delivery', 'fr', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'fr' AND t.channel = 'push'), 'transactional',
 'Votre commande pour {{.town}} est en route',
 '',
 'Arrivée aujourd’hui, {{.windowText}}.{{if .handoverCode}} Code de remise {{.handoverCode}}.{{end}}'),
('run.out_for_delivery', 'fr', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'fr' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: votre commande pour {{.town}} est en cours de livraison aujourd''hui, {{.windowText}}.{{if .handoverCode}} Code de remise {{.handoverCode}}.{{end}} Rép. STOP pour quitter'),
('run.subscription.confirm', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.subscription.confirm' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 'Confirmez vos alertes de tournée pour {{.town}}',
 '{{define "preheader"}}Un clic et nous vous préviendrons quand une tournée vers {{.town}} ouvre.{{end}}
<h1>Une dernière étape</h1>
<p>Confirmez votre e-mail pour être prévenu en premier quand AfroTransact ouvre une tournée de livraison vers <strong>{{.town}}</strong> : à l’ouverture des commandes, avant leur clôture, et quand nous sommes en route. Rien n’est envoyé tant que vous n’avez pas confirmé.</p>
{{button .confirmUrl "Confirmer mes alertes"}}
<p><small>Vous n’êtes pas à l’origine de cette demande ? Ignorez cet e-mail et rien ne sera envoyé.</small></p>',
 'Confirmez votre e-mail pour être prévenu en premier quand AfroTransact ouvre une tournée de livraison vers {{.town}} : à l’ouverture des commandes, avant leur clôture, et quand nous sommes en route. Rien n’est envoyé tant que vous n’avez pas confirmé.

Confirmer mes alertes : {{.confirmUrl}}

Vous n’êtes pas à l’origine de cette demande ? Ignorez cet e-mail et rien ne sera envoyé.'),
('run.subscription.welcome', 'fr', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.subscription.welcome' AND t.locale = 'fr' AND t.channel = 'email'), 'transactional',
 'Vous serez le premier informé des tournées de {{.town}}',
 '{{define "preheader"}}Vous êtes inscrit aux alertes de tournée de {{.town}} par {{.channelList}}.{{end}}
{{badge "success" "Inscrit"}}
<h1>Vous êtes sur la liste pour {{.town}}</h1>
<p>Bonjour {{.firstName}}, nous vous préviendrons par {{.channelList}} quand une tournée de livraison vers <strong>{{.town}}</strong> ouvre, quand elle va bientôt se terminer et quand nous sommes en route.</p>
{{button .locationUrl "Voir les prochaines tournées"}}
{{if .hasWhatsappCommunity}}<p>Vos voisins et nos chauffeurs échangent des nouvelles dans la communauté WhatsApp de {{.town}} : <a href="{{.whatsappInviteUrl}}">rejoindre la communauté</a>.</p>{{end}}
<p><small>Vous pouvez modifier ces réglages ou tout arrêter à tout moment depuis votre compte, ou avec le lien de désabonnement présent dans chaque message.</small></p>',
 'Bonjour {{.firstName}}, nous vous préviendrons par {{.channelList}} quand une tournée de livraison vers {{.town}} ouvre, quand elle va bientôt se terminer et quand nous sommes en route.
Voir les prochaines tournées : {{.locationUrl}}{{if .hasWhatsappCommunity}}
Rejoindre la communauté WhatsApp de {{.town}} : {{.whatsappInviteUrl}}{{end}}
Se désabonner à tout moment : {{.unsubscribeUrl}}'),
('preorder.announce', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'es' AND t.channel = 'email'), 'reminder',
 '{{.name}} está abierta: pide antes del {{.closesDisplay}}',
 '{{define "preheader"}}Pide antes del {{.closesDisplay}}, entrega el {{.deliversDisplay}}.{{end}}
{{badge "warning" "Preventa abierta"}}
<h1>{{if .headline}}{{.headline}}{{else}}La preventa está abierta en {{.where}}{{end}}</h1>
<p>Hola {{.firstName}}, <strong>{{.name}}</strong> está abierta. Pide junto a tus vecinos y entregamos todo en un solo día.</p>
{{facts "Pide antes del" .closesDisplay "Entrega" .deliversDisplay "Entrega a domicilio" .doorFee "Recogida" .pickupFee "Horario de recogida" .pickupWindow}}
{{button .emailUrl "Reservar ahora"}}
<p><small>El cobro se hace al pagar y puedes cambiar o cancelar los artículos de la preventa con reembolso total hasta que se cierre.</small></p>',
 'Hola {{.firstName}}, {{.name}} está abierta. Pide antes del {{.closesDisplay}} y entregamos el {{.deliversDisplay}}.
{{if .doorFee}}Entrega a domicilio: {{.doorFee}} tarifa fija.
{{end}}{{if .pickupFee}}Recogida: {{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}.
{{end}}El cobro se hace al pagar y puedes cambiar o cancelar los artículos de la preventa con reembolso total hasta que se cierre.
Reservar ahora: {{.emailUrl}}{{if .unsubscribeUrl}}

Cancelar las alertas de preventa de {{.where}}: {{.unsubscribeUrl}}{{end}}'),
('preorder.announce', 'es', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'es' AND t.channel = 'push'), 'reminder',
 'Preventa abierta en {{.where}}',
 '',
 'Pide antes del {{.closesDisplay}}, entrega el {{.deliversDisplay}}.{{if .doorFee}} Entrega a domicilio {{.doorFee}}.{{end}}'),
('preorder.announce', 'es', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'es' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 '{{.smsCopy}}'),
('preorder.closed', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.closed' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 '{{.name}} está cerrada: entrega el {{.deliversDisplay}}',
 '{{define "preheader"}}Tu preventa está asegurada para el {{.deliversDisplay}}.{{end}}
{{badge "success" "Asegurada"}}
<h1>Tu preventa está asegurada</h1>
<p>Hola {{.firstName}}, <strong>{{.name}}</strong> ya está cerrada. Entregamos tu preventa el <strong>{{.deliversDisplay}}</strong>{{if .pickupWindow}} (recogida {{.pickupWindow}}){{end}}.</p>
{{button .emailUrl "Ver tu preventa"}}
<p><small>Si aceptaste los mensajes de texto, te escribiremos cuando salga a reparto.</small></p>',
 'Hola {{.firstName}}, {{.name}} ya está cerrada. Entregamos tu preventa el {{.deliversDisplay}}{{if .pickupWindow}} (recogida {{.pickupWindow}}){{end}}. Si aceptaste los mensajes de texto, te escribiremos cuando salga a reparto. {{.emailUrl}}'),
('preorder.confirmed', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.confirmed' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 'Ya estás dentro: {{.name}}',
 '{{define "preheader"}}Preventa {{.preorderNumber}}, entrega el {{.deliversDisplay}}. Cobrado: {{.total}}.{{end}}
{{badge "success" "Ya estás dentro"}}
<h1>Ya estás dentro: {{.name}}</h1>
<p>Hola {{.firstName}}, gracias por tu preventa. La entregamos junto con las de la comunidad el <strong>{{.deliversDisplay}}</strong>.</p>
{{facts "Número de preventa" .preorderNumber "Entrega" .deliversDisplay .fulfilmentText (or .doorFee .pickupFee) "Cobrado" .total}}
{{button .emailUrl "Ver tu preventa"}}
{{template "packages" (index . "packages")}}
{{template "totals" (index . "totals")}}
{{template "address" (index . "shipTo")}}
{{callout "info" (printf "%sLos artículos de la preventa se pueden cambiar o cancelar con reembolso total hasta el %s." (or .nowNote "") .cancelUntil)}}
{{with index . "helpUrl"}}<p><small>¿Necesitas ayuda con este pedido? Visita el <a href="{{.}}">Centro de ayuda</a> o responde a este correo.</small></p>{{end}}',
 'Hola {{.firstName}}, ya estás dentro: {{.name}}.
Preventa n.º: {{.preorderNumber}}
Entrega: {{.deliversDisplay}}
{{.fulfilmentText}}: {{if .doorFee}}{{.doorFee}}{{else}}{{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}{{end}}
Cobrado: {{.total}}
{{if .nowNote}}{{.nowNote}} {{end}}Los artículos de la preventa se pueden cambiar o cancelar con reembolso total hasta el {{.cancelUntil}}. Si aceptaste los mensajes de texto, te escribiremos el día de la entrega.
{{.emailUrl}}'),
('preorder.delivery_missed', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.delivery_missed' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 'No pudimos entregarte: {{.name}}',
 '{{define "preheader"}}Lo sentimos. Elige una nueva fecha o un reembolso total.{{end}}
{{badge "danger" "Entrega fallida"}}
<h1>Lo sentimos, no pudimos entregar tu pedido</h1>
<p>Hola {{.firstName}}, no pudimos entregar <strong>{{.name}}</strong> (pedido {{.orderNumber}}) el {{.missedDisplay}}. Elige lo que prefieras:</p>
{{if .newDateUrl}}{{button .newDateUrl (printf "Entregar el %s" .newDateDisplay)}}{{end}}
{{if .refundUrl}}{{secondaryButton .refundUrl (printf "Recibir un reembolso total de %s" .total)}}{{end}}
{{template "packages" (index . "packages")}}
{{with index . "helpUrl"}}<p><small>¿Necesitas ayuda con este pedido? Visita el <a href="{{.}}">Centro de ayuda</a> o responde a este correo.</small></p>{{end}}',
 'Hola {{.firstName}}, lo sentimos: no pudimos entregar {{.name}} (pedido {{.orderNumber}}) el {{.missedDisplay}}. Elige lo que prefieras:
{{if .newDateUrl}}Entregar el {{.newDateDisplay}}: {{.newDateUrl}}
{{end}}{{if .refundUrl}}Recibir un reembolso total de {{.total}}: {{.refundUrl}}
{{end}}Tus pedidos: {{.orderUrl}}'),
('preorder.last_call', 'es', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.last_call' AND t.locale = 'es' AND t.channel = 'push'), 'reminder',
 'Última llamada: {{.name}}',
 '',
 'La preventa cierra el {{.closesDisplay}}. Entrega el {{.deliversDisplay}}.'),
('preorder.last_call', 'es', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.last_call' AND t.locale = 'es' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: ultimo aviso! {{.name}} cierra {{.closesDisplay}}. {{.smsUrl}} STOP para salir'),
('preorder.late_order.payment_link', 'es', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.late_order.payment_link' AND t.locale = 'es' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: paga {{.total}} por tu preventa: {{.paymentUrl}} Responde STOP para salir'),
('preorder.out_for_delivery', 'es', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.out_for_delivery' AND t.locale = 'es' AND t.channel = 'push'), 'transactional',
 'Tu preventa sale a reparto',
 '',
 '{{.name}} llega hoy{{if .deliveryWindow}}, {{.deliveryWindow}}{{end}}.'),
('preorder.out_for_delivery', 'es', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.out_for_delivery' AND t.locale = 'es' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: {{.name}} sale a reparto hoy{{if .deliveryWindow}}, {{.deliveryWindow}}{{end}}. {{.smsUrl}} Responde STOP para salir'),
('preorder.reminder', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.reminder' AND t.locale = 'es' AND t.channel = 'email'), 'reminder',
 'Quedan 48 horas: {{.name}} cierra el {{.closesDisplay}}',
 '{{define "preheader"}}Pide antes del {{.closesDisplay}} para recibirlo el {{.deliversDisplay}}.{{end}}
{{badge "warning" "Quedan 48 horas"}}
<h1>Quedan 48 horas para reservar</h1>
<p>Hola {{.firstName}}, <strong>{{.name}}</strong> cierra el <strong>{{.closesDisplay}}</strong>. Todo lo pedido hasta entonces llega el <strong>{{.deliversDisplay}}</strong>.</p>
{{facts "Pide antes del" .closesDisplay "Entrega" .deliversDisplay "Entrega a domicilio" .doorFee "Recogida" .pickupFee "Horario de recogida" .pickupWindow}}
{{button .emailUrl "Terminar tu preventa"}}',
 'Hola {{.firstName}}, {{.name}} cierra el {{.closesDisplay}}. Todo lo pedido hasta entonces llega el {{.deliversDisplay}}{{if .doorFee}} con entrega a domicilio por {{.doorFee}}{{end}}{{if .pickupFee}}{{if .doorFee}} o{{else}} con{{end}} recogida ({{.pickupFee}}){{end}}.
Terminar tu preventa: {{.emailUrl}}{{if .unsubscribeUrl}}

Cancelar la suscripción: {{.unsubscribeUrl}}{{end}}'),
('preorder.reminder', 'es', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.reminder' AND t.locale = 'es' AND t.channel = 'push'), 'reminder',
 'Quedan 48 horas para reservar',
 '',
 '{{.name}} cierra el {{.closesDisplay}}. Entrega el {{.deliversDisplay}}.'),
('preorder.waitlist.opened', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.waitlist.opened' AND t.locale = 'es' AND t.channel = 'email'), 'reminder',
 'La preventa está abierta en {{.where}}',
 '{{define "preheader"}}{{.name}} está abierta hasta el {{.closesDisplay}}.{{end}}
{{badge "warning" "Lo pediste, ya estamos aquí"}}
<h1>Entregamos en {{.where}}</h1>
<p>Hola {{.firstName}}, nos pediste que te avisáramos cuando abriera una preventa en {{.where}}. <strong>{{.name}}</strong> está abierta ahora.</p>
{{facts "Pide antes del" .closesDisplay "Entrega" .deliversDisplay "Entrega a domicilio" .doorFee "Recogida" .pickupFee "Horario de recogida" .pickupWindow}}
{{button .emailUrl "Ver qué hay en la preventa"}}',
 'Hola {{.firstName}}, nos pediste que te avisáramos cuando abriera una preventa en {{.where}}. {{.name}} está abierta hasta el {{.closesDisplay}}, con entrega el {{.deliversDisplay}}.{{if .doorFee}} La entrega a domicilio cuesta {{.doorFee}} de tarifa fija.{{end}}{{if .pickupFee}} La recogida cuesta {{.pickupFee}}{{if .pickupWindow}}, {{.pickupWindow}}{{end}}.{{end}}
Ver qué hay en la preventa: {{.emailUrl}}{{if .unsubscribeUrl}}

Salir de la lista de espera: {{.unsubscribeUrl}}{{end}}'),
('run.announced', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.announced' AND t.locale = 'es' AND t.channel = 'email'), 'reminder',
 'Una ruta de entrega a {{.town}} el {{.deliveryDay}}',
 '{{define "preheader"}}Vamos a {{.town}} el {{.deliveryDay}}. Los pedidos abren pronto.{{end}}
{{badge "info" "Ruta de entrega anunciada"}}
<h1>Vamos a {{.town}}</h1>
<p>Hola {{.firstName}}, una ruta de entrega de AfroTransact a <strong>{{.town}}</strong> está prevista para el <strong>{{.deliveryDay}}</strong>. Te avisaremos en cuanto abran los pedidos.</p>
{{facts "Día de entrega" .deliveryDay "Horario de entrega" .windowDisplay "Pide antes del" .cutoffDisplay "Costo de entrega" .feeDisplay "Cómo" .dropText}}
{{button .emailUrl "Ver la ruta"}}
{{if .whatsappInviteUrl}}<p><small>Únete a la comunidad de WhatsApp de {{.town}} para recibir noticias de tus vecinos y de nuestros conductores: <a href="{{.whatsappInviteUrl}}">unirme</a>.</small></p>{{end}}',
 'Hola {{.firstName}}, una ruta de entrega de AfroTransact a {{.town}} está prevista para el {{.deliveryDay}} ({{.windowDisplay}}). Pide antes del {{.cutoffDisplay}}; la entrega cuesta {{.feeDisplay}}. {{.dropText}}.
Te avisaremos en cuanto abran los pedidos.
Ver la ruta: {{.emailUrl}}{{if .unsubscribeUrl}}

Cancelar las alertas de rutas a {{.town}}: {{.unsubscribeUrl}}{{end}}'),
('run.announced', 'es', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.announced' AND t.locale = 'es' AND t.channel = 'push'), 'reminder',
 'Ruta de entrega a {{.town}}',
 '',
 'Vamos a {{.town}} el {{.deliveryDay}}. Los pedidos abren pronto.'),
('run.cancelled', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 'La ruta de entrega a {{.town}} del {{.deliveryDay}} se canceló',
 '{{define "preheader"}}Te reembolsamos el total, incluido el costo de entrega.{{end}}
{{badge "danger" "Ruta cancelada"}}
<h1>Cancelamos la ruta a {{.town}}</h1>
<p>Hola {{.firstName}}, lo sentimos: la ruta de entrega a {{.town}} prevista para el <strong>{{.deliveryDay}}</strong> no se realizará. {{.reasonText}}</p>
{{callout "info" .refundNote}}
{{button .locationUrl "Ver la próxima ruta"}}',
 'Hola {{.firstName}}, lo sentimos: la ruta de entrega a {{.town}} prevista para el {{.deliveryDay}} no se realizará. {{.reasonText}}
{{.refundNote}}
Ver la próxima ruta: {{.locationUrl}}'),
('run.cancelled', 'es', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'es' AND t.channel = 'push'), 'transactional',
 'La ruta a {{.town}} se canceló',
 '',
 'Te reembolsamos el total, incluido el costo de entrega.'),
('run.cancelled', 'es', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'es' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: cancelamos la ruta a {{.town}} del {{.deliveryDay}}. Tu pedido se reembolsa por completo, costo de entrega incluido. Responde STOP para salir'),
('run.confirmed', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.confirmed' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 'La ruta a {{.town}} va: entrega el {{.deliveryDay}}',
 '{{define "preheader"}}Tu pedido está confirmado para el {{.deliveryDay}}, {{.windowText}}.{{end}}
{{badge "success" "Ruta confirmada"}}
<h1>La ruta a {{.town}} va</h1>
<p>Hola {{.firstName}}, llegaron suficientes pedidos, así que la ruta a {{.town}} sigue adelante. Tu pedido está confirmado para el <strong>{{.deliveryDay}}</strong>.</p>
{{facts "Día de entrega" .deliveryDay "Horario de entrega" .windowDisplay "Cómo" .dropText "Código de entrega" .handoverCode}}
{{button .emailUrl "Ver la ruta"}}',
 'Hola {{.firstName}}, la ruta a {{.town}} sigue adelante. Tu pedido está confirmado para el {{.deliveryDay}} ({{.windowDisplay}}). {{.dropText}}.{{if .handoverCode}} Código de entrega: {{.handoverCode}}.{{end}}
Ver la ruta: {{.emailUrl}}'),
('run.confirmed', 'es', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.confirmed' AND t.locale = 'es' AND t.channel = 'push'), 'transactional',
 'La ruta a {{.town}} va',
 '',
 'Tu pedido está confirmado para el {{.deliveryDay}}, {{.windowText}}.'),
('run.cutoff_reminder', 'es', 'chat', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'es' AND t.channel = 'chat'), 'reminder',
 'afrotransact_run_cutoff_reminder',
 '',
 '{{.town}}
{{.deliveryDay}}
{{.cutoffText}}
{{.feeDisplay}}
{{.whatsappUrl}}'),
('run.cutoff_reminder', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'es' AND t.channel = 'email'), 'reminder',
 'Última oportunidad para la ruta a {{.town}}: pide antes del {{.cutoffText}}',
 '{{define "preheader"}}La ruta a {{.town}} cierra el {{.cutoffText}}. Entregamos el {{.deliveryDay}}.{{end}}
{{badge "warning" "Cierra pronto"}}
<h1>La ruta a {{.town}} cierra pronto</h1>
<p>Hola {{.firstName}}, los pedidos para la ruta de entrega a {{.town}} cierran el <strong>{{.cutoffDisplay}}</strong>. Entregamos el <strong>{{.deliveryDay}}</strong>.</p>
{{facts "Pide antes del" .cutoffDisplay "Día de entrega" .deliveryDay "Horario de entrega" .windowDisplay "Costo de entrega" .feeDisplay "Cómo" .dropText "Pedidos hasta ahora" .minimumText}}
{{button .emailUrl "Ver la ruta"}}',
 'Hola {{.firstName}}, los pedidos para la ruta de entrega a {{.town}} cierran el {{.cutoffDisplay}}. Entregamos el {{.deliveryDay}} ({{.windowDisplay}}); la entrega cuesta {{.feeDisplay}}.
Ver la ruta: {{.emailUrl}}{{if .unsubscribeUrl}}

Cancelar las alertas de rutas a {{.town}}: {{.unsubscribeUrl}}{{end}}'),
('run.cutoff_reminder', 'es', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'es' AND t.channel = 'push'), 'reminder',
 'Última oportunidad para la ruta a {{.town}}',
 '',
 'Pide antes del {{.cutoffText}}. Entrega el {{.deliveryDay}}.'),
('run.cutoff_reminder', 'es', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'es' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: ultima oportunidad, ruta a {{.town}}. Pide antes de {{.cutoffText}}. Entrega {{.deliveryDay}}. {{.smsUrl}} Responde STOP para salir'),
('run.opened', 'es', 'chat', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'es' AND t.channel = 'chat'), 'reminder',
 'afrotransact_run_opened',
 '',
 '{{.town}}
{{.deliveryDay}}
{{.cutoffText}}
{{.feeDisplay}}
{{.whatsappUrl}}'),
('run.opened', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'es' AND t.channel = 'email'), 'reminder',
 'La ruta de entrega a {{.town}} está abierta: pide antes del {{.cutoffText}}',
 '{{define "preheader"}}Pide antes del {{.cutoffText}}. Entregamos el {{.deliveryDay}}, {{.windowText}}.{{end}}
{{badge "warning" "Ruta abierta"}}
<h1>La ruta de entrega a {{.town}} está abierta</h1>
<p>Hola {{.firstName}}, llevamos pedidos de AfroTransact a {{.town}} el <strong>{{.deliveryDay}}</strong>. Pide antes del cierre y el tuyo va en el mismo viaje.</p>
{{facts "Día de entrega" .deliveryDay "Horario de entrega" .windowDisplay "Pide antes del" .cutoffDisplay "Costo de entrega" .feeDisplay "Cómo" .dropText "Pedidos hasta ahora" .minimumText}}
{{button .emailUrl "Ver la ruta"}}
{{if .whatsappInviteUrl}}<p><small>Únete a la comunidad de WhatsApp de {{.town}} para recibir noticias de tus vecinos y de nuestros conductores: <a href="{{.whatsappInviteUrl}}">unirme</a>.</small></p>{{end}}',
 'Hola {{.firstName}}, la ruta de entrega a {{.town}} está abierta. Entregamos el {{.deliveryDay}} ({{.windowDisplay}}). Pide antes del {{.cutoffDisplay}}; la entrega cuesta {{.feeDisplay}}. {{.dropText}}.
Ver la ruta: {{.emailUrl}}{{if .unsubscribeUrl}}

Cancelar las alertas de rutas a {{.town}}: {{.unsubscribeUrl}}{{end}}'),
('run.opened', 'es', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'es' AND t.channel = 'push'), 'reminder',
 'La ruta de entrega a {{.town}} está abierta',
 '',
 'Pide antes del {{.cutoffText}}. Entrega el {{.deliveryDay}}.{{if .feeDisplay}} Entrega {{.feeDisplay}}.{{end}}'),
('run.opened', 'es', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'es' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: ruta a {{.town}} abierta. Entrega {{.deliveryDay}}. Pide antes de {{.cutoffText}}. {{.smsUrl}} Responde STOP para salir'),
('run.out_for_delivery', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 'Tu entrega en {{.town}} va en camino hoy',
 '{{define "preheader"}}Salimos hacia {{.town}}. Horario de entrega {{.windowText}}.{{end}}
{{badge "success" "En camino"}}
<h1>Vamos en camino a {{.town}}</h1>
<p>Hola {{.firstName}}, la ruta de entrega a {{.town}} ya salió. Tu pedido llega hoy, {{.windowDisplay}}.</p>
{{facts "Horario de entrega" .windowDisplay "Cómo" .dropText "Código de entrega" .handoverCode}}
{{if .handoverCode}}<p>Muestra este código al recoger tu pedido: <strong>{{.handoverCode}}</strong>.</p>{{end}}
{{button .emailUrl "Seguir la ruta"}}',
 'Hola {{.firstName}}, la ruta de entrega a {{.town}} ya salió. Tu pedido llega hoy, {{.windowDisplay}}. {{.dropText}}.{{if .handoverCode}} Código de entrega: {{.handoverCode}}.{{end}}
Seguir la ruta: {{.emailUrl}}'),
('run.out_for_delivery', 'es', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'es' AND t.channel = 'push'), 'transactional',
 'Tu pedido a {{.town}} va en camino',
 '',
 'Llega hoy, {{.windowText}}.{{if .handoverCode}} Código de entrega {{.handoverCode}}.{{end}}'),
('run.out_for_delivery', 'es', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'es' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: tu pedido a {{.town}} sale a reparto hoy, {{.windowText}}.{{if .handoverCode}} Codigo de entrega {{.handoverCode}}.{{end}} Responde STOP para salir'),
('run.subscription.confirm', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.subscription.confirm' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 'Confirma tus alertas de rutas a {{.town}}',
 '{{define "preheader"}}Un clic y te avisaremos cuando abra una ruta a {{.town}}.{{end}}
<h1>Un último paso</h1>
<p>Confirma tu correo para enterarte primero cuando AfroTransact abra una ruta de entrega a <strong>{{.town}}</strong>: cuando abran los pedidos, cuando estén por cerrar y cuando vayamos en camino. No enviaremos nada hasta que confirmes.</p>
{{button .confirmUrl "Confirmar mis alertas"}}
<p><small>¿No lo pediste? Ignora este correo y no se enviará nada.</small></p>',
 'Confirma tu correo para enterarte primero cuando AfroTransact abra una ruta de entrega a {{.town}}: cuando abran los pedidos, cuando estén por cerrar y cuando vayamos en camino. No enviaremos nada hasta que confirmes.

Confirmar mis alertas: {{.confirmUrl}}

¿No lo pediste? Ignora este correo y no se enviará nada.'),
('run.subscription.welcome', 'es', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.subscription.welcome' AND t.locale = 'es' AND t.channel = 'email'), 'transactional',
 'Serás el primero en saber de las rutas a {{.town}}',
 '{{define "preheader"}}Estás suscrito a las alertas de rutas a {{.town}} por {{.channelList}}.{{end}}
{{badge "success" "Suscrito"}}
<h1>Estás en la lista de {{.town}}</h1>
<p>Hola {{.firstName}}, te avisaremos por {{.channelList}} cuando abra una ruta de entrega a <strong>{{.town}}</strong>, cuando esté por cerrar y cuando vayamos en camino.</p>
{{button .locationUrl "Ver próximas rutas"}}
{{if .hasWhatsappCommunity}}<p>Tus vecinos y nuestros conductores comparten novedades en la comunidad de WhatsApp de {{.town}}: <a href="{{.whatsappInviteUrl}}">unirme a la comunidad</a>.</p>{{end}}
<p><small>Puedes cambiar esto o darte de baja cuando quieras desde tu cuenta, o con el enlace de baja que incluye cada mensaje.</small></p>',
 'Hola {{.firstName}}, te avisaremos por {{.channelList}} cuando abra una ruta de entrega a {{.town}}, cuando esté por cerrar y cuando vayamos en camino.
Ver próximas rutas: {{.locationUrl}}{{if .hasWhatsappCommunity}}
Unirme a la comunidad de WhatsApp de {{.town}}: {{.whatsappInviteUrl}}{{end}}
Darte de baja cuando quieras: {{.unsubscribeUrl}}'),
('preorder.announce', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'ar' AND t.channel = 'email'), 'reminder',
 '{{.name}} مفتوح: اطلب قبل {{.closesDisplay}}',
 '{{define "preheader"}}اطلب قبل {{.closesDisplay}}، والتوصيل في {{.deliversDisplay}}.{{end}}
<div dir="rtl">
{{badge "warning" "الطلب المسبق مفتوح"}}
<h1>{{if .headline}}{{.headline}}{{else}}الطلب المسبق مفتوح في {{.where}}{{end}}</h1>
<p>مرحبًا {{.firstName}}، <strong>{{.name}}</strong> مفتوح الآن. اطلب مع جيرانك ونوصّل كل شيء في يوم واحد.</p>
{{facts "اطلب قبل" .closesDisplay "التوصيل" .deliversDisplay "التوصيل إلى الباب" .doorFee "الاستلام" .pickupFee "موعد الاستلام" .pickupWindow}}
{{button .emailUrl "اطلب مسبقًا الآن"}}
<p><small>يتم الدفع عند إتمام الطلب، ويمكنك تعديل عناصر الطلب المسبق أو إلغاؤها مع استرداد كامل المبلغ حتى إغلاق الطلب المسبق.</small></p>
</div>',
 'مرحبًا {{.firstName}}، {{.name}} مفتوح الآن. اطلب قبل {{.closesDisplay}} ونوصّل في {{.deliversDisplay}}.
{{if .doorFee}}التوصيل إلى الباب: {{.doorFee}} رسم ثابت.
{{end}}{{if .pickupFee}}الاستلام: {{.pickupFee}}{{if .pickupWindow}}، {{.pickupWindow}}{{end}}.
{{end}}يتم الدفع عند إتمام الطلب، ويمكنك تعديل عناصر الطلب المسبق أو إلغاؤها مع استرداد كامل المبلغ حتى إغلاق الطلب المسبق.
اطلب مسبقًا الآن: {{.emailUrl}}{{if .unsubscribeUrl}}

إلغاء الاشتراك في تنبيهات الطلب المسبق في {{.where}}: {{.unsubscribeUrl}}{{end}}'),
('preorder.announce', 'ar', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'ar' AND t.channel = 'push'), 'reminder',
 'الطلب المسبق مفتوح في {{.where}}',
 '',
 'اطلب قبل {{.closesDisplay}}، والتوصيل في {{.deliversDisplay}}.{{if .doorFee}} التوصيل إلى الباب {{.doorFee}}.{{end}}'),
('preorder.announce', 'ar', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.announce' AND t.locale = 'ar' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 '{{.smsCopy}}'),
('preorder.closed', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.closed' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 '{{.name}} أُغلق: التوصيل في {{.deliversDisplay}}',
 '{{define "preheader"}}طلبك المسبق مؤكد للتوصيل في {{.deliversDisplay}}.{{end}}
<div dir="rtl">
{{badge "success" "تم التأكيد"}}
<h1>طلبك المسبق مؤكد</h1>
<p>مرحبًا {{.firstName}}، تم إغلاق <strong>{{.name}}</strong>. سنوصّل طلبك المسبق في <strong>{{.deliversDisplay}}</strong>{{if .pickupWindow}} (الاستلام {{.pickupWindow}}){{end}}.</p>
{{button .emailUrl "عرض طلبك المسبق"}}
<p><small>إذا وافقت على الرسائل النصية، سنراسلك عندما يخرج طلبك للتوصيل.</small></p>
</div>',
 'مرحبًا {{.firstName}}، تم إغلاق {{.name}}. سنوصّل طلبك المسبق في {{.deliversDisplay}}{{if .pickupWindow}} (الاستلام {{.pickupWindow}}){{end}}. إذا وافقت على الرسائل النصية، سنراسلك عندما يخرج طلبك للتوصيل. {{.emailUrl}}'),
('preorder.confirmed', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.confirmed' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 'تم تسجيلك: {{.name}}',
 '{{define "preheader"}}الطلب المسبق {{.preorderNumber}}، التوصيل في {{.deliversDisplay}}. المبلغ المحصّل: {{.total}}.{{end}}
<div dir="rtl">
{{badge "success" "تم تسجيلك"}}
<h1>تم تسجيلك: {{.name}}</h1>
<p>مرحبًا {{.firstName}}، شكرًا على طلبك المسبق. سنوصّله مع طلبات المجتمع في <strong>{{.deliversDisplay}}</strong>.</p>
{{facts "رقم الطلب المسبق" .preorderNumber "التوصيل" .deliversDisplay .fulfilmentText (or .doorFee .pickupFee) "المبلغ المحصّل" .total}}
{{button .emailUrl "عرض طلبك المسبق"}}
{{template "packages" (index . "packages")}}
{{template "totals" (index . "totals")}}
{{template "address" (index . "shipTo")}}
{{callout "info" (printf "%sيمكن تعديل عناصر الطلب المسبق أو إلغاؤها مع استرداد كامل المبلغ حتى %s." (or .nowNote "") .cancelUntil)}}
{{with index . "helpUrl"}}<p><small>هل تحتاج إلى مساعدة بخصوص هذا الطلب؟ زر <a href="{{.}}">مركز المساعدة</a> أو رد على هذه الرسالة.</small></p>{{end}}
</div>',
 'مرحبًا {{.firstName}}، تم تسجيلك: {{.name}}.
رقم الطلب المسبق: {{.preorderNumber}}
التوصيل: {{.deliversDisplay}}
{{.fulfilmentText}}: {{if .doorFee}}{{.doorFee}}{{else}}{{.pickupFee}}{{if .pickupWindow}}، {{.pickupWindow}}{{end}}{{end}}
المبلغ المحصّل: {{.total}}
{{if .nowNote}}{{.nowNote}} {{end}}يمكن تعديل عناصر الطلب المسبق أو إلغاؤها مع استرداد كامل المبلغ حتى {{.cancelUntil}}. إذا وافقت على الرسائل النصية، سنراسلك يوم التوصيل.
{{.emailUrl}}'),
('preorder.delivery_missed', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.delivery_missed' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 'فاتنا توصيل طلبك: {{.name}}',
 '{{define "preheader"}}نعتذر. اختر موعدًا جديدًا أو استردادًا كاملًا للمبلغ.{{end}}
<div dir="rtl">
{{badge "danger" "فاتنا التوصيل"}}
<h1>نعتذر، فاتنا توصيل طلبك</h1>
<p>مرحبًا {{.firstName}}، لم نتمكن من توصيل <strong>{{.name}}</strong> (الطلب {{.orderNumber}}) في {{.missedDisplay}}. اختر ما يناسبك:</p>
{{if .newDateUrl}}{{button .newDateUrl (printf "وصّله في %s" .newDateDisplay)}}{{end}}
{{if .refundUrl}}{{secondaryButton .refundUrl (printf "استرداد كامل المبلغ %s" .total)}}{{end}}
{{template "packages" (index . "packages")}}
{{with index . "helpUrl"}}<p><small>هل تحتاج إلى مساعدة بخصوص هذا الطلب؟ زر <a href="{{.}}">مركز المساعدة</a> أو رد على هذه الرسالة.</small></p>{{end}}
</div>',
 'مرحبًا {{.firstName}}، نعتذر: لم نتمكن من توصيل {{.name}} (الطلب {{.orderNumber}}) في {{.missedDisplay}}. اختر ما يناسبك:
{{if .newDateUrl}}وصّله في {{.newDateDisplay}}: {{.newDateUrl}}
{{end}}{{if .refundUrl}}استرداد كامل المبلغ {{.total}}: {{.refundUrl}}
{{end}}طلباتك: {{.orderUrl}}'),
('preorder.last_call', 'ar', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.last_call' AND t.locale = 'ar' AND t.channel = 'push'), 'reminder',
 'النداء الأخير: {{.name}}',
 '',
 'يُغلق الطلب المسبق في {{.closesDisplay}}. التوصيل في {{.deliversDisplay}}.'),
('preorder.last_call', 'ar', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.last_call' AND t.locale = 'ar' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: النداء الأخير! يُغلق {{.name}} في {{.closesDisplay}}. {{.smsUrl}} للإلغاء أرسل STOP'),
('preorder.late_order.payment_link', 'ar', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.late_order.payment_link' AND t.locale = 'ar' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: ادفع {{.total}} لطلبك المسبق: {{.paymentUrl}} للإلغاء أرسل STOP'),
('preorder.out_for_delivery', 'ar', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.out_for_delivery' AND t.locale = 'ar' AND t.channel = 'push'), 'transactional',
 'طلبك المسبق خرج للتوصيل',
 '',
 'يصل {{.name}} اليوم{{if .deliveryWindow}}، {{.deliveryWindow}}{{end}}.'),
('preorder.out_for_delivery', 'ar', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.out_for_delivery' AND t.locale = 'ar' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: خرج {{.name}} للتوصيل اليوم{{if .deliveryWindow}}، {{.deliveryWindow}}{{end}}. {{.smsUrl}} للإلغاء أرسل STOP'),
('preorder.reminder', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.reminder' AND t.locale = 'ar' AND t.channel = 'email'), 'reminder',
 'بقيت 48 ساعة: يُغلق {{.name}} في {{.closesDisplay}}',
 '{{define "preheader"}}اطلب قبل {{.closesDisplay}} لتستلم في {{.deliversDisplay}}.{{end}}
<div dir="rtl">
{{badge "warning" "بقيت 48 ساعة"}}
<h1>بقيت 48 ساعة للطلب المسبق</h1>
<p>مرحبًا {{.firstName}}، يُغلق <strong>{{.name}}</strong> في <strong>{{.closesDisplay}}</strong>. كل ما تطلبه حتى ذلك الوقت يصلك في <strong>{{.deliversDisplay}}</strong>.</p>
{{facts "اطلب قبل" .closesDisplay "التوصيل" .deliversDisplay "التوصيل إلى الباب" .doorFee "الاستلام" .pickupFee "موعد الاستلام" .pickupWindow}}
{{button .emailUrl "أكمل طلبك المسبق"}}
</div>',
 'مرحبًا {{.firstName}}، يُغلق {{.name}} في {{.closesDisplay}}. كل ما تطلبه حتى ذلك الوقت يصلك في {{.deliversDisplay}}{{if .doorFee}} مع توصيل إلى الباب بـ {{.doorFee}}{{end}}{{if .pickupFee}}{{if .doorFee}} أو{{else}} مع{{end}} استلام ({{.pickupFee}}){{end}}.
أكمل طلبك المسبق: {{.emailUrl}}{{if .unsubscribeUrl}}

إلغاء الاشتراك: {{.unsubscribeUrl}}{{end}}'),
('preorder.reminder', 'ar', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.reminder' AND t.locale = 'ar' AND t.channel = 'push'), 'reminder',
 'بقيت 48 ساعة للطلب المسبق',
 '',
 'يُغلق {{.name}} في {{.closesDisplay}}. التوصيل في {{.deliversDisplay}}.'),
('preorder.waitlist.opened', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'preorder.waitlist.opened' AND t.locale = 'ar' AND t.channel = 'email'), 'reminder',
 'الطلب المسبق مفتوح في {{.where}}',
 '{{define "preheader"}}{{.name}} مفتوح حتى {{.closesDisplay}}.{{end}}
<div dir="rtl">
{{badge "warning" "طلبت منا ذلك وها نحن"}}
<h1>نوصّل إلى {{.where}}</h1>
<p>مرحبًا {{.firstName}}، طلبت منا إبلاغك عند فتح طلب مسبق في {{.where}}. <strong>{{.name}}</strong> مفتوح الآن.</p>
{{facts "اطلب قبل" .closesDisplay "التوصيل" .deliversDisplay "التوصيل إلى الباب" .doorFee "الاستلام" .pickupFee "موعد الاستلام" .pickupWindow}}
{{button .emailUrl "شاهد ما في الطلب المسبق"}}
</div>',
 'مرحبًا {{.firstName}}، طلبت منا إبلاغك عند فتح طلب مسبق في {{.where}}. {{.name}} مفتوح حتى {{.closesDisplay}}، والتوصيل في {{.deliversDisplay}}.{{if .doorFee}} التوصيل إلى الباب {{.doorFee}} رسم ثابت.{{end}}{{if .pickupFee}} الاستلام {{.pickupFee}}{{if .pickupWindow}}، {{.pickupWindow}}{{end}}.{{end}}
شاهد ما في الطلب المسبق: {{.emailUrl}}{{if .unsubscribeUrl}}

مغادرة قائمة الانتظار: {{.unsubscribeUrl}}{{end}}'),
('run.announced', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.announced' AND t.locale = 'ar' AND t.channel = 'email'), 'reminder',
 'جولة توصيل إلى {{.town}} في {{.deliveryDay}}',
 '{{define "preheader"}}نحن في طريقنا إلى {{.town}} في {{.deliveryDay}}. يبدأ استقبال الطلبات قريبًا.{{end}}
<div dir="rtl">
{{badge "info" "تم الإعلان عن جولة توصيل"}}
<h1>نحن قادمون إلى {{.town}}</h1>
<p>مرحبًا {{.firstName}}، هناك جولة توصيل من أفروترانزاكت إلى <strong>{{.town}}</strong> مقررة في <strong>{{.deliveryDay}}</strong>. سنبلغك فور فتح باب الطلبات.</p>
{{facts "يوم التوصيل" .deliveryDay "موعد التوصيل" .windowDisplay "اطلب قبل" .cutoffDisplay "رسوم التوصيل" .feeDisplay "الطريقة" .dropText}}
{{button .emailUrl "شاهد الجولة"}}
{{if .whatsappInviteUrl}}<p><small>انضم إلى مجتمع واتساب في {{.town}} لتصلك أخبار الجيران وسائقينا: <a href="{{.whatsappInviteUrl}}">انضم</a>.</small></p>{{end}}
</div>',
 'مرحبًا {{.firstName}}، هناك جولة توصيل من أفروترانزاكت إلى {{.town}} مقررة في {{.deliveryDay}} ({{.windowDisplay}}). اطلب قبل {{.cutoffDisplay}}؛ رسوم التوصيل {{.feeDisplay}}. {{.dropText}}.
سنبلغك فور فتح باب الطلبات.
شاهد الجولة: {{.emailUrl}}{{if .unsubscribeUrl}}

إلغاء الاشتراك في تنبيهات جولات {{.town}}: {{.unsubscribeUrl}}{{end}}'),
('run.announced', 'ar', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.announced' AND t.locale = 'ar' AND t.channel = 'push'), 'reminder',
 'جولة توصيل إلى {{.town}}',
 '',
 'نحن قادمون إلى {{.town}} في {{.deliveryDay}}. يبدأ استقبال الطلبات قريبًا.'),
('run.cancelled', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 'أُلغيت جولة التوصيل إلى {{.town}} في {{.deliveryDay}}',
 '{{define "preheader"}}يُرد لك المبلغ كاملًا، شاملًا رسوم التوصيل.{{end}}
<div dir="rtl">
{{badge "danger" "أُلغيت الجولة"}}
<h1>ألغينا جولة {{.town}}</h1>
<p>مرحبًا {{.firstName}}، نعتذر: جولة التوصيل إلى {{.town}} المقررة في <strong>{{.deliveryDay}}</strong> لن تتم. {{.reasonText}}</p>
{{callout "info" .refundNote}}
{{button .locationUrl "شاهد الجولة القادمة"}}
</div>',
 'مرحبًا {{.firstName}}، نعتذر: جولة التوصيل إلى {{.town}} المقررة في {{.deliveryDay}} لن تتم. {{.reasonText}}
{{.refundNote}}
شاهد الجولة القادمة: {{.locationUrl}}'),
('run.cancelled', 'ar', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'ar' AND t.channel = 'push'), 'transactional',
 'أُلغيت جولة {{.town}}',
 '',
 'يُرد لك المبلغ كاملًا، شاملًا رسوم التوصيل.'),
('run.cancelled', 'ar', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cancelled' AND t.locale = 'ar' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: ألغينا جولة {{.town}} المقررة في {{.deliveryDay}}. يُرد لك مبلغ طلبك كاملًا شاملًا رسوم التوصيل. للإلغاء أرسل STOP'),
('run.confirmed', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.confirmed' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 'جولة {{.town}} مؤكدة: التوصيل في {{.deliveryDay}}',
 '{{define "preheader"}}طلبك مؤكد في {{.deliveryDay}}، {{.windowText}}.{{end}}
<div dir="rtl">
{{badge "success" "تأكدت الجولة"}}
<h1>جولة {{.town}} ستتم</h1>
<p>مرحبًا {{.firstName}}، وصل عدد كافٍ من الطلبات، لذلك ستتم جولة {{.town}}. طلبك مؤكد في <strong>{{.deliveryDay}}</strong>.</p>
{{facts "يوم التوصيل" .deliveryDay "موعد التوصيل" .windowDisplay "الطريقة" .dropText "رمز التسليم" .handoverCode}}
{{button .emailUrl "شاهد الجولة"}}
</div>',
 'مرحبًا {{.firstName}}، ستتم جولة {{.town}}. طلبك مؤكد في {{.deliveryDay}} ({{.windowDisplay}}). {{.dropText}}.{{if .handoverCode}} رمز التسليم: {{.handoverCode}}.{{end}}
شاهد الجولة: {{.emailUrl}}'),
('run.confirmed', 'ar', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.confirmed' AND t.locale = 'ar' AND t.channel = 'push'), 'transactional',
 'جولة {{.town}} ستتم',
 '',
 'طلبك مؤكد في {{.deliveryDay}}، {{.windowText}}.'),
('run.cutoff_reminder', 'ar', 'chat', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'ar' AND t.channel = 'chat'), 'reminder',
 'afrotransact_run_cutoff_reminder',
 '',
 '{{.town}}
{{.deliveryDay}}
{{.cutoffText}}
{{.feeDisplay}}
{{.whatsappUrl}}'),
('run.cutoff_reminder', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'ar' AND t.channel = 'email'), 'reminder',
 'الفرصة الأخيرة لجولة {{.town}}: اطلب قبل {{.cutoffText}}',
 '{{define "preheader"}}تُغلق جولة {{.town}} في {{.cutoffText}}. نوصّل في {{.deliveryDay}}.{{end}}
<div dir="rtl">
{{badge "warning" "تُغلق قريبًا"}}
<h1>جولة {{.town}} تُغلق قريبًا</h1>
<p>مرحبًا {{.firstName}}، يُغلق باب الطلبات لجولة التوصيل إلى {{.town}} في <strong>{{.cutoffDisplay}}</strong>. نوصّل في <strong>{{.deliveryDay}}</strong>.</p>
{{facts "اطلب قبل" .cutoffDisplay "يوم التوصيل" .deliveryDay "موعد التوصيل" .windowDisplay "رسوم التوصيل" .feeDisplay "الطريقة" .dropText "الطلبات حتى الآن" .minimumText}}
{{button .emailUrl "شاهد الجولة"}}
</div>',
 'مرحبًا {{.firstName}}، يُغلق باب الطلبات لجولة التوصيل إلى {{.town}} في {{.cutoffDisplay}}. نوصّل في {{.deliveryDay}} ({{.windowDisplay}})؛ رسوم التوصيل {{.feeDisplay}}.
شاهد الجولة: {{.emailUrl}}{{if .unsubscribeUrl}}

إلغاء الاشتراك في تنبيهات جولات {{.town}}: {{.unsubscribeUrl}}{{end}}'),
('run.cutoff_reminder', 'ar', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'ar' AND t.channel = 'push'), 'reminder',
 'الفرصة الأخيرة لجولة {{.town}}',
 '',
 'اطلب قبل {{.cutoffText}}. التوصيل في {{.deliveryDay}}.'),
('run.cutoff_reminder', 'ar', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.cutoff_reminder' AND t.locale = 'ar' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: الفرصة الأخيرة لجولة {{.town}}. اطلب قبل {{.cutoffText}}. التوصيل {{.deliveryDay}}. {{.smsUrl}} للإلغاء أرسل STOP'),
('run.opened', 'ar', 'chat', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'ar' AND t.channel = 'chat'), 'reminder',
 'afrotransact_run_opened',
 '',
 '{{.town}}
{{.deliveryDay}}
{{.cutoffText}}
{{.feeDisplay}}
{{.whatsappUrl}}'),
('run.opened', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'ar' AND t.channel = 'email'), 'reminder',
 'جولة التوصيل إلى {{.town}} مفتوحة: اطلب قبل {{.cutoffText}}',
 '{{define "preheader"}}اطلب قبل {{.cutoffText}}. نوصّل في {{.deliveryDay}}، {{.windowText}}.{{end}}
<div dir="rtl">
{{badge "warning" "الجولة مفتوحة"}}
<h1>جولة التوصيل إلى {{.town}} مفتوحة</h1>
<p>مرحبًا {{.firstName}}، نحمل طلبات أفروترانزاكت إلى {{.town}} في <strong>{{.deliveryDay}}</strong>. اطلب قبل الإغلاق ويسافر طلبك في الرحلة نفسها.</p>
{{facts "يوم التوصيل" .deliveryDay "موعد التوصيل" .windowDisplay "اطلب قبل" .cutoffDisplay "رسوم التوصيل" .feeDisplay "الطريقة" .dropText "الطلبات حتى الآن" .minimumText}}
{{button .emailUrl "شاهد الجولة"}}
{{if .whatsappInviteUrl}}<p><small>انضم إلى مجتمع واتساب في {{.town}} لتصلك أخبار الجيران وسائقينا: <a href="{{.whatsappInviteUrl}}">انضم</a>.</small></p>{{end}}
</div>',
 'مرحبًا {{.firstName}}، جولة التوصيل إلى {{.town}} مفتوحة. نوصّل في {{.deliveryDay}} ({{.windowDisplay}}). اطلب قبل {{.cutoffDisplay}}؛ رسوم التوصيل {{.feeDisplay}}. {{.dropText}}.
شاهد الجولة: {{.emailUrl}}{{if .unsubscribeUrl}}

إلغاء الاشتراك في تنبيهات جولات {{.town}}: {{.unsubscribeUrl}}{{end}}'),
('run.opened', 'ar', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'ar' AND t.channel = 'push'), 'reminder',
 'جولة التوصيل إلى {{.town}} مفتوحة',
 '',
 'اطلب قبل {{.cutoffText}}. التوصيل في {{.deliveryDay}}.{{if .feeDisplay}} رسوم التوصيل {{.feeDisplay}}.{{end}}'),
('run.opened', 'ar', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.opened' AND t.locale = 'ar' AND t.channel = 'sms'), 'reminder',
 '',
 '',
 'AfroTransact: جولة {{.town}} مفتوحة. التوصيل {{.deliveryDay}}. اطلب قبل {{.cutoffText}}. {{.smsUrl}} للإلغاء أرسل STOP'),
('run.out_for_delivery', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 'توصيلك في {{.town}} في الطريق اليوم',
 '{{define "preheader"}}انطلقنا إلى {{.town}}. موعد التوصيل {{.windowText}}.{{end}}
<div dir="rtl">
{{badge "success" "في الطريق"}}
<h1>نحن في طريقنا إلى {{.town}}</h1>
<p>مرحبًا {{.firstName}}، انطلقت جولة التوصيل إلى {{.town}}. يصل طلبك اليوم، {{.windowDisplay}}.</p>
{{facts "موعد التوصيل" .windowDisplay "الطريقة" .dropText "رمز التسليم" .handoverCode}}
{{if .handoverCode}}<p>أظهر هذا الرمز عند استلام طلبك: <strong>{{.handoverCode}}</strong>.</p>{{end}}
{{button .emailUrl "تابع الجولة"}}
</div>',
 'مرحبًا {{.firstName}}، انطلقت جولة التوصيل إلى {{.town}}. يصل طلبك اليوم، {{.windowDisplay}}. {{.dropText}}.{{if .handoverCode}} رمز التسليم: {{.handoverCode}}.{{end}}
تابع الجولة: {{.emailUrl}}'),
('run.out_for_delivery', 'ar', 'push', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'ar' AND t.channel = 'push'), 'transactional',
 'طلبك إلى {{.town}} في الطريق',
 '',
 'يصل اليوم، {{.windowText}}.{{if .handoverCode}} رمز التسليم {{.handoverCode}}.{{end}}'),
('run.out_for_delivery', 'ar', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.out_for_delivery' AND t.locale = 'ar' AND t.channel = 'sms'), 'transactional',
 '',
 '',
 'AfroTransact: طلبك إلى {{.town}} خرج للتوصيل اليوم، {{.windowText}}.{{if .handoverCode}} رمز التسليم {{.handoverCode}}.{{end}} للإلغاء أرسل STOP'),
('run.subscription.confirm', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.subscription.confirm' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 'أكّد تنبيهات جولات التوصيل إلى {{.town}}',
 '{{define "preheader"}}بنقرة واحدة نبلغك عند فتح جولة إلى {{.town}}.{{end}}
<div dir="rtl">
<h1>خطوة واحدة فقط</h1>
<p>أكّد بريدك الإلكتروني لتكون أول من يعلم عندما تفتح أفروترانزاكت جولة توصيل إلى <strong>{{.town}}</strong>: عند فتح الطلبات، وقبيل إغلاقها، وعندما نكون في الطريق. لن نرسل شيئًا قبل أن تؤكد.</p>
{{button .confirmUrl "تأكيد تنبيهاتي"}}
<p><small>لم تطلب ذلك؟ تجاهل هذه الرسالة ولن يُرسل أي شيء.</small></p>
</div>',
 'أكّد بريدك الإلكتروني لتكون أول من يعلم عندما تفتح أفروترانزاكت جولة توصيل إلى {{.town}}: عند فتح الطلبات، وقبيل إغلاقها، وعندما نكون في الطريق. لن نرسل شيئًا قبل أن تؤكد.

تأكيد تنبيهاتي: {{.confirmUrl}}

لم تطلب ذلك؟ تجاهل هذه الرسالة ولن يُرسل أي شيء.'),
('run.subscription.welcome', 'ar', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'run.subscription.welcome' AND t.locale = 'ar' AND t.channel = 'email'), 'transactional',
 'ستكون أول من يعلم بجولات التوصيل إلى {{.town}}',
 '{{define "preheader"}}أنت مشترك في تنبيهات جولات {{.town}} عبر {{.channelList}}.{{end}}
<div dir="rtl">
{{badge "success" "تم الاشتراك"}}
<h1>أنت في قائمة {{.town}}</h1>
<p>مرحبًا {{.firstName}}، سنبلغك عبر {{.channelList}} عندما تفتح جولة توصيل إلى <strong>{{.town}}</strong>، وقبيل إغلاقها، وعندما نكون في الطريق.</p>
{{button .locationUrl "شاهد الجولات القادمة"}}
{{if .hasWhatsappCommunity}}<p>يتبادل الجيران وسائقونا الأخبار في مجتمع واتساب في {{.town}}: <a href="{{.whatsappInviteUrl}}">انضم إلى المجتمع</a>.</p>{{end}}
<p><small>يمكنك تغيير ذلك أو إيقافه في أي وقت من حسابك، أو عبر رابط إلغاء الاشتراك في كل رسالة.</small></p>
</div>',
 'مرحبًا {{.firstName}}، سنبلغك عبر {{.channelList}} عندما تفتح جولة توصيل إلى {{.town}}، وقبيل إغلاقها، وعندما نكون في الطريق.
شاهد الجولات القادمة: {{.locationUrl}}{{if .hasWhatsappCommunity}}
انضم إلى مجتمع واتساب في {{.town}}: {{.whatsappInviteUrl}}{{end}}
إلغاء الاشتراك في أي وقت: {{.unsubscribeUrl}}');
