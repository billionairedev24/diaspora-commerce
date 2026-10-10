/* AfroTransact site config: runtime-editable social links, legal document metadata, contacts,
   locale + consent. Production: served by the Site Content API (GET /v1/site-config, ETag-cached,
   published from Backoffice → Site content & legal). Prototype: localStorage + cross-tab events. */
(function () {
  if (window.ATSite) return;
  const KEY = 'at.site.v1', LKEY = 'at.locale.v1', CKEY = 'at.consent.v1';
  const NETS = {
    tiktok: ['TikTok', 'ph ph-tiktok-logo', /^https:\/\/(www\.)?tiktok\.com\/@[\w.]+\/?$/i],
    instagram: ['Instagram', 'ph ph-instagram-logo', /^https:\/\/(www\.)?instagram\.com\/[\w.]+\/?$/i],
    facebook: ['Facebook', 'ph ph-facebook-logo', /^https:\/\/(www\.)?facebook\.com\/[\w.]+\/?$/i],
    x: ['X', 'ph ph-x-logo', /^https:\/\/(www\.)?(x|twitter)\.com\/\w+\/?$/i],
    youtube: ['YouTube', 'ph ph-youtube-logo', /^https:\/\/(www\.)?youtube\.com\/(@|c\/|channel\/)[\w.-]+\/?$/i],
    linkedin: ['LinkedIn', 'ph ph-linkedin-logo', /^https:\/\/(www\.)?linkedin\.com\/company\/[\w-]+\/?$/i],
    whatsapp: ['WhatsApp channel', 'ph ph-whatsapp-logo', /^https:\/\/(www\.)?whatsapp\.com\/channel\/\w+\/?$/i]
  };
  const SI = 'https://cdn.jsdelivr.net/npm/simple-icons@9.21.0/icons/';
  const BRAND = { tiktok: ['tiktok', '#000000'], instagram: ['instagram', '#E4405F'], facebook: ['facebook', '#0866FF'], x: ['x', '#000000'], youtube: ['youtube', '#FF0000'], linkedin: ['linkedin', '#0A66C2'], whatsapp: ['whatsapp', '#25D366'] };
  const DEF = {
    rev: 1,
    socials: [
      { net: 'tiktok', url: 'https://www.tiktok.com/@afrotransact', on: true },
      { net: 'instagram', url: 'https://www.instagram.com/afrotransact', on: true },
      { net: 'facebook', url: 'https://www.facebook.com/afrotransact', on: true },
      { net: 'x', url: 'https://x.com/afrotransact', on: true },
      { net: 'youtube', url: 'https://www.youtube.com/@afrotransact', on: true },
      { net: 'linkedin', url: 'https://www.linkedin.com/company/afrotransact', on: true },
      { net: 'whatsapp', url: 'https://www.whatsapp.com/channel/0029VaAfroTransact', on: false }
    ],
    contacts: { privacy: 'privacy@afrotransact.com', accessibility: 'accessibility@afrotransact.com', legal: 'legal@afrotransact.com', phone: '+1 (833) 555-0199', address: 'AfroTransact, Inc. · 1001 Texas Ave, Suite 1400 · Houston, TX 77002' },
    legal: {
      terms: { v: '3.2', eff: '2026-09-01', hist: [['3.1', '2026-03-15'], ['3.0', '2025-11-01']] },
      privacy: { v: '4.0', eff: '2026-09-01', hist: [['3.4', '2026-01-10']] },
      cookies: { v: '2.1', eff: '2026-09-01', hist: [['2.0', '2026-01-10']] },
      choices: { v: '1.3', eff: '2026-09-01', hist: [] },
      returns: { v: '2.0', eff: '2026-06-01', hist: [['1.4', '2025-12-01']] },
      sellers: { v: '2.4', eff: '2026-09-01', hist: [['2.3', '2026-04-01']] },
      prohibited: { v: '1.6', eff: '2026-07-15', hist: [] },
      sms: { v: '1.0', eff: '2026-10-10', hist: [] },
      accessibility: { v: '1.2', eff: '2026-08-20', hist: [] }
    },
    draftBanner: true
  };
  const LOCALES = [['en', 'English', 'ltr'], ['fr', 'Français', 'ltr'], ['es', 'Español', 'ltr'], ['ar', 'العربية', 'rtl']];

  const S = {
    en: { skip: 'Skip to main content', center: 'Legal & privacy', back: 'Back to shop', language: 'Language', docs: 'Legal documents', onPage: 'On this page', version: 'Version', effective: 'Effective', prev: 'Previous versions', print: 'Print', translation: '', draft: 'Draft for legal counsel review — not yet in force.', followUs: 'Follow AfroTransact', contact: 'Contact', rights: 'All rights reserved.',
      'd.terms': 'Conditions of Use', 'd.privacy': 'Privacy Notice', 'd.cookies': 'Cookie Notice', 'd.choices': 'Your Privacy Choices', 'd.returns': 'Returns & Refunds', 'd.sellers': 'Seller & Provider Terms', 'd.prohibited': 'Prohibited & Restricted Items', 'd.sms': 'SMS & Notification Terms', 'd.accessibility': 'Accessibility Statement', cookiePrefs: 'Cookie preferences',
      cbTitle: 'Your cookie choices', cbText: 'We use necessary cookies to run the store. With your permission we also use cookies to remember preferences, measure traffic and show relevant ads.', acceptAll: 'Accept all', rejectAll: 'Reject non-essential', manage: 'Manage preferences', save: 'Save preferences', saved: 'Preferences saved.', alwaysOn: 'Always on',
      'c.necessary': 'Strictly necessary', 'c.functional': 'Functional', 'c.analytics': 'Analytics', 'c.advertising': 'Advertising',
      'cd.necessary': 'Sign-in, cart, checkout, fraud prevention and security. These cannot be switched off.', 'cd.functional': 'Remember your language, delivery ZIP and recently viewed items.', 'cd.analytics': 'Aggregated, de-identified measurement of how pages perform.', 'cd.advertising': 'Show AfroTransact ads on other sites and limit how often you see them.',
      gpc: 'Your browser sent a Global Privacy Control signal. We have turned off advertising cookies and treat it as an opt-out of sale and sharing.', table: 'Cookies we use', colName: 'Name', colProvider: 'Provider', colPurpose: 'Purpose', colDuration: 'Duration', colCategory: 'Category',
      reqTitle: 'Make a privacy request', reqIntro: 'Residents of any US state may use this form. We verify your identity by email and respond within 45 days.', reqType: 'What would you like to do?', 'r.access': 'Get a copy of my data', 'r.delete': 'Delete my data', 'r.correct': 'Correct my data', 'r.optout': 'Opt out of sale, sharing and targeted ads', email: 'Email on your account', state: 'State of residence', details: 'Details (optional)', submit: 'Submit request', submitted: 'Request received. Reference {ref}. Check {email} to confirm it’s you.', errSummary: 'There are {n} problems with your request', errEmail: 'Enter the email address on your account', errType: 'Choose what you would like to do', errState: 'Choose your state', choose: 'Choose…',
      dns: 'Do not sell or share my personal information', dnsOn: 'Opted out — we won’t sell or share your data or use it for targeted ads.', dnsOff: 'Not opted out.' },
    fr: { skip: 'Aller au contenu principal', center: 'Mentions légales et confidentialité', back: 'Retour à la boutique', language: 'Langue', docs: 'Documents juridiques', onPage: 'Sur cette page', version: 'Version', effective: 'En vigueur le', prev: 'Versions précédentes', print: 'Imprimer', translation: 'La traduction française de ce document est en cours de relecture juridique. La version anglaise ci-dessous fait foi.', draft: 'Projet soumis à relecture juridique — pas encore en vigueur.', followUs: 'Suivre AfroTransact', contact: 'Contact', rights: 'Tous droits réservés.',
      'd.terms': 'Conditions d’utilisation', 'd.privacy': 'Avis de confidentialité', 'd.cookies': 'Politique relative aux cookies', 'd.choices': 'Vos choix de confidentialité', 'd.returns': 'Retours et remboursements', 'd.sellers': 'Conditions vendeurs et prestataires', 'd.prohibited': 'Articles interdits et restreints', 'd.sms': 'Conditions SMS et notifications', 'd.accessibility': 'Déclaration d’accessibilité', cookiePrefs: 'Préférences de cookies',
      cbTitle: 'Vos choix en matière de cookies', cbText: 'Nous utilisons des cookies nécessaires au fonctionnement de la boutique. Avec votre accord, nous en utilisons aussi pour mémoriser vos préférences, mesurer l’audience et afficher des publicités pertinentes.', acceptAll: 'Tout accepter', rejectAll: 'Refuser les non essentiels', manage: 'Gérer les préférences', save: 'Enregistrer', saved: 'Préférences enregistrées.', alwaysOn: 'Toujours actifs',
      'c.necessary': 'Strictement nécessaires', 'c.functional': 'Fonctionnels', 'c.analytics': 'Mesure d’audience', 'c.advertising': 'Publicité',
      'cd.necessary': 'Connexion, panier, paiement, prévention de la fraude et sécurité. Ils ne peuvent pas être désactivés.', 'cd.functional': 'Mémoriser votre langue, votre code postal de livraison et les articles consultés.', 'cd.analytics': 'Mesure agrégée et anonymisée des performances des pages.', 'cd.advertising': 'Afficher les publicités AfroTransact sur d’autres sites et limiter leur fréquence.',
      gpc: 'Votre navigateur a envoyé un signal Global Privacy Control. Nous avons désactivé les cookies publicitaires et le traitons comme une opposition à la vente et au partage.', table: 'Cookies utilisés', colName: 'Nom', colProvider: 'Fournisseur', colPurpose: 'Finalité', colDuration: 'Durée', colCategory: 'Catégorie',
      reqTitle: 'Faire une demande', reqIntro: 'Les résidents de tout État américain peuvent utiliser ce formulaire. Nous vérifions votre identité par e-mail et répondons sous 45 jours.', reqType: 'Que souhaitez-vous faire ?', 'r.access': 'Obtenir une copie de mes données', 'r.delete': 'Supprimer mes données', 'r.correct': 'Corriger mes données', 'r.optout': 'M’opposer à la vente, au partage et à la publicité ciblée', email: 'E-mail du compte', state: 'État de résidence', details: 'Précisions (facultatif)', submit: 'Envoyer la demande', submitted: 'Demande reçue. Référence {ref}. Consultez {email} pour confirmer votre identité.', errSummary: 'Votre demande comporte {n} erreur(s)', errEmail: 'Saisissez l’adresse e-mail de votre compte', errType: 'Choisissez une option', errState: 'Choisissez votre État', choose: 'Choisir…',
      dns: 'Ne pas vendre ni partager mes informations personnelles', dnsOn: 'Opposition enregistrée — nous ne vendons ni ne partageons vos données.', dnsOff: 'Aucune opposition.' },
    es: { skip: 'Ir al contenido principal', center: 'Legal y privacidad', back: 'Volver a la tienda', language: 'Idioma', docs: 'Documentos legales', onPage: 'En esta página', version: 'Versión', effective: 'Vigente desde', prev: 'Versiones anteriores', print: 'Imprimir', translation: 'La traducción al español de este documento está en revisión legal. La versión en inglés que aparece abajo es la que rige.', draft: 'Borrador para revisión legal — aún no está en vigor.', followUs: 'Sigue a AfroTransact', contact: 'Contacto', rights: 'Todos los derechos reservados.',
      'd.terms': 'Condiciones de uso', 'd.privacy': 'Aviso de privacidad', 'd.cookies': 'Aviso de cookies', 'd.choices': 'Tus opciones de privacidad', 'd.returns': 'Devoluciones y reembolsos', 'd.sellers': 'Términos para vendedores y proveedores', 'd.prohibited': 'Artículos prohibidos y restringidos', 'd.sms': 'Términos de SMS y notificaciones', 'd.accessibility': 'Declaración de accesibilidad', cookiePrefs: 'Preferencias de cookies',
      cbTitle: 'Tus opciones de cookies', cbText: 'Usamos cookies necesarias para que la tienda funcione. Con tu permiso, también usamos cookies para recordar preferencias, medir el tráfico y mostrar anuncios relevantes.', acceptAll: 'Aceptar todo', rejectAll: 'Rechazar no esenciales', manage: 'Gestionar preferencias', save: 'Guardar preferencias', saved: 'Preferencias guardadas.', alwaysOn: 'Siempre activas',
      'c.necessary': 'Estrictamente necesarias', 'c.functional': 'Funcionales', 'c.analytics': 'Analíticas', 'c.advertising': 'Publicidad',
      'cd.necessary': 'Inicio de sesión, carrito, pago, prevención de fraude y seguridad. No se pueden desactivar.', 'cd.functional': 'Recordar tu idioma, código postal de entrega y artículos vistos.', 'cd.analytics': 'Medición agregada y anonimizada del rendimiento de las páginas.', 'cd.advertising': 'Mostrar anuncios de AfroTransact en otros sitios y limitar su frecuencia.',
      gpc: 'Tu navegador envió una señal Global Privacy Control. Desactivamos las cookies publicitarias y la tratamos como una exclusión de venta y uso compartido.', table: 'Cookies que usamos', colName: 'Nombre', colProvider: 'Proveedor', colPurpose: 'Finalidad', colDuration: 'Duración', colCategory: 'Categoría',
      reqTitle: 'Hacer una solicitud de privacidad', reqIntro: 'Los residentes de cualquier estado de EE. UU. pueden usar este formulario. Verificamos tu identidad por correo y respondemos en 45 días.', reqType: '¿Qué te gustaría hacer?', 'r.access': 'Obtener una copia de mis datos', 'r.delete': 'Eliminar mis datos', 'r.correct': 'Corregir mis datos', 'r.optout': 'Excluirme de la venta, el uso compartido y los anuncios dirigidos', email: 'Correo de tu cuenta', state: 'Estado de residencia', details: 'Detalles (opcional)', submit: 'Enviar solicitud', submitted: 'Solicitud recibida. Referencia {ref}. Revisa {email} para confirmar tu identidad.', errSummary: 'Hay {n} problema(s) con tu solicitud', errEmail: 'Ingresa el correo de tu cuenta', errType: 'Elige una opción', errState: 'Elige tu estado', choose: 'Elegir…',
      dns: 'No vender ni compartir mi información personal', dnsOn: 'Exclusión activa — no venderemos ni compartiremos tus datos.', dnsOff: 'Sin exclusión.' },
    ar: { skip: 'انتقل إلى المحتوى الرئيسي', center: 'الشؤون القانونية والخصوصية', back: 'العودة إلى المتجر', language: 'اللغة', docs: 'المستندات القانونية', onPage: 'في هذه الصفحة', version: 'الإصدار', effective: 'ساري من', prev: 'الإصدارات السابقة', print: 'طباعة', translation: 'الترجمة العربية لهذا المستند قيد المراجعة القانونية. النسخة الإنجليزية أدناه هي المعتمدة.', draft: 'مسودة قيد المراجعة القانونية — غير سارية بعد.', followUs: 'تابع AfroTransact', contact: 'تواصل معنا', rights: 'جميع الحقوق محفوظة.',
      'd.terms': 'شروط الاستخدام', 'd.privacy': 'إشعار الخصوصية', 'd.cookies': 'إشعار ملفات تعريف الارتباط', 'd.choices': 'خيارات الخصوصية', 'd.returns': 'الإرجاع والاسترداد', 'd.sellers': 'شروط البائعين ومقدمي الخدمات', 'd.prohibited': 'المواد المحظورة والمقيدة', 'd.sms': 'شروط الرسائل النصية والإشعارات', 'd.accessibility': 'بيان إمكانية الوصول', cookiePrefs: 'تفضيلات ملفات تعريف الارتباط',
      cbTitle: 'خياراتك بشأن ملفات تعريف الارتباط', cbText: 'نستخدم ملفات تعريف الارتباط الضرورية لتشغيل المتجر. وبإذنك نستخدمها أيضًا لتذكر تفضيلاتك وقياس الزيارات وعرض إعلانات ملائمة.', acceptAll: 'قبول الكل', rejectAll: 'رفض غير الضرورية', manage: 'إدارة التفضيلات', save: 'حفظ التفضيلات', saved: 'تم حفظ التفضيلات.', alwaysOn: 'مفعّلة دائمًا',
      'c.necessary': 'ضرورية تمامًا', 'c.functional': 'وظيفية', 'c.analytics': 'تحليلية', 'c.advertising': 'إعلانية',
      'cd.necessary': 'تسجيل الدخول والسلة والدفع ومنع الاحتيال والأمان. لا يمكن إيقافها.', 'cd.functional': 'تذكر لغتك والرمز البريدي للتوصيل والمنتجات التي شاهدتها.', 'cd.analytics': 'قياس مجمّع وغير معرِّف لأداء الصفحات.', 'cd.advertising': 'عرض إعلانات AfroTransact على مواقع أخرى والحد من تكرارها.',
      gpc: 'أرسل متصفحك إشارة Global Privacy Control. أوقفنا ملفات الإعلانات ونعاملها كرفض للبيع والمشاركة.', table: 'ملفات تعريف الارتباط المستخدمة', colName: 'الاسم', colProvider: 'المزوّد', colPurpose: 'الغرض', colDuration: 'المدة', colCategory: 'الفئة',
      reqTitle: 'تقديم طلب خصوصية', reqIntro: 'يمكن لسكان أي ولاية أمريكية استخدام هذا النموذج. نتحقق من هويتك عبر البريد الإلكتروني ونرد خلال 45 يومًا.', reqType: 'ماذا تريد أن تفعل؟', 'r.access': 'الحصول على نسخة من بياناتي', 'r.delete': 'حذف بياناتي', 'r.correct': 'تصحيح بياناتي', 'r.optout': 'رفض البيع والمشاركة والإعلانات الموجّهة', email: 'البريد الإلكتروني للحساب', state: 'ولاية الإقامة', details: 'تفاصيل (اختياري)', submit: 'إرسال الطلب', submitted: 'تم استلام الطلب. المرجع {ref}. راجع {email} لتأكيد هويتك.', errSummary: 'يوجد {n} خطأ في طلبك', errEmail: 'أدخل البريد الإلكتروني لحسابك', errType: 'اختر ما تريد فعله', errState: 'اختر ولايتك', choose: 'اختر…',
      dns: 'لا تبع أو تشارك معلوماتي الشخصية', dnsOn: 'تم الرفض — لن نبيع بياناتك أو نشاركها.', dnsOff: 'لم يتم الرفض.' }
  };

  const DOCS = {
    terms: [
      ['Agreement', 'These Conditions of Use govern your use of AfroTransact websites, apps and services. By creating an account or placing an order you agree to them. If you do not agree, do not use AfroTransact.'],
      ['Who you buy from', 'AfroTransact is a marketplace. Many items are sold by independent sellers and many services are performed by independent providers; the seller or provider is shown on every listing, order and receipt. Items “Sold by AfroTransact” are sold by AfroTransact, Inc.'],
      ['Your account', 'You must be 18 or older, give accurate information and keep your sign-in details secure. You are responsible for activity on your account. One person may hold customer, seller and provider roles on the same account.'],
      ['Prices, payment and taxes', 'Prices are in US dollars and include any discount shown at checkout. We charge your payment method when you place an order, or a deposit when you book a service. Sales tax is calculated by delivery address and shown before you pay. Payments are processed by Stripe.'],
      ['Delivery and services', 'Delivery estimates are not guarantees. Risk of loss passes to you on delivery. Service appointments follow the provider’s cancellation policy shown before you book.'],
      ['Reviews and content', 'Reviews must reflect a genuine experience. You grant AfroTransact a licence to display content you submit. We may remove content that breaks our rules.'],
      ['Disputes', 'Contact customer service first; most issues are resolved within 48 hours. Any dispute not resolved informally will be settled by binding individual arbitration under the AAA Consumer Rules, except small-claims matters. You may opt out of arbitration within 30 days of accepting these terms by writing to {legal}.'],
      ['Notifications, email and text messages', 'By creating an account you agree that AfroTransact may send transactional messages about your account, orders, deliveries, bookings, payments and security by email, push notification and, if you provide a mobile number and opt in, SMS/MMS. Marketing messages are sent only with your separate, express consent, which is never a condition of purchase. SMS program: AfroTransact Alerts. Message frequency varies by activity. Message and data rates may apply. Reply STOP to cancel, HELP for help, or call {phone}. Carriers are not liable for delayed or undelivered messages. Full details are in the SMS & Notification Terms.'],
      ['Changes', 'We will give at least 30 days’ notice of material changes by email and in the app. The version and effective date appear at the top of this page.']
    ],
    privacy: [
      ['Information we collect', 'Account details (name, email, phone), delivery addresses, order and booking history, payment tokens from Stripe (never full card numbers), device and usage data, and messages with sellers, providers, drivers and Lola, our AI assistant.'],
      ['How we use it', 'To process orders and bookings, deliver packages, prevent fraud, provide support, personalise recommendations, and — only with consent — send marketing and measure advertising.'],
      ['Who we share it with', 'The seller, provider or driver handling your order receives only what they need (for example, a name and delivery address). We also share data with service providers such as Stripe, our address-verification and mapping vendors and cloud hosting, under contracts that limit their use. We do not sell personal information for money.'],
      ['AI features', 'Lola processes your questions to find products and manage orders. Personal details are redacted before any model call and conversations are not used to train third-party models.'],
      ['Retention', 'We keep order records for 7 years for tax and accounting, and other data only as long as needed for the purpose collected. Closed accounts are deleted or de-identified within 90 days, except where law requires us to keep records.'],
      ['Your rights', 'Depending on your state you may access, correct, delete or port your data and opt out of sale, sharing, targeted advertising and profiling. Use Your Privacy Choices or email {privacy}. We will not discriminate against you for exercising your rights.'],
      ['Mobile numbers, SMS and notifications', 'We use your mobile number to send the order, delivery, booking, security (including one-time passcodes) and — only if you opted in — marketing messages you asked for, through our messaging providers (such as Twilio). No mobile information will be shared with third parties or affiliates for marketing or promotional purposes. Text-messaging originator opt-in data and consent are excluded from every sharing category in this notice and will not be shared with any third party. Reply STOP to any text to opt out; manage email and push preferences in Account → Notifications.'],
      ['Children', 'AfroTransact is not directed to children under 13 and we do not knowingly collect their data.'],
      ['Security', 'Data is encrypted in transit and at rest, access is role-based and logged, and payment data is handled by PCI DSS Level 1 certified Stripe.'],
      ['Contact', 'Privacy questions: {privacy} · {address}.']
    ],
    cookies: [
      ['What cookies are', 'Cookies and similar technologies (local storage, pixels, SDKs) store small pieces of information on your device.'],
      ['How we use them', 'We group cookies into four categories. Strictly necessary cookies are always on; you choose the rest below. Your choice is saved for 12 months and you can change it at any time from “Cookie preferences” in the footer.'],
      ['Global Privacy Control', 'We honour the Global Privacy Control (GPC) browser signal as an opt-out of advertising cookies and of sale and sharing.']
    ],
    choices: [
      ['Your rights', 'You can ask for a copy of your data, ask us to correct or delete it, and opt out of sale, sharing and targeted advertising. Authorised agents may submit requests with signed permission.'],
      ['How we verify', 'We send a confirmation link to the email on your account. For deletion we also ask you to sign in. You can appeal a decision by replying to our response; we answer appeals within 60 days.']
    ],
    sms: [
      ['Program', 'AfroTransact Alerts is a text-message program from AfroTransact, Inc. that sends order confirmations, delivery and driver updates, booking reminders and changes, payment and refund notices, security alerts and one-time passcodes. If you separately opt in to AfroTransact Deals, we also send offers and restock alerts.'],
      ['How you opt in', 'You opt in by entering your mobile number and ticking the SMS consent box at sign-up, checkout, booking or in Account → Notifications, or by texting START to our number. Consent to marketing texts is optional and is not a condition of any purchase. We confirm your enrolment by text.'],
      ['Message frequency and cost', 'Message frequency varies with your orders and bookings; AfroTransact Deals sends up to 4 messages per month. Message and data rates may apply according to your mobile plan.'],
      ['Opting out and help', 'Reply STOP, END, CANCEL, UNSUBSCRIBE or QUIT to any message to stop texts; you will receive one final confirmation and no further messages. Reply START to rejoin. Reply HELP or contact {phone} or {privacy} for support. Security codes requested by you will still be sent by email if you opt out of SMS.'],
      ['Supported carriers', 'Supported on major US carriers including AT&T, T-Mobile, Verizon and their MVNOs. Carriers are not liable for delayed or undelivered messages.'],
      ['Push and email notifications', 'Push notifications are controlled in your device settings and in Account → Notifications. Every marketing email includes a one-click unsubscribe link; transactional emails about orders, bookings, payments and security cannot be turned off while your account is open.'],
      ['Privacy', 'Your mobile number and SMS consent are handled under our Privacy Notice. No mobile information will be shared with third parties or affiliates for marketing or promotional purposes, and opt-in data and consent are never shared with any third party.'],
      ['Quiet hours', 'Marketing texts are sent only between 9 am and 8 pm in your local time zone. Delivery and security messages may be sent at any time.']
    ],
    returns: [
      ['Return window', 'Most items can be returned within 30 days of delivery. The window is shown on each product page and in Your orders.'],
      ['Non-returnable items', 'Perishable food, opened beauty and personal-care items, custom-made clothing and gift cards cannot be returned unless they arrive damaged, defective or not as described.'],
      ['Damaged or wrong items', 'Report within 7 days with a photo. We refund or replace at no cost and you don’t need to send perishables back.'],
      ['Refunds', 'Refunds go to the original payment method within 3–5 business days after we receive the return, or immediately for drop-off at a pickup point. ACH refunds can take up to 7 business days.'],
      ['Services', 'Service refunds follow the provider’s cancellation policy shown at booking. If a provider cancels or does not show up, you get a full refund including any deposit.']
    ],
    sellers: [
      ['Eligibility', 'Sellers and providers must complete Stripe Connect verification and AfroTransact’s document review before going live. Licences, permits and insurance must stay current.'],
      ['Fees', 'AfroTransact charges a referral fee per sale or booking and a monthly plan fee where applicable. Current rates are shown in Seller Central and the plan page; we give 30 days’ notice of changes.'],
      ['Payouts', 'Earnings are held for at least 7 days after delivery (products) or service completion (services) so payments settle and returns or disputes can be raised. Released earnings appear as your available balance. You request payouts from your AfroTransact dashboard (minimum $25, one request per day); we review and send approved payouts by ACH to your verified bank account, normally within 1–2 business days. Payments are processed by Stripe on our behalf — you do not need a Stripe account login. We may hold specific funds while a return, dispute, chargeback or policy review is open, and unclaimed balances are paid out automatically before any legal holding limit.'],
      ['Listings and conduct', 'Listings must be accurate and comply with the Prohibited & Restricted Items policy. Do not divert customers off AfroTransact or manipulate reviews.'],
      ['Performance', 'We measure late shipments, cancellations, defects and response time. Accounts below standard may be limited or suspended after notice and a chance to appeal.']
    ],
    prohibited: [
      ['Never allowed', 'Weapons and ammunition, illegal drugs and paraphernalia, counterfeit goods, wildlife products protected under CITES, recalled items, and unapproved medicines or supplements making disease claims.'],
      ['Restricted — approval needed', 'Alcohol, meat and seafood, raw shea and herbal products marketed for health, cosmetics with mercury or hydroquinone, and immigration, tax or legal services. Approval requires the relevant licence or permit.'],
      ['Imported food', 'Imported foods must meet FDA rules, carry English labelling and allergen information, and come from registered facilities.'],
      ['Reporting', 'Report a listing from its page or email {legal}. We review reports within one business day.']
    ],
    accessibility: [
      ['Our commitment', 'We aim to meet WCAG 2.2 Level AA across the AfroTransact website and apps and test with VoiceOver, TalkBack, NVDA and keyboard-only navigation.'],
      ['What we support', 'Text resizing to 200%, reduced motion, high-contrast themes, captions on videos, and screen-reader labels on all controls. Seller storefront themes must meet minimum contrast before they can be published.'],
      ['Known limitations', 'Some older seller-uploaded images lack descriptive alt text; we are adding AI-suggested descriptions for sellers to review.'],
      ['Feedback', 'Email {accessibility} or call {phone}. We respond within 2 business days.']
    ]
  };
  const COOKIES = [['at_session', 'AfroTransact', 'Keeps you signed in', 'Session', 'necessary'], ['at_cart', 'AfroTransact', 'Remembers your cart', '30 days', 'necessary'], ['__stripe_mid', 'Stripe', 'Fraud prevention during payment', '1 year', 'necessary'], ['at_locale', 'AfroTransact', 'Language and region', '1 year', 'functional'], ['at_zip', 'AfroTransact', 'Delivery ZIP for dates and prices', '90 days', 'functional'], ['_at_metrics', 'AfroTransact', 'De-identified page performance', '13 months', 'analytics'], ['_gcl_au', 'Google Ads', 'Measure ad conversions', '90 days', 'advertising'], ['_fbp', 'Meta', 'Show ads on Meta platforms', '90 days', 'advertising']];

  const subs = new Set();
  const emit = () => subs.forEach((fn) => { try { fn(); } catch (e) { /* ignore */ } });
  window.addEventListener('storage', (e) => { if ([KEY, LKEY, CKEY].includes(e.key)) emit(); });
  const read = (k, d) => { try { return JSON.parse(localStorage.getItem(k) || 'null') || d; } catch (e) { return d; } };
  const write = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* ignore */ } emit(); };

  const API = {
    NETS, LOCALES, DOCS, COOKIES, DOC_KEYS: Object.keys(DOCS),
    get() { const o = read(KEY, null); return o ? { ...DEF, ...o, contacts: { ...DEF.contacts, ...(o.contacts || {}) }, legal: { ...DEF.legal, ...(o.legal || {}) } } : DEF; },
    save(patch) { const cur = API.get(); write(KEY, { ...cur, ...patch, rev: (cur.rev || 1) + 1, publishedAt: new Date().toISOString() }); },
    subscribe(fn) { subs.add(fn); return () => subs.delete(fn); },
    socials() { return API.get().socials.filter((x) => x.on && NETS[x.net] && NETS[x.net][2].test(x.url)).map((x) => ({ net: x.net, href: x.url, label: NETS[x.net][0] + ' (opens in a new tab)', icon: NETS[x.net][1], glyph: 'url("' + SI + BRAND[x.net][0] + '.svg")', color: BRAND[x.net][1], ring: BRAND[x.net][1] === '#000000' ? '#ffffff55' : BRAND[x.net][1] })); },
    validUrl(net, url) { return !!(NETS[net] && NETS[net][2].test(url || '')); },
    locale() { const l = read(LKEY, null); if (l && S[l]) return l; const n = (navigator.language || 'en').slice(0, 2); return S[n] ? n : 'en'; },
    setLocale(l) { if (S[l]) write(LKEY, l); },
    dir(l) { const x = LOCALES.find((r) => r[0] === (l || API.locale())); return x ? x[2] : 'ltr'; },
    t(key, l, vars) { const loc = l || API.locale(); let s = (S[loc] && S[loc][key]) || S.en[key] || key; if (vars) Object.keys(vars).forEach((k) => { s = s.split('{' + k + '}').join(vars[k]); }); return s; },
    fill(text) { const c = API.get().contacts; return text.replace(/\{(\w+)\}/g, (m, k) => c[k] || m); },
    fmtDate(iso, l) { try { return new Intl.DateTimeFormat(l || API.locale(), { dateStyle: 'long' }).format(new Date(iso + 'T12:00:00')); } catch (e) { return iso; } },
    gpc() { return !!navigator.globalPrivacyControl; },
    consent() { return read(CKEY, null); },
    setConsent(c) { write(CKEY, { necessary: true, functional: !!c.functional, analytics: !!c.analytics, advertising: !!c.advertising && !API.gpc(), at: new Date().toISOString(), v: API.get().legal.cookies.v }); },
    legalHref(key) { return 'AfroTransact Legal.dc.html#' + key; },
    toSettings() { const g = API.get(); const o = {}; g.socials.forEach((x) => { o['so_' + x.net + '_on'] = x.on; o['so_' + x.net + '_url'] = x.url; }); Object.entries(g.contacts).forEach(([k, v]) => { o['ct_' + k] = v; }); Object.entries(g.legal).forEach(([k, v]) => { o['lg_' + k + '_v'] = v.v; o['lg_' + k + '_eff'] = v.eff; }); o.draftBanner = g.draftBanner; return o; },
    fromSettings(o) {
      const g = API.get(); const bad = [];
      const socials = g.socials.map((x) => { const url = o['so_' + x.net + '_url'] ?? x.url; const on = o['so_' + x.net + '_on'] ?? x.on; if (on && !API.validUrl(x.net, url)) bad.push(NETS[x.net][0]); return { ...x, url, on }; });
      if (bad.length) return { ok: false, error: 'Fix the link for ' + bad.join(', ') + ' — it must be a full https:// profile URL on that network.' };
      const contacts = { ...g.contacts }; Object.keys(contacts).forEach((k) => { if (o['ct_' + k] != null) contacts[k] = o['ct_' + k]; });
      const legal = {}; Object.entries(g.legal).forEach(([k, v]) => { const nv = String(o['lg_' + k + '_v'] ?? v.v); const ne = String(o['lg_' + k + '_eff'] ?? v.eff); legal[k] = nv !== v.v ? { v: nv, eff: ne, hist: [[v.v, v.eff], ...v.hist] } : { ...v, eff: ne }; });
      API.save({ socials, contacts, legal, draftBanner: o.draftBanner ?? g.draftBanner }); return { ok: true };
    }
  };
  window.ATSite = API;
})();
