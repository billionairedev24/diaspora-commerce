# Provider terms matrix

Draft prepared with AI assistance on 2026-10-28 (document set version 1.0). Counsel must verify every row against the live text before launch.

This matrix lists every third party the customer-facing documents depend on, what we use it for (taken from `docs/engineering/secrets-and-configuration.md`, `docs/adr/0004`, `0005`, `0006` and the service READMEs), the terms that bind us, the obligations those terms put on us, and the exact place in our documents that meets each obligation. Section names refer to the `##` headings in `docs/legal/<key>.en.md`.

## How the terms were read, and what that means

**Important limitation.** The session's network policy blocked direct access to the providers' own legal pages (`stripe.com`, `www.twilio.com`, `whatsappbusiness.com`, `www.ctia.org`, `www.ftc.gov` all failed with "host not allowed" or a proxy 403). The full text of those terms was therefore **not read**. What was read on 2026-10-28 is:

- the search-result summaries and snippets of the official pages and of secondary commentary that a web search returned (the "Read" column says which); and
- our own repository (how each provider is actually wired).

Where this matrix says "obligation", it is either (a) taken from a search summary of the official page, marked **S**, or (b) general knowledge of the provider's published policy, marked **K**, not checked in this session. **Nothing marked K is verified.** Whoever reviews this must open each URL, read the current text, and record the date they read it in the "Reviewed by counsel" column. To unblock a re-run, the session would need these hosts allowed: `stripe.com`, `docs.stripe.com`, `www.twilio.com`, `help.twilio.com`, `whatsappbusiness.com`, `www.whatsapp.com`, `developers.facebook.com`, `www.ctia.org`, `www.ftc.gov`, `openrouter.ai`, `cloud.google.com`, `www.smarty.com`.

Legend: **S** = summarised in a search result read 2026-10-28; **K** = from general knowledge, unverified; **R** = our repository.

---

## 1. Stripe (payments, saved methods, Link, membership billing)

**What we use it for (R):** checkout PaymentIntents with manual capture per shipment; saved cards and bank accounts (SetupIntents); refunds and disputes; Apple Pay, Google Pay and Link through the Express Checkout Element and PaymentSheet; gift card purchases; Plus membership off-session renewals; Connect for sellers (the other agent's documents). Stripe collects device and fraud signals (`__stripe_mid` cookie).

| Terms | URL | Read 2026-10-28 | Obligations on us | Where our documents satisfy it |
|---|---|---|---|---|
| Stripe Services Agreement (US) | https://stripe.com/legal/ssa | **Not read in full.** Summary from search (S): each party makes a compliant privacy policy available; we must follow Card Network Rules and PCI standards; no cash refunds for card transactions unless required by law (S) | Publish a lawful privacy policy; follow card network rules; refund to the original card, not to cash; keep records and not disclose card data; comply with law | Privacy: `privacy` "Information we collect", "Who we share it with"; Refunds go back to the payer: `returns` "How refunds are paid"; store credit cannot be paid out in cash: `store-credit-terms` "What you can never do with it"; PCI statement: `privacy` "Security" |
| Stripe Services Agreement - Service Terms and Financial Services Terms | https://stripe.com/legal/ssa-service-terms | Listed in search (S), not read | Payment method rules; recurring-charge and stored-credential rules (K) | Saved methods and recurring authorisation: `conditions-of-use` "Prices, payment and taxes" (Saved payment methods); `membership-terms` "Automatic renewal: what you agree to" |
| Stripe Connected Account Agreement | https://stripe.com/legal/connect-account | **Not read**; K: connected accounts (sellers) accept it; platform stays responsible to end customers for its marketplace | Seller onboarding terms and flow-down | The other agent's seller documents. Customer-side effect: `conditions-of-use` "Who you are buying from" |
| Stripe Privacy Policy | https://stripe.com/privacy | **Not read in full.** S: Stripe distinguishes Business Services and End User Services; merchants are told to give their own end customers the disclosures and handle privacy requests; end customers who paid through a merchant are told to ask that merchant | Disclose to customers that Stripe collects and uses payment, device and fraud data; point to Stripe's policy; handle privacy requests about our own data | `conditions-of-use` "Payment processing by Stripe"; `privacy` "Who we share it with" (payment and fraud services); `privacy-choices` "How to make a request" |
| Stripe Link Terms / Consumer Terms of Service | https://stripe.com/legal/link ; https://stripe.com/legal/consumer | Link page found in search; **SMS and consent content not found (S)**; not read in full | If we offer Link, tell the customer that Link is a Stripe service with its own terms and privacy notice, and that they choose to use it | `conditions-of-use` "Payment processing by Stripe" (Link) |
| Stripe Prohibited and Restricted Businesses | https://stripe.com/legal/restricted-businesses | **Not read**; K | Do not sell prohibited goods or services; restricted ones need approval | `prohibited-items` "Never allowed", "Restricted: allowed only with approval" |
| Card network rules (Visa, Mastercard and others) | via Stripe | Referenced in Stripe agreement (S) | Show clear refund/return policy and total price before payment (K); clear cancellation and recurring-billing terms and consent | `returns`; `conditions-of-use` "Prices, payment and taxes"; `membership-terms` |
| Customer agreement to Stripe terms when paying | - | - | Customers agree to Stripe's terms and privacy policy when paying | `conditions-of-use` "Payment processing by Stripe"; `membership-terms` "Payments by Stripe"; `gift-card-terms` "Buying a gift card" |

**Open items:** Q29 (exact Stripe terms to name, controller/processor description, Link wording, saved-method and recurring authorisation wording).

---

## 2. Twilio (SMS, and A2P 10DLC / toll-free registration)

**What we use it for (R):** SMS one-time codes, order, delivery and pre-order texts, marketing texts for opted-in customers; STOP/HELP handling on `/v1/webhooks/twilio/inbound`; a Messaging Service carries the A2P 10DLC campaign; Amazon End User Messaging is the deployed failover.

| Terms | URL | Read 2026-10-28 | Obligations on us | Where our documents satisfy it |
|---|---|---|---|---|
| Twilio Messaging Policy | https://www.twilio.com/en-us/legal/messaging-policy | **Not read in full.** S: recipients must give real, informed opt-in; tell them who is sending and what they will get; show how to withdraw; keep proof of consent until withdrawn; first message must carry an opt-out instruction (STOP or UNSUBSCRIBE, CANCEL, END, QUIT) | Obtain and prove consent; clear identity and purpose; honour opt-outs; do not send prohibited content | Consent text, records and keywords: `sms-terms` "How you opt in", "Consent records", "Opting out and getting help" |
| Twilio Acceptable Use Policy | https://www.twilio.com/en-us/legal/aup | **Not read**; K: bans unlawful, deceptive, harassing content and content that breaks carrier rules; high-risk categories are restricted | Do not send unlawful or carrier-prohibited content; take responsibility for end-user compliance | `sms-terms` "Program"; `prohibited-items`; `community-guidelines` "Messages with sellers, providers, drivers and support" |
| Twilio Messaging Campaign Terms (A2P 10DLC) | https://www.twilio.com/en-us/legal/messaging-campaign-terms | Listed in search (S), not read | Campaign registration must match real use; honest program description, samples and opt-in flow | `sms-terms` "Program", "How you opt in" (must match registered campaign: Q14) |
| Twilio consent and opt-in guidance | https://www.twilio.com/docs/verify/consent-opt-in | Listed in search (S); S: opt-in confirmation should name the program, give help and opt-out instructions, say frequency, and state "message and data rates may apply"; separate consents for different message types for toll-free verification; unchecked boxes | Opt-in confirmation message content; unchecked consent boxes; separate consent per message type | `sms-terms` "How you opt in" (separate boxes; unticked marketing consent), "Message frequency", "Message and data rates", the quoted consent text |
| Twilio data terms (Customer Agreement, Data Protection Addendum, privacy notice) | https://www.twilio.com/en-us/legal | Not read; K | Disclose Twilio as a provider; do not let Twilio use data for its own marketing | `privacy` "Who we share it with"; `sms-terms` "Who sends and who sees" |

**CTIA Messaging Principles and Best Practices** (https://www.ctia.org/ - the current document was **not retrievable**; the search returned no CTIA text, only Twilio's description of it, S): consent that is clear, a program description with frequency, opt-out and help keywords, "message and data rates may apply", no sharing of opt-in data with third parties for marketing, honouring opt-out words other than STOP, no consent as a condition of purchase.

| CTIA / TCPA requirement | Where satisfied |
|---|---|
| Program name, message types, frequency, rates | `sms-terms` "Program", "Message frequency", "Message and data rates" |
| STOP / HELP, and honour other opt-out words | `sms-terms` "Opting out and getting help" (also STOPALL, OPTOUT, REVOKE; START/UNSTOP/YES) |
| Consent not a condition of purchase | `sms-terms` "How you opt in"; `conditions-of-use` "Messages, notifications and Lola" |
| Carriers are not liable | `sms-terms` "Supported carriers" |
| No sharing of mobile opt-in data for marketing | `sms-terms` "Privacy"; `privacy` "Mobile numbers, SMS and WhatsApp" |
| Opt-in records (proof of consent) | `sms-terms` "Consent records" |
| Quiet hours | `sms-terms` "Quiet hours" (Q14: conflict with platform default of 21:00-08:00) |
| Privacy policy link and terms link at opt-in | consent text in `sms-terms`; storefront string `auth.smsDisclosure` links the SMS Terms and Privacy Notice |

**Open items:** Q14.

---

## 3. Meta WhatsApp Business Platform

**What we use it for (R):** WhatsApp Cloud API (`WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`), text or approved templates, behind `DELIVERY_RUNS_WHATSAPP_ENABLED` (off); a community link; storefront "chat on WhatsApp" links. Facebook Login is a separate sign-in option (see 10).

| Terms | URL | Read 2026-10-28 | Obligations on us | Where our documents satisfy it |
|---|---|---|---|---|
| WhatsApp Business Terms of Service | https://www.whatsapp.com/legal/business-terms | **Not read**; K | Accept WhatsApp's terms; comply with its policies; WhatsApp may process message content under its own privacy practices | `sms-terms` "Who sends and who sees"; `privacy` "Who we share it with" |
| WhatsApp Business Messaging Policy | https://whatsappbusiness.com/policy/ | **Not read in full.** S (search summary): may only message people who opted in; opt-in must be separate by message category; the business must be named and the purpose clear; explain how to opt out and honour it | Separate, named opt-in; opt-out honoured; templates for business-initiated messages | `sms-terms` "How you opt in" (WhatsApp separate; opt-in names WhatsApp), "Opting out and getting help" (WhatsApp), "Who sends and who sees" (approved templates) |
| WhatsApp Commerce Policy | https://www.whatsapp.com/legal/commerce-policy | **Not read in full.** S: applies when using catalogs or other commerce features; secondary sources list banned categories (weapons, alcohol, tobacco, gambling, adult products, live animals, and others). The lists vary between sources, so not relied on | Do not sell prohibited goods through WhatsApp commerce features | `prohibited-items` (alcohol, tobacco, weapons and others are never allowed or restricted) and Q18 (reconcile against the live Commerce Policy) |
| Meta Platform Terms and Business Data / data-sharing terms | https://developers.facebook.com/terms | Not read; K | Do not misuse Meta data; honour user opt-outs; disclose to users | `privacy`; `cookies` ("Meta" `_fbp` advertising cookie) |

**Open items:** Q15 (WhatsApp Business account name, templates, whether the phone number is a number registered for SMS, and whether WhatsApp is enabled at launch).

---

## 4. AI model providers (AI gateway)

**What we use it for (R):** Lola, semantic search embeddings, AI image moderation, translation, listing writing and triage, through `ai-gateway` (OpenAI-compatible API) to **OpenRouter**, with defaults of Gemini 2.5 Flash-Lite then GPT-5 Nano and per-task model lists. PII redaction before model calls; prompt/completion logging disabled in our logs (`java/services/ai-gateway/README.md`).

| Terms | URL | Read 2026-10-28 | Obligations on us | Where our documents satisfy it |
|---|---|---|---|---|
| OpenRouter Terms of Service and Privacy Policy | https://openrouter.ai/terms ; https://openrouter.ai/privacy | **Not read in full.** S (search summary of OpenRouter docs and blog): by default OpenRouter does not store prompts or completions; logs only metadata; prompt logging is opt-in; a separate opt-in lets OpenRouter use inputs for product improvement; Zero Data Retention can be enforced per request or account, routing only to providers with a ZDR policy; the downstream provider's retention and training policy still applies | Choose settings that match what we promise; disclose use of providers; do not send disallowed data | `privacy` "AI features" (redaction, no training, short retention by providers); `conditions-of-use` "Messages, notifications and Lola"; `community-guidelines` "Using Lola". **Q16: verify that account-level logging and training opt-ins are off and ZDR is enforced before the statements in `privacy` go live** |
| Underlying model providers (for example Google Gemini, OpenAI) | per model on OpenRouter | Not read | Their usage policies (no illegal or harmful use), retention and training terms apply through OpenRouter | `privacy` "AI features"; `community-guidelines` "Using Lola" |
| AI disclosure laws | state statutes | Not researched here | Tell users when they interact with an AI | `conditions-of-use`; `community-guidelines` "Using Lola" (Q16) |

---

## 5. Maps, geo and address providers

**What we use it for (R):** Google Places (New) autocomplete and Place Details, Geocoding, Routes (via `geo`, server side); Smarty (US address checks); OpenStreetMap Nominatim as a no-key fallback only.

| Terms | URL | Read 2026-10-28 | Obligations on us | Where our documents satisfy it |
|---|---|---|---|---|
| Google Maps Platform Terms of Service and Service Specific Terms | https://cloud.google.com/maps-platform/terms | **Not read; the search that was run returned only OpenRouter results**; K: end users of apps using Maps APIs are subject to Google's privacy policy; no caching or scraping beyond allowed; attribution where required | Disclose use of Google Maps; show required attribution; no caching beyond allowed | `privacy` "Who we share it with" (address, map and routing services); `accessibility` "Known limitations" (third-party address tools). Counsel to check whether an explicit "Google Maps/Google Earth Additional Terms" and Google Privacy Policy link must appear (Q11) |
| Smarty Terms of Service | https://www.smarty.com/legal/terms-of-service | Not read; K | Use for address validation only, no resale | `privacy` "Who we share it with" |
| OpenStreetMap Nominatim usage policy | https://operations.osmfoundation.org/policies/nominatim/ | Not read; K | Fair use, attribution, no bulk geocoding | `privacy` (OpenStreetMap-based services); not used for production traffic when the Google key is set |

---

## 6. Email, push and messaging infrastructure

**What we use it for (R):** Resend (transactional email, bounces and complaints), Amazon SES (deployed failover), Firebase Cloud Messaging (Android push), Apple Push Notification service (iOS push), Expo/EAS (mobile builds).

| Terms | Read 2026-10-28 | Obligations on us | Where our documents satisfy it |
|---|---|---|---|
| Resend Terms and Acceptable Use Policy (https://resend.com/legal/terms-of-service) | Not read; K | No spam; consent for marketing; honour unsubscribe and complaints | `sms-terms` "Email" (one-click unsubscribe on marketing emails); `privacy` "Who we share it with" |
| Amazon Web Services Acceptable Use Policy; Amazon SES and End User Messaging terms (https://aws.amazon.com/aup/) | Not read; K | Comply with AUP; for SMS, carrier consent rules also apply | `sms-terms`; `privacy` |
| CAN-SPAM Act (federal) | Statute, not re-read | Honest headers and subject lines; physical address in marketing email; unsubscribe honoured within 10 business days | `sms-terms` "Email" (unsubscribe); address `{address}` is in every document; marketing emails carry it (R: notifications README, `List-Unsubscribe` header) |
| Firebase and Apple developer terms | Not read; K | Push content rules; notification permission via device | `sms-terms` "Push notifications" |

---

## 7. Hosting, storage, observability

**What we use it for (R):** target cloud (GCP/AWS/Azure) with the lean profile; Layerbase (Postgres, Valkey, Meilisearch, Virginia); Cloudflare (Workers for web apps, R2 for product images); Grafana Cloud (telemetry); Sentry (web and mobile error monitoring); UploadThing (dev/uat media; replaced by R2 in prod per ADR 0004).

| Provider | Obligations on us | Where satisfied |
|---|---|---|
| Cloud, Layerbase, Cloudflare, Grafana Cloud, Sentry (terms not read; K) | Data processing terms; list as service providers; disclose in privacy notice; keep security and breach duties | `privacy` "Who we share it with" (hosting and infrastructure), "Security", "Where your data is stored"; the separate subprocessor page is maintained by another document set (Q11 cross-check) |

---

## 8. Delivery carriers

**What we use it for (R):** UPS, USPS, FedEx and DHL Express through `carrier-gateway`; AfroTransact drivers (driver app); pickup points.

| Terms | Obligations on us | Where satisfied |
|---|---|---|
| Carrier service terms, hazardous-materials and restricted-commodity rules (not read; K) | Do not ship prohibited or dangerous goods; correct address and package details; claims windows | `prohibited-items` "Shipping limits"; `preorder-delivery-terms` "Delivery fees", "If nobody is there to receive the delivery"; `returns` "After delivery" |

---

## 9. Advertising and analytics (cookie providers)

**What we use it for (R, `cookie_definition`):** `_gcl_au` (Google Ads) and `_fbp` (Meta) after advertising consent; `_at_metrics` and `at_attr` first-party analytics.

| Terms | Obligations on us | Where satisfied |
|---|---|---|
| Google Ads data policies, Meta Business Tools Terms (not read; K) | Obtain legally required consent; honour opt-outs and GPC; do not send sensitive data; disclose | `cookies` (table, "Global Privacy Control", "Third parties and sharing"); `privacy-choices` "Do Not Sell or Share My Personal Information"; `privacy` |

---

## 10. Social sign-in

Google, Apple and Facebook sign-in (R: identity). Terms not read; K: show a privacy policy and terms link on the sign-in page; follow brand and data-use rules. Satisfied by `privacy` "Information we collect" (social sign-in), and the links on the sign-in page. Q11.

---

## 11. Laws that shape the documents (not provider terms, but they drive the same text)

| Law / rule | Status read 2026-10-28 | Where satisfied |
|---|---|---|
| FTC "Click-to-Cancel" Negative Option Rule | S: **vacated by the Eighth Circuit on 2025-07-08** (Custom Communications v. FTC); search results say the FTC has since moved to restart rulemaking, so the position at launch must be re-checked | `membership-terms` is drafted to the strictest of the old rule's concepts anyway: clear disclosure before purchase, express consent, online cancellation as easy as sign-up, reminders, no fee after cancel |
| ROSCA (15 U.S.C. 8401-8405) | S: the FTC still enforces it with Section 5; search result did not give the text; K: clear disclosure of material terms, express informed consent, simple cancellation | `membership-terms` "Automatic renewal: what you agree to", "How to cancel" |
| State automatic renewal laws (California B&P 17600 and others) | S/K: California amended its law effective 2025-07-01 for online cancellation; **not verified** | `membership-terms` (Q09) |
| TCPA, state mini-TCPAs | K | `sms-terms` |
| CCPA/CPRA and other state privacy laws, GPC | K | `privacy`, `privacy-choices`, `cookies` |
| DMCA 17 U.S.C. 512 | K (statutory elements of notice and counter-notice) | `ip-policy` |
| FTC rule on fake reviews, 16 CFR Part 465; Consumer Review Fairness Act | K | `community-guidelines` |
| FTC Mail, Internet or Telephone Order Merchandise Rule | K | `preorder-delivery-terms` "Delivery dates and windows" (Q30) |
| WCAG 2.2 AA | Standard; our own checklist in `docs/engineering/accessibility.md` | `accessibility` |
