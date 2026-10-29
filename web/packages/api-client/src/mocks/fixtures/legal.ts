/* Legal & privacy center fixtures: the content-service seed (java/services/content/.../V5__seed_legal.sql and V6
   cookie table), so mock mode serves the same first drafts. Placeholders ({phone}, {privacy}, …) are filled when served. */

import { MORE_LEGAL_DOCUMENTS } from './legal-more';

export interface LegalVersionFixture {
  version: string;
  effectiveDate: string;
  status: 'CURRENT' | 'ARCHIVED';
  draftBanner: boolean;
  changeSummary: string;
  /** English Markdown (binding). */
  body: string;
}

export interface LegalDocumentFixture {
  key: string;
  title: string;
  /** Newest first. */
  versions: LegalVersionFixture[];
}

const FIRST_NINE: LegalDocumentFixture[] = [
  {
    key: 'conditions-of-use',
    title: 'Conditions of Use',
    versions: [
      {
        version: '3.2',
        effectiveDate: '2026-09-01',
        status: 'CURRENT',
        draftBanner: true,
        changeSummary: 'Adds notifications, SMS and current contact details',
        body: '# Conditions of Use\n\n## Agreement\n\nThese Conditions of Use govern your use of AfroTransact websites, apps and services. By creating an account or placing an order you agree to them. If you do not agree, do not use AfroTransact.\n\n## Who you buy from\n\nAfroTransact is a marketplace. Many items are sold by independent sellers and many services are performed by independent providers; the seller or provider is shown on every listing, order and receipt. Items “Sold by AfroTransact” are sold by AfroTransact, Inc.\n\n## Your account\n\nYou must be 18 or older, give accurate information and keep your sign-in details secure. You are responsible for activity on your account. One person may hold customer, seller and provider roles on the same account.\n\n## Prices, payment and taxes\n\nPrices are in US dollars and include any discount shown at checkout. We charge your payment method when you place an order, or a deposit when you book a service. Sales tax is calculated by delivery address and shown before you pay. Payments are processed by Stripe.\n\n## Delivery and services\n\nDelivery estimates are not guarantees. Risk of loss passes to you on delivery. Service appointments follow the provider’s cancellation policy shown before you book.\n\n## Reviews and content\n\nReviews must reflect a genuine experience. You grant AfroTransact a licence to display content you submit. We may remove content that breaks our rules.\n\n## Disputes\n\nContact customer service first; most issues are resolved within 48 hours. Any dispute not resolved informally will be settled by binding individual arbitration under the AAA Consumer Rules, except small-claims matters. You may opt out of arbitration within 30 days of accepting these terms by writing to {legal}.\n\n## Notifications, email and text messages\n\nBy creating an account you agree that AfroTransact may send transactional messages about your account, orders, deliveries, bookings, payments and security by email, push notification and, if you provide a mobile number and opt in, SMS/MMS. Marketing messages are sent only with your separate, express consent, which is never a condition of purchase. SMS program: {smsProgramName}. Message frequency varies by activity. Message and data rates may apply. Reply STOP to cancel, HELP for help, or call {phone}. Carriers are not liable for delayed or undelivered messages. Full details are in the SMS & Notification Terms.\n\n## Changes\n\nWe will give at least 30 days’ notice of material changes by email and in the app. The version and effective date appear at the top of this page.\n'
      },
      {
        version: '3.1',
        effectiveDate: '2026-03-15',
        status: 'ARCHIVED',
        draftBanner: true,
        changeSummary: 'Archived',
        body: '# Conditions of Use (version 3.1)\n\n## Archived version\n\nVersion 3.1 of the Conditions of Use applied from 2026-03-15 until 2026-09-01, when version 3.2 replaced it. Write to {legal} for a certified copy of the full text.\n'
      },
      {
        version: '3.0',
        effectiveDate: '2025-11-01',
        status: 'ARCHIVED',
        draftBanner: true,
        changeSummary: 'Archived',
        body: '# Conditions of Use (version 3.0)\n\n## Archived version\n\nVersion 3.0 of the Conditions of Use applied from 2025-11-01 until 2026-03-15, when version 3.1 replaced it. Write to {legal} for a certified copy of the full text.\n'
      }
    ]
  },
  {
    key: 'privacy',
    title: 'Privacy Notice',
    versions: [
      {
        version: '4.0',
        effectiveDate: '2026-09-01',
        status: 'CURRENT',
        draftBanner: true,
        changeSummary: 'Adds notifications, SMS and current contact details',
        body: '# Privacy Notice\n\n## Information we collect\n\nAccount details (name, email, phone), delivery addresses, order and booking history, payment tokens from Stripe (never full card numbers), device and usage data, and messages with sellers, providers, drivers and Lola, our AI assistant.\n\n## How we use it\n\nTo process orders and bookings, deliver packages, prevent fraud, provide support, personalise recommendations, and — only with consent — send marketing and measure advertising.\n\n## Who we share it with\n\nThe seller, provider or driver handling your order receives only what they need (for example, a name and delivery address). We also share data with service providers such as Stripe, our address-verification and mapping vendors and cloud hosting, under contracts that limit their use. We do not sell personal information for money.\n\n## AI features\n\nLola processes your questions to find products and manage orders. Personal details are redacted before any model call and conversations are not used to train third-party models.\n\n## Retention\n\nWe keep order records for 7 years for tax and accounting, and other data only as long as needed for the purpose collected. Closed accounts are deleted or de-identified within 90 days, except where law requires us to keep records.\n\n## Your rights\n\nDepending on your state you may access, correct, delete or port your data and opt out of sale, sharing, targeted advertising and profiling. Use Your Privacy Choices or email {privacy}. We will not discriminate against you for exercising your rights.\n\n## Mobile numbers, SMS and notifications\n\nWe use your mobile number to send the order, delivery, booking, security (including one-time passcodes) and — only if you opted in — marketing messages you asked for, through our messaging providers (such as Twilio). No mobile information will be shared with third parties or affiliates for marketing or promotional purposes. Text-messaging originator opt-in data and consent are excluded from every sharing category in this notice and will not be shared with any third party. Reply STOP to any text to opt out; manage email and push preferences in Account → Notifications.\n\n## Children\n\nAfroTransact is not directed to children under 13 and we do not knowingly collect their data.\n\n## Security\n\nData is encrypted in transit and at rest, access is role-based and logged, and payment data is handled by PCI DSS Level 1 certified Stripe.\n\n## Contact\n\nPrivacy questions: {privacy} · {address}.\n'
      },
      {
        version: '3.4',
        effectiveDate: '2026-01-10',
        status: 'ARCHIVED',
        draftBanner: true,
        changeSummary: 'Archived',
        body: '# Privacy Notice (version 3.4)\n\n## Archived version\n\nVersion 3.4 of the Privacy Notice applied from 2026-01-10 until 2026-09-01, when version 4.0 replaced it. Write to {legal} for a certified copy of the full text.\n'
      }
    ]
  },
  {
    key: 'cookies',
    title: 'Cookie Notice',
    versions: [
      {
        version: '2.1',
        effectiveDate: '2026-09-01',
        status: 'CURRENT',
        draftBanner: true,
        changeSummary: 'Adds notifications, SMS and current contact details',
        body: '# Cookie Notice\n\n## What cookies are\n\nCookies and similar technologies (local storage, pixels, SDKs) store small pieces of information on your device.\n\n## How we use them\n\nWe group cookies into four categories. Strictly necessary cookies are always on; you choose the rest below. Your choice is saved for 12 months and you can change it at any time from “Cookie preferences” in the footer.\n\n## Global Privacy Control\n\nWe honour the Global Privacy Control (GPC) browser signal as an opt-out of advertising cookies and of sale and sharing.\n\n## Cookies we use\n\nThe cookie table below this notice lists every cookie and similar technology we use, with its provider, purpose, duration and category. It is generated from the same configuration as the cookie banner, so it is always current.\n'
      },
      {
        version: '2.0',
        effectiveDate: '2026-01-10',
        status: 'ARCHIVED',
        draftBanner: true,
        changeSummary: 'Archived',
        body: '# Cookie Notice (version 2.0)\n\n## Archived version\n\nVersion 2.0 of the Cookie Notice applied from 2026-01-10 until 2026-09-01, when version 2.1 replaced it. Write to {legal} for a certified copy of the full text.\n'
      }
    ]
  },
  {
    key: 'privacy-choices',
    title: 'Your Privacy Choices',
    versions: [
      {
        version: '1.3',
        effectiveDate: '2026-09-01',
        status: 'CURRENT',
        draftBanner: true,
        changeSummary: 'First draft for counsel review',
        body: '# Your Privacy Choices\n\n## Your rights\n\nYou can ask for a copy of your data, ask us to correct or delete it, and opt out of sale, sharing and targeted advertising. Authorised agents may submit requests with signed permission.\n\n## How we verify\n\nWe send a confirmation link to the email on your account. For deletion we also ask you to sign in. You can appeal a decision by replying to our response; we answer appeals within 60 days.\n'
      }
    ]
  },
  {
    key: 'returns',
    title: 'Returns & Refunds',
    versions: [
      {
        version: '2.0',
        effectiveDate: '2026-06-01',
        status: 'CURRENT',
        draftBanner: true,
        changeSummary: 'Adds notifications, SMS and current contact details',
        body: '# Returns & Refunds\n\n## Return window\n\nMost items can be returned within 30 days of delivery. The window is shown on each product page and in Your orders.\n\n## Non-returnable items\n\nPerishable food, opened beauty and personal-care items, custom-made clothing and gift cards cannot be returned unless they arrive damaged, defective or not as described.\n\n## Damaged or wrong items\n\nReport within 7 days with a photo. We refund or replace at no cost and you don’t need to send perishables back.\n\n## Refunds\n\nRefunds go to the original payment method within 3–5 business days after we receive the return, or immediately for drop-off at a pickup point. ACH refunds can take up to 7 business days.\n\n## Services\n\nService refunds follow the provider’s cancellation policy shown at booking. If a provider cancels or does not show up, you get a full refund including any deposit.\n'
      },
      {
        version: '1.4',
        effectiveDate: '2025-12-01',
        status: 'ARCHIVED',
        draftBanner: true,
        changeSummary: 'Archived',
        body: '# Returns & Refunds (version 1.4)\n\n## Archived version\n\nVersion 1.4 of the Returns & Refunds applied from 2025-12-01 until 2026-06-01, when version 2.0 replaced it. Write to {legal} for a certified copy of the full text.\n'
      }
    ]
  },
  {
    key: 'seller-terms',
    title: 'Seller & Provider Terms',
    versions: [
      {
        version: '2.4',
        effectiveDate: '2026-09-01',
        status: 'CURRENT',
        draftBanner: true,
        changeSummary: 'Adds notifications, SMS and current contact details',
        body: '# Seller & Provider Terms\n\n## Eligibility\n\nSellers and providers must complete Stripe Connect verification and AfroTransact’s document review before going live. Licences, permits and insurance must stay current.\n\n## Fees\n\nAfroTransact charges a referral fee per sale or booking and a monthly plan fee where applicable. Current rates are shown in Seller Central and the plan page; we give 30 days’ notice of changes.\n\n## Payouts\n\nEarnings are held for at least 7 days after delivery (products) or service completion (services) so payments settle and returns or disputes can be raised. Released earnings appear as your available balance. You request payouts from your AfroTransact dashboard (minimum $25, one request per day); we review and send approved payouts by ACH to your verified bank account, normally within 1–2 business days. Payments are processed by Stripe on our behalf — you do not need a Stripe account login. We may hold specific funds while a return, dispute, chargeback or policy review is open, and unclaimed balances are paid out automatically before any legal holding limit.\n\n## Listings and conduct\n\nListings must be accurate and comply with the Prohibited & Restricted Items policy. Do not divert customers off AfroTransact or manipulate reviews.\n\n## Performance\n\nWe measure late shipments, cancellations, defects and response time. Accounts below standard may be limited or suspended after notice and a chance to appeal.\n'
      },
      {
        version: '2.3',
        effectiveDate: '2026-04-01',
        status: 'ARCHIVED',
        draftBanner: true,
        changeSummary: 'Archived',
        body: '# Seller & Provider Terms (version 2.3)\n\n## Archived version\n\nVersion 2.3 of the Seller & Provider Terms applied from 2026-04-01 until 2026-09-01, when version 2.4 replaced it. Write to {legal} for a certified copy of the full text.\n'
      }
    ]
  },
  {
    key: 'prohibited-items',
    title: 'Prohibited & Restricted Items',
    versions: [
      {
        version: '1.6',
        effectiveDate: '2026-07-15',
        status: 'CURRENT',
        draftBanner: true,
        changeSummary: 'First draft for counsel review',
        body: '# Prohibited & Restricted Items\n\n## Never allowed\n\nWeapons and ammunition, illegal drugs and paraphernalia, counterfeit goods, wildlife products protected under CITES, recalled items, and unapproved medicines or supplements making disease claims.\n\n## Restricted — approval needed\n\nAlcohol, meat and seafood, raw shea and herbal products marketed for health, cosmetics with mercury or hydroquinone, and immigration, tax or legal services. Approval requires the relevant licence or permit.\n\n## Imported food\n\nImported foods must meet FDA rules, carry English labelling and allergen information, and come from registered facilities.\n\n## Reporting\n\nReport a listing from its page or email {legal}. We review reports within one business day.\n'
      }
    ]
  },
  {
    key: 'accessibility',
    title: 'Accessibility Statement',
    versions: [
      {
        version: '1.2',
        effectiveDate: '2026-08-20',
        status: 'CURRENT',
        draftBanner: true,
        changeSummary: 'First draft for counsel review',
        body: '# Accessibility Statement\n\n## Our commitment\n\nWe aim to meet WCAG 2.2 Level AA across the AfroTransact website and apps and test with VoiceOver, TalkBack, NVDA and keyboard-only navigation.\n\n## What we support\n\nText resizing to 200%, reduced motion, high-contrast themes, captions on videos, and screen-reader labels on all controls. Seller storefront themes must meet minimum contrast before they can be published.\n\n## Known limitations\n\nSome older seller-uploaded images lack descriptive alt text; we are adding AI-suggested descriptions for sellers to review.\n\n## Feedback\n\nEmail {accessibility} or call {phone}. We respond within 2 business days.\n'
      }
    ]
  },
  {
    key: 'sms-terms',
    title: 'SMS & Notification Terms',
    versions: [
      {
        version: '1.0',
        effectiveDate: '2026-10-10',
        status: 'CURRENT',
        draftBanner: true,
        changeSummary: 'First draft for counsel review',
        body: '# SMS & Notification Terms\n\n## Program\n\n{smsProgramName} is a text-message program from AfroTransact, Inc. that sends order confirmations, delivery and driver updates, booking reminders and changes, payment and refund notices, security alerts and one-time passcodes. If you separately opt in to AfroTransact Deals, we also send offers and restock alerts.\n\n## How you opt in\n\nYou opt in by entering your mobile number and ticking the SMS consent box at sign-up, checkout, booking or in Account → Notifications, or by texting START to our number. Consent to marketing texts is optional and is not a condition of any purchase. We confirm your enrolment by text.\n\n## Message frequency and cost\n\nMessage frequency varies with your orders and bookings; AfroTransact Deals sends up to 4 messages per month. Message and data rates may apply according to your mobile plan.\n\n## Opting out and help\n\nReply STOP, END, CANCEL, UNSUBSCRIBE or QUIT to any message to stop texts; you will receive one final confirmation and no further messages. Reply START to rejoin. Reply HELP or contact {phone} or {privacy} for support. Security codes requested by you will still be sent by email if you opt out of SMS.\n\n## Supported carriers\n\nSupported on major US carriers including AT&T, T-Mobile, Verizon and their MVNOs. Carriers are not liable for delayed or undelivered messages.\n\n## Push and email notifications\n\nPush notifications are controlled in your device settings and in Account → Notifications. Every marketing email includes a one-click unsubscribe link; transactional emails about orders, bookings, payments and security cannot be turned off while your account is open.\n\n## Privacy\n\nYour mobile number and SMS consent are handled under our Privacy Notice. No mobile information will be shared with third parties or affiliates for marketing or promotional purposes, and opt-in data and consent are never shared with any third party.\n\n## Quiet hours\n\nMarketing texts are sent only between 9 am and 8 pm in your local time zone. Delivery and security messages may be sent at any time.\n'
      }
    ]
  }
];

/** All twenty-one documents of the legal center, in the order content-service lists them. */
export const LEGAL_DOCUMENTS: LegalDocumentFixture[] = [...FIRST_NINE, ...MORE_LEGAL_DOCUMENTS];

export const COOKIE_CATEGORIES = [
  { key: 'necessary', name: 'Strictly necessary', description: 'Sign-in, cart, checkout, fraud prevention and security. These cannot be switched off.', required: true, gpcOptOut: false },
  { key: 'functional', name: 'Functional', description: 'Remember your language, delivery ZIP and recently viewed items.', required: false, gpcOptOut: false },
  { key: 'analytics', name: 'Analytics', description: 'Aggregated, de-identified measurement of how pages perform.', required: false, gpcOptOut: false },
  { key: 'advertising', name: 'Advertising', description: 'Show AfroTransact ads on other sites and limit how often you see them.', required: false, gpcOptOut: true }
];

/** Content-service order: by category, then name. */
export const COOKIE_TABLE = [
  { name: '__stripe_mid', provider: 'Stripe', purpose: 'Fraud prevention during payment', duration: '1 year', category: 'necessary' },
  { name: 'at_cart', provider: 'AfroTransact', purpose: 'Remembers your cart', duration: '30 days', category: 'necessary' },
  { name: 'at_consent', provider: 'AfroTransact', purpose: 'Remembers your cookie choices and "Do not sell or share"', duration: '12 months', category: 'necessary' },
  { name: 'at_session', provider: 'AfroTransact', purpose: 'Keeps you signed in', duration: 'Session', category: 'necessary' },
  { name: 'at_locale', provider: 'AfroTransact', purpose: 'Language and region', duration: '1 year', category: 'functional' },
  { name: 'at_zip', provider: 'AfroTransact', purpose: 'Delivery ZIP for dates and prices', duration: '90 days', category: 'functional' },
  { name: '_at_metrics', provider: 'AfroTransact', purpose: 'De-identified page performance', duration: '13 months', category: 'analytics' },
  { name: 'at_attr', provider: 'AfroTransact', purpose: 'Which campaign link brought you to a pre-order (last touch)', duration: '30 days', category: 'analytics' },
  { name: '_fbp', provider: 'Meta', purpose: 'Show ads on Meta platforms', duration: '90 days', category: 'advertising' },
  { name: '_gcl_au', provider: 'Google Ads', purpose: 'Measure ad conversions', duration: '90 days', category: 'advertising' }
];
