---
key: subprocessors
title: Sub-processors and Service Providers
version: 1.0
effective: TBD-at-launch
change_summary: New document. Plain-language list of the third-party providers that handle data for AfroTransact (payments, messaging, email, AI, maps and address, hosting, monitoring, carriers), what data they receive, why, where, and a commitment to give notice of changes.
applies_to: Customers, sellers, providers and anyone whose data AfroTransact handles. Referenced by the Privacy Notice, the Data Processing Terms and the Seller Terms.
draft: true
---

# Sub-processors and Service Providers

> DRAFT. This document was prepared with AI assistance and has not been reviewed by qualified counsel. It was assembled from the platform's configuration and documentation. Operations must confirm each entry, processing location and link against what is actually live before publication. Text in double square brackets marked LEGAL is an open decision.

## 1. What this list is

1.1 AfroTransact uses other companies to run parts of the Marketplace. This page lists them in plain language: who they are, what data they get, why, and where it is processed. It supports the Privacy Notice, the Data Processing Terms and the Seller Terms.

1.2 "Sub-processor" means a company that processes personal data for us when we act as a processor or service provider for a seller or provider, or that we use to process personal data in running the Marketplace. Some companies below also act as independent controllers for their own purposes (for example Stripe, for fraud prevention and legal compliance). We say so where that applies.

1.3 We only share the data a provider needs for the stated purpose, under written terms that limit its use and require appropriate security. We do not sell personal information for money.

1.4 Only the providers marked "in use" are used in production. Providers marked "failover" or "optional" are configured as back-ups or are switched on only when enabled. Providers marked "development only" are not used with customer data in production.

## 2. How to read the table

Each entry shows the provider; what it does for us; the main categories of data it receives; where it processes data (the region our configuration uses, or "United States / global" where the provider operates globally and publishes its own locations); and a link to its privacy and terms pages. Links go to the provider's own pages, which may change.

## 3. Payments and identity verification

| Provider | What it does | Data it receives | Where | Links |
|---|---|---|---|---|
| Stripe, Inc. and its affiliates (in use) | Card and wallet payments, saved payment methods, refunds, disputes, Connect accounts for sellers and providers, identity and business verification (KYC/KYB), payouts, plan billing (subscriptions and invoices), fraud prevention. Stripe acts as a processor for payment processing and as an independent controller for fraud prevention, regulatory compliance and its own legal obligations. | Customers: name, email, billing details, card or bank details entered into Stripe's fields (we never see full card numbers), order amount, device and fraud signals. Sellers and providers: legal name, address, date of birth, tax ID, bank account, identity documents, ownership details, business details. | United States and globally, per Stripe's published locations | https://stripe.com/privacy; https://stripe.com/legal; https://stripe.com/legal/ssa; https://stripe.com/legal/connect-account |
| Stripe Tax (optional) | Calculation of sales tax in addition to our in-house rules engine, if enabled. | Delivery address, product tax category, amounts. | As Stripe | As Stripe |

## 4. Messaging: SMS, WhatsApp, email and push

| Provider | What it does | Data it receives | Where | Links |
|---|---|---|---|---|
| Twilio Inc. (in use) | Sends text messages: one-time codes, order, delivery, booking and security messages, and opt-in marketing; processes STOP/HELP replies and delivery receipts. | Mobile number, message content, delivery status, opt-out status. | United States and globally, per Twilio | https://www.twilio.com/en-us/legal/privacy; https://www.twilio.com/en-us/legal/tos; https://www.twilio.com/en-us/legal/messaging-policy; https://www.twilio.com/en-us/legal/aup |
| Meta Platforms, Inc. (WhatsApp Business Platform) (optional; switched on per programme) | Sends WhatsApp messages from templates approved by Meta, only to people who opted in (for example delivery-run announcements). | WhatsApp phone number, message content from approved templates, delivery and read status, opt-in status. | United States and globally, per Meta | https://www.whatsapp.com/legal/business-terms; https://business.whatsapp.com/policy; https://www.facebook.com/privacy/policy |
| Resend (in use) | Sends transactional email: order confirmations, shipping and account email, seller and provider notices, verification codes. | Email address, name, message content, delivery and bounce status. | United States (confirm region) | https://resend.com/legal/privacy-policy; https://resend.com/legal/terms-of-service; https://resend.com/legal/dpa [[LEGAL: confirm Resend's current DPA link and sub-processors]] |
| Amazon Web Services: Simple Email Service and End User Messaging (failover) | Back-up email and SMS delivery if the primary provider fails. | As for Twilio and Resend. | United States (configured region) | https://aws.amazon.com/privacy/; https://aws.amazon.com/service-terms/ |
| SendGrid / Twilio SendGrid; Vonage (failover, configured but used only if enabled) | Back-up email and SMS delivery. | As for Resend and Twilio. | United States / global | https://www.twilio.com/en-us/legal/privacy; https://www.vonage.com/legal/privacy-policy/ |
| Google Firebase Cloud Messaging and Apple Push Notification service (in use for app push) | Deliver push notifications to customer and driver mobile apps. | Device push token, notification content. | United States / global | https://policies.google.com/privacy; https://www.apple.com/legal/privacy/ |

## 5. Artificial intelligence

| Provider | What it does | Data it receives | Where | Links |
|---|---|---|---|---|
| OpenRouter, Inc. (in use) | Routes our requests to AI models for the shopping assistant (Lola), semantic search, listing writing and translation, triage and image moderation. | The text or images needed for the task. Personal details are redacted before model calls where our systems can do so. We configure it not to retain prompts. | United States; model hosts may be elsewhere | https://openrouter.ai/privacy; https://openrouter.ai/terms; https://openrouter.ai/data-processing-agreement |
| Model providers reached through OpenRouter (in use: configured models include Google Gemini, OpenAI GPT and Anthropic Claude families) | Run the models. | The same task content, via OpenRouter. | As each provider publishes | https://policies.google.com/privacy; https://openai.com/policies/privacy-policy; https://www.anthropic.com/legal/privacy |

Conversations are not used by us to train third-party models. [[LEGAL: confirm this statement against the contracts and the provider data-retention settings actually applied, and align with the Privacy Notice]]

## 6. Maps, geolocation and address verification

| Provider | What it does | Data it receives | Where | Links |
|---|---|---|---|---|
| Google (Maps Platform: Places, Geocoding, Routes) (in use) | Address autocomplete, place details, geocoding and drive-time routing for checkout, seller pickup locations, delivery coverage and routes. | Partial and full addresses typed into address fields, coordinates, origin and destination for routing. | United States / global | https://policies.google.com/privacy; https://cloud.google.com/maps-platform/terms |
| OpenStreetMap Nominatim (fallback when no Google key is configured) | Address search fallback. | The address text typed. | Per OSM Foundation | https://osmfoundation.org/wiki/Privacy_Policy; https://operations.osmfoundation.org/policies/nominatim/ |
| Smarty (optional) | US address deliverability checks. | Address. | United States | https://www.smarty.com/legal/privacy-policy [[LEGAL: confirm link]] |

## 7. Hosting, storage, delivery network and databases

| Provider | What it does | Data it receives | Where | Links |
|---|---|---|---|---|
| Cloud hosting: Google Cloud Platform, Amazon Web Services or Microsoft Azure (the platform can run on any; one is selected per environment) (in use) | Runs the application servers and object storage, including a locked bucket for seller verification documents. | All platform data, encrypted in transit and at rest where configured. | United States (production region: Virginia) [[LEGAL: confirm cloud and region]] | https://cloud.google.com/terms/data-processing-addendum; https://aws.amazon.com/compliance/gdpr-center/; https://www.microsoft.com/licensing/docs/view/Microsoft-Products-and-Services-Data-Protection-Addendum-DPA |
| Layerbase (in use, per the current production design) | Hosts PostgreSQL and Valkey (cache) databases and the Meilisearch index. | Platform database content including account, order and booking data. | United States (Virginia) | https://layerbase.com [[LEGAL: confirm provider's legal and privacy links]] |
| Cloudflare (in use) | Content delivery network, DNS, web application protection, edge hosting of web apps and object storage (R2) for product images. | IP address, request metadata, images and files uploaded. | Global edge network | https://www.cloudflare.com/privacypolicy/; https://www.cloudflare.com/cloudflare-customer-dpa/ |
| UploadThing (development and legacy; being replaced by Cloudflare R2) | File uploads for product images in non-production environments. | Uploaded images. | United States | https://uploadthing.com [[LEGAL: confirm link]] |

## 8. Monitoring, error reporting and analytics

| Provider | What it does | Data it receives | Where | Links |
|---|---|---|---|---|
| Grafana Labs (Grafana Cloud) (in use) | Stores metrics, logs and traces to keep the platform running. | Technical telemetry. We avoid personal data in telemetry; some identifiers and IP addresses can appear in logs. | Per Grafana Cloud region | https://grafana.com/legal/privacy-policy/; https://grafana.com/legal/dpa/ |
| Functional Software, Inc. (Sentry) (optional) | Collects browser and app error reports. | Error details, URL, device and browser information, user identifier. | United States | https://sentry.io/privacy/; https://sentry.io/legal/dpa/ |
| Advertising and measurement (Google Ads conversion measurement; Meta pixel) (only if the visitor consents to advertising cookies; Global Privacy Control is honoured) | Measure ad conversions and show our ads on other sites. | Cookie identifiers, page and purchase events. | United States / global | https://policies.google.com/privacy; https://www.facebook.com/privacy/policy. The Cookie Notice lists each cookie. |
| AfroTransact first-party analytics | De-identified page-performance measurement and delivery-run link attribution as aggregate counters. | Aggregated, no visitor identifier. | Our own infrastructure | Cookie Notice |

## 9. Sign-in providers

| Provider | What it does | Data it receives | Where | Links |
|---|---|---|---|---|
| Google, Apple and Facebook (Meta) sign-in (each shown only if the customer chooses it) | Let a customer sign in with an existing account. | The identifiers and profile details the customer approves (name, email). | As the provider | https://policies.google.com/privacy; https://www.apple.com/legal/privacy/; https://www.facebook.com/privacy/policy |

## 10. Shipping carriers and delivery

| Provider | What it does | Data it receives | Where | Links |
|---|---|---|---|---|
| UPS, USPS, FedEx and DHL Express (used for the carriers enabled in each market) | Provide rates, labels, tracking and delivery. Carriers act as independent businesses for delivery. | Recipient and sender name, address, phone, email where needed, package details, tracking events. | United States and international as the carrier operates | https://www.ups.com/us/en/support/shipping-support/legal-terms-conditions.page; https://www.usps.com/privacy/; https://www.fedex.com/en-us/privacy-policy.html; https://www.dhl.com/global-en/home/footer/global-privacy-notice.html [[LEGAL: confirm links]] |
| Selling businesses (sellers, providers) and AfroTransact drivers | Not sub-processors. Sellers and providers receive the data needed to fulfil an order and act as independent controllers for it (see the Data Processing Terms). Drivers are engaged by or for AfroTransact and receive the delivery details for their route. | Name, delivery address, phone, delivery preferences, handover code check. | Not applicable | Seller Terms; Data Processing Terms |

## 11. Product data services (no customer personal data)

AfroTransact also uses services such as Open Food Facts and GS1 (product data), Pexels, Unsplash and Wikimedia (stock or sample images) to fill the catalogue. They receive product identifiers only, not personal data about customers, sellers or providers.

## 12. Locations and international transfers

12.1 Our production systems run in the United States. Some providers process data in other countries. Where the law requires a transfer mechanism, we rely on the mechanism described in the Data Processing Terms and the Privacy Notice [[LEGAL: transfer mechanism, for example Standard Contractual Clauses, UK addendum, Data Privacy Framework certification status]].

## 13. Changes to this list and how you will be told

13.1 We will update this page before we add or replace a sub-processor that processes personal data of sellers, providers or customers on our behalf, and we will show the date of the last change at the top of the page.

13.2 For sellers and providers, we will give at least thirty (30) days' notice [[LEGAL: confirm notice period and whether to offer a right to object and terminate]] of a new sub-processor, by email to the account owner and in Seller Center or Provider Hub. If you object on reasonable data-protection grounds in that period, tell us at {legal}; we will work with you in good faith and, if we cannot resolve the objection, you may close your account as the Data Processing Terms provide.

13.3 We may add a provider immediately in an emergency (for example to replace one that has failed), and we will notify you promptly afterwards.

13.4 Changes in the table of providers marked "optional" or "failover" count as changes once the provider is switched on in production.

## 14. Contact

Questions about this list go to {privacy} or {legal}. Our address is {address}.
