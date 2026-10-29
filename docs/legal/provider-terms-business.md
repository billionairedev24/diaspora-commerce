# Third-party provider terms reviewed for the business-side legal documents

Date of review: 2026-10-29. Prepared with AI assistance. Counsel must verify every entry against the live vendor text before relying on it.

## Limits of this review (read first)

The working environment could not open the vendors' sites directly: fetches of stripe.com, docs.stripe.com and twilio.com failed at DNS resolution because those hosts are outside the session's allowed network domains. The vendor terms were therefore read only through web-search result excerpts, which quote or paraphrase the official pages and, in places, third-party summaries. No full official text was read. Each row says which kind of source supported it and what is still unverified.

To complete the review, allow stripe.com, docs.stripe.com, twilio.com, business.whatsapp.com, openrouter.ai, cloud.google.com and resend.com in the session network settings (or have a person read the URLs below) and re-run. Open items are also in `open-questions-business.md`.

Source quality key: **A** = excerpt of the vendor's own page; **B** = vendor documentation excerpt about the topic, not the contract; **C** = third-party summary (do not rely on it).

## Summary table

| Provider and document | Official URL | Date read | Source quality |
|---|---|---|---|
| Stripe Connected Account Agreement | https://stripe.com/legal/connect-account | 2026-10-29 | A (excerpts), full text not read |
| Stripe Services Agreement (General Terms; Service Terms) | https://stripe.com/legal/ssa ; https://stripe.com/legal/ssa-service-terms | 2026-10-29 | A (reserve and offset excerpts), full text not read |
| Stripe Connect Platform Agreement | https://stripe.com/legal/connect | 2026-10-29 | B/C (excerpts via third-party aggregator and country PDFs) |
| Stripe Connect service agreement types and onboarding documentation | https://docs.stripe.com/connect/service-agreement-types ; https://docs.stripe.com/connect/identity-verification | 2026-10-29 | A/B (search excerpts) |
| Stripe Connect risk and liability management; account balances | https://docs.stripe.com/connect/risk-management ; https://docs.stripe.com/connect/account-balances ; https://docs.stripe.com/connect/accounts-v2/connected-account-configuration | 2026-10-29 | B (excerpts) |
| Stripe Prohibited and Restricted Businesses | https://stripe.com/legal/restricted-businesses | 2026-10-29 | A/C (page found, "last updated May 13, 2026" per a result; category detail from secondary summaries) |
| Stripe reserves FAQ | https://support.stripe.com/questions/connect-reserves-faq | 2026-10-29 | listed in results; content not read |
| Twilio Messaging Policy | https://www.twilio.com/en-us/legal/messaging-policy | 2026-10-29 | A (excerpts) |
| Twilio Acceptable Use Policy | https://www.twilio.com/en-us/legal/aup | 2026-10-29 | listed in results; content not read |
| WhatsApp Business Messaging Policy | https://business.whatsapp.com/policy | 2026-10-29 | A (excerpts) plus C |
| WhatsApp opt-in documentation | https://developers.facebook.com/documentation/business-messaging/whatsapp/getting-opt-in | 2026-10-29 | A/B (excerpts) |
| WhatsApp Business Terms of Service; Commerce Policy | https://www.whatsapp.com/legal/business-terms ; https://www.whatsapp.com/legal/commerce-policy | 2026-10-29 | Not read. Only third-party commentary found |
| OpenRouter privacy, logging and ZDR documentation; DPA | https://openrouter.ai/docs/guides/privacy/logging ; https://openrouter.ai/data-processing-agreement | 2026-10-29 | A/B (excerpts) |
| Google Maps Platform Service Specific Terms; Places API policies | https://cloud.google.com/maps-platform/terms ; https://developers.google.com/maps/documentation/places/web-service/policies | 2026-10-29 | A/B (excerpts), C for caching limits |
| Resend legal pages (terms, privacy, DPA, subprocessors) | https://resend.com/legal | 2026-10-29 | Not read. Search returned no Resend page |
| Other vendors in `subprocessors.en.md` (Google Gemini, OpenAI, Anthropic via OpenRouter; Cloudflare; Grafana Cloud; Sentry; Smarty; AWS; carriers; Firebase; Apple; Layerbase; UploadThing) | see that file | not read | Not read. Links are unverified |

## Stripe

### Which agreement applies to our Connect account type

Our connected accounts are created (see `docs/engineering/payments-idempotency.md`, "Seller accounts and liability") with Stripe collecting requirements and doing KYC/KYB, Stripe liable for the connected account's negative balances, the account paying Stripe's fees, no Stripe dashboard for the seller, and manual payouts. Onboarding is Stripe's embedded component inside Seller Center.

What the excerpts say (B): Stripe's documentation says a "full" service agreement creates a direct service relationship between Stripe and the connected account holder and its legal language is the Stripe Connected Account Agreement; a "recipient" agreement is for accounts that cannot take card payments themselves; the agreement type is fixed once accepted; accounts without Stripe-hosted dashboard access must accept the correct service agreement, and the platform can include Stripe's terms in its own terms; the platform must disclose Stripe's privacy policy to connected accounts. Another excerpt (B) says that under Accounts v2, Stripe collects KYC in most configurations and the platform collects it only when it carries losses and gives no dashboard.

Conclusion for drafting: the seller is the merchant and takes card payments, so the full service agreement, i.e. the Connected Account Agreement (which incorporates the Stripe Services Agreement), is the one drafted for. This is an inference, not confirmed from the Stripe contract. Counsel or operations must confirm in the Stripe dashboard (Connect settings, and an account's `tos_acceptance`/service agreement field) which agreement and jurisdiction version each account accepted. Tracked as OQ-09.

### Stripe Connected Account Agreement (A, partial)

| Obligation | Applies to | Where our documents satisfy it |
|---|---|---|
| Agreement is between Stripe and the account holder; it governs an account enrolled with a platform; it incorporates the Stripe Services Agreement | Sellers, providers | Payment Terms s.3 (sellers accept it during embedded onboarding; links shown) |
| Account holder warrants information given to Stripe is accurate and complete | Sellers, providers | Payment Terms s.4.2; Seller Terms s.2.2 |
| Account holder is solely responsible for goods and services sold and is financially liable to Stripe for disputes, refunds and fines, even if shared with the platform | Sellers, providers | Seller Terms ss.8.1, 15.4, 20; Payment Terms ss.7, 8 |
| Stripe may suspend or stop services to a connected account | Sellers, providers | Seller Terms s.21.1; Payment Terms ss.3.2, 13 |
| Country versions differ (for example a Stripe Payments Europe version) | AfroTransact | Payment Terms s.3.1 placeholder: record agreement version and jurisdiction accepted |

Not verified: sanctions, data and record-retention clauses, and dispute-resolution terms.

### Stripe Services Agreement (A, partial)

| Obligation | Applies to | Where satisfied |
|---|---|---|
| Stripe may establish a reserve (collateral held in a reserve account) after a Reserve Notice, change reserve terms if risk changes, and the user has no right to interest or earnings on it | Users (our sellers and providers, indirectly) | Payment Terms s.6.6 (Stripe reserves are Stripe's to set and we cannot override); Seller Terms s.15.2 (our own reserve) |
| On a negative balance, a portion of any reserve is applied automatically; Stripe may deduct, recoup or set off from payments otherwise owed | Users | Payment Terms s.8.3; Seller Terms s.15.3, 15.5 |
| For Connect accounts, payouts pause while the balance is negative; Stripe clears long-standing negative balances after a period (a docs excerpt says 180 days) | Platform and accounts | Payment Terms ss.6.5, 8.3 (we stop payouts until repaid) |
| Reserve released when the reason ends | Users | Seller Terms s.15.2 (we release on the same principle) |

Not verified: restricted-business clauses, PCI, data-use and sanctions clauses, record retention.

### Stripe Connect Platform Agreement (B/C)

Obligations on AfroTransact as platform, from excerpts: we alone are responsible for the platform services we provide and Stripe is not responsible to connected accounts for them; our user agreement must explain the platform services, how we access and use connected account data and the activity the account may perform; connected accounts with no Stripe-hosted dashboard must accept Stripe's service agreement, and we must give them a link to Stripe's privacy policy; for connected accounts created with custom or express onboarding the platform is liable to Stripe for their activity, whereas our configuration is the Stripe-liable one (confirm). Platforms must keep monitoring for fraud even after Stripe verifies an account, and Stripe's verification does not satisfy the platform's own legal KYC obligations (from Stripe Connect identity-verification docs, B).

| Obligation | Where satisfied |
|---|---|
| Explain platform services and our use of connected account data | Payment Terms ss.2, 4.3, 11; Data Processing Terms s.2.3; Seller Terms s.19.4 |
| Disclose Stripe privacy policy | Payment Terms s.3.1; Sub-processors s.3 |
| Connected accounts accept Stripe's agreement | Payment Terms s.3.1 |
| Our own checks beyond Stripe KYC | Seller Terms s.2.3 (two-step review); Payment Terms s.4.4 |
| Ongoing fraud monitoring | Seller Terms ss.15, 17, 21 (holds, prohibited conduct, suspension) |
| Platform liable to Stripe for activity (if applicable) | Seller Terms ss.15.3, 15.5, 22 (offset, indemnity) |

### Stripe Prohibited and Restricted Businesses (A/C)

Per excerpts: the list has prohibited businesses, restricted businesses needing extra due diligence, and country-specific rules; it is representative, not exhaustive; it changes (a result showed "last updated May 13, 2026"). Secondary summaries name restricted or unsupported categories including CBD, cryptocurrency, pharmaceuticals, medical devices and telemedicine, tobacco and e-cigarettes, dating, crowdfunding and fundraising, travel, adult content, shipping brokers and freight forwarders without authorisation, and many financial services (investment, credit, peer-to-peer money transmission, bill pay). This matters to AfroTransact because diaspora marketplaces commonly feature herbal and health products, money-related and travel or immigration services, and shipping.

| Requirement | Where satisfied |
|---|---|
| Sellers and providers must not sell what Stripe prohibits; the current list applies | Seller Terms s.17.2; Provider Terms ss.2.3, 13.1; Payment Terms s.3.3 |
| Restricted categories need approval and licences | Seller Terms ss.2.5, 17.1; Provider Terms s.2.2 |
| Money transmission, stored value, escrow, crowdfunding barred | Seller Terms s.17.2 |

Open: counsel must read the live list, decide which restricted categories (herbal and health products, immigration or legal services, travel, shipping, remittance-like services) AfroTransact will accept, and align the Prohibited & Restricted Items policy (customer-facing document, other agent). OQ-10.

## Twilio (A, partial)

Twilio Messaging Policy excerpts: prior express consent is required before sending messages; consent cannot be bought, sold, rented or transferred; recipients must be able to opt out with standard keywords, one confirmation message may follow an opt-out and no more; certain content is never allowed even with consent (excerpts and third-party pages point to categories such as SHAFT: sex, hate, alcohol, firearms, tobacco, which we treat as unverified). Versions shown with dates April 13, 2026 and November 12, 2024; the current one governs. US long-code traffic needs A2P 10DLC or toll-free verification (from our own `secrets-and-configuration.md`; not re-verified).

| Obligation | Where satisfied |
|---|---|
| Obtain consent before messaging; consent not transferable | Seller Terms s.19.5 and Data Processing Terms s.3.2 (sellers may not text customers from Marketplace contact details without their own consent); the customer SMS Terms (other agent) cover AfroTransact's own consent |
| Honour STOP/opt-out | Same; SMS Terms (other agent) |
| Prohibited content even with consent | Sub-processors s.4 lists Twilio; Seller Terms s.17 (conduct) and s.19.5. Sellers cannot send via our Twilio account |
| Acceptable Use Policy | Not read. OQ-11 |

## WhatsApp / Meta (A partial, plus C)

From the WhatsApp Business Messaging Policy and opt-in documentation excerpts: businesses must get opt-in before messaging outside the 24-hour customer-service window (marketing, utility, authentication), by a method the business chooses and remains responsible for; separate opt-in per message category is recommended; pre-checked boxes are reported as not acceptable (C); do not impersonate another business or mislead; do not share one customer's chat information with another; regulated verticals cannot be messaged on the WhatsApp Business App and commerce experiences for regulated verticals are prohibited; businesses need all required licences and permits and must publish notices and obtain permissions; Meta may update the policy without notice. Messages are sent from Meta-approved templates (our own docs).

| Obligation | Where satisfied |
|---|---|
| Opt-in and template approval for AfroTransact's WhatsApp messages | Sub-processors s.4 (optional, per programme); customer-facing SMS and notification terms (other agent) |
| Sellers do not message customers via our WhatsApp | Seller Terms s.19.5 |
| Regulated verticals | Seller Terms s.17; Provider Terms s.13.1 (prohibited and restricted services). Policy alignment needed. OQ-12 |
| WhatsApp Business Terms of Service and Commerce Policy | Not read. OQ-11 |

## OpenRouter and AI model providers (A/B)

Excerpts: OpenRouter says prompts are not retained unless the account opts into prompt logging; Zero Data Retention routing can be enforced per account, request or guardrail; a provider's endpoint policy can differ from its general policy; OpenRouter publishes a DPA. A third-party source claims prompt logging grants OpenRouter commercial-use rights (C; unverified). Model hosts (Google, OpenAI, Anthropic) have own retention policies that vary by endpoint.

| Obligation or fact | Where satisfied |
|---|---|
| Disclose AI providers and data sent | Sub-processors s.5 |
| Do not send personal data unredacted; do not allow prompt logging; use ZDR routing | Sub-processors s.5 statement, flagged `[[LEGAL]]` to confirm against actual account settings. Engineering must confirm prompt logging is off and ZDR is enforced (OQ-13) |
| Sellers using AI listing tools remain responsible for content | Seller Terms s.4.3 |

## Google Maps Platform (A/B, caching from C)

Excerpts: Places content must not be pre-fetched, cached or stored beyond stated exceptions, with the place ID exempt; results shown on a map must be on a Google map with attribution, and without a map need the Google logo; apps using the Places API must provide public Terms of Use and a Privacy Policy that incorporate Google's terms and privacy policy; Places content cannot be used with a non-Google map. Our geo service calls Google from the server and stores a geocoded position of seller ship-from addresses.

| Obligation | Where satisfied |
|---|---|
| Terms of Use and Privacy Policy incorporating Google's | Privacy Notice and Conditions of Use (other agent) must reference Google; Sub-processors s.6 lists Google with links |
| Caching and storage limits | Not a legal document item. Engineering follow-up: confirm that stored geocodes of addresses (position of ship-from addresses and delivery addresses) are permitted under the current Maps terms, or switch to place IDs, and that attribution is displayed (OQ-14) |

## Resend and other email vendors

Search returned no Resend page. Not read. Sub-processors lists Resend with unverified links. OQ-11.

## Cloudflare, Grafana Cloud, Sentry, Smarty, AWS, cloud hosts, carriers, Firebase, APNs, Layerbase, UploadThing

Not read. Listed in `subprocessors.en.md` from the repository configuration and ADR 0004 only; links are best-known and must be verified. Carrier (UPS, USPS, FedEx, DHL) shipping terms and any carrier account agreements the business signed were not available; sellers who buy labels through us are charged the carrier cost plus handling (see `seller-fees.en.md`). OQ-15.

## Consistency check performed against the repository

- Hold window: 7 days default, configurable (`SETTLEMENT_HOLD`, payments); drafts say default and configurable.
- Payout: minimum USD 25 and one request per day; drafts label these "at the time of drafting".
- Connect account: Stripe collects requirements, Stripe liable for losses, account pays fees, no dashboard, manual payouts; Payment Terms describe this and flag the allocation of liability as open.
- Fees: commission precedence (retail, negotiated, plan, category, default), connection fee on captured amount, label charge plus handling, delivery fee fault rule; reflected in `seller-fees.en.md`.
- Plan-fee waterfall: credit balance first, then earnings if opted in, then card; reflected in Seller Terms s.7.
- Disputes: no self-service return for delivered orders; customer help requests with evidence; staff decide; trust-ops claims with a seller response window and decision SLA; reflected in Seller Terms ss.11, 12.
- Reviews: sellers may reply; reviews moderated by staff; providers reply once; reflected in Seller Terms s.13 and Provider Terms s.11.
- Seeded text (V5) used the entity name "AfroTransact, Inc." and email placeholders; the new documents use a LEGAL placeholder for the entity and keep the placeholders {legal}, {privacy}, {address}.
