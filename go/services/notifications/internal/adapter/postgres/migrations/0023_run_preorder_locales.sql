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
Se désabonner à tout moment : {{.unsubscribeUrl}}');
