# Open questions for counsel: business-side legal documents

Prepared with AI assistance on 2026-10-29. Every item below is marked `[[LEGAL: ...]]` somewhere in `docs/legal/*.en.md` for the business-side documents (seller-terms, provider-terms, payments-terms, subprocessors, data-processing, seller-fees). The documents must not be published until each item is decided or consciously accepted. Recommendations are starting points for qualified counsel, not legal advice.

Documents: ST = seller-terms, PT = provider-terms, PAY = payments-terms, SP = subprocessors, DP = data-processing, SF = seller-fees.

## A. Company, law and dispute resolution

| ID | Open question | Where | Recommendation |
|---|---|---|---|
| OQ-01 | Contracting entity: legal name, form, state of organisation, registered address. The seeded text uses "AfroTransact, Inc." and the site footer says the same, but no company facts were verified. Also whether AfroTransact Retail is the same entity or a separate affiliate (merchant of record). | ST 1.1, PT 1.1, PAY 1.2, DP 1.1 | Confirm from the certificate of incorporation. Decide whether Retail sits in a separate subsidiary so that marketplace liability and first-party sales are separated; the documents are written so either works. |
| OQ-02 | Governing law, exclusive venue or arbitration, class-action waiver, small-claims carve-out, informal-resolution period (draft: 30 days). | ST 24, PT 17, PAY 14, DP | Governing law of the entity's home state; venue in its courts. If arbitration is chosen (AAA or JAMS, individual only, opt-out window for 30 days), keep it business-to-business only; sellers are businesses, so the consumer-arbitration restrictions are less pressing but state rules for small sole proprietors vary. |
| OQ-03 | Disclaimers, limitation of liability and cap amount (draft placeholder: fees paid in the prior 12 months); carve-outs; whether data-protection breaches are uncapped or have a super-cap. | ST 23, PT 15.2, DP 13 | Cap at fees paid in 12 months with a floor (a fixed dollar amount) so a new seller with few fees has a meaningful figure. Carve out fraud, wilful misconduct, indemnities and, for the seller's liability, nothing (sellers' liability should be uncapped for product liability and chargebacks). Format disclaimers conspicuously as the governing state requires. |
| OQ-04 | Whether the independent-controller characterisation of sellers and providers for fulfilment data is right in each scenario (sellers using their own couriers, providers with sensitive data). | ST 19.1, DP 2.4 | Keep independent controllers for fulfilment data (consistent with how the marketplace behaves). Have counsel confirm it for CCPA "business" status and for service providers handling health-related data. |

## B. Money and payments

| ID | Open question | Where | Recommendation |
|---|---|---|---|
| OQ-05 | Money-transmission positioning: AfroTransact receives customer funds, holds them through a hold window, and settles to sellers. Is this covered by an agent-of-payee structure, the payment-processor or marketplace exemptions in each state, or does it need licences? Does Stripe Connect's structure (funds in Stripe balances and transfers) change the answer? | PAY 2.3, ST 15.2 | Before launch obtain a written opinion from payments counsel in each launch state. Prefer designing so that funds sit in Stripe (Connect balances) and AfroTransact is a commercial agent of sellers for collection, with a clear agent-of-payee clause in the Marketplace Terms and customer terms. Do not publish PAY 2.3 until this is settled. |
| OQ-06 | Hold window: legality and wording of a staff-configurable 7-day (default) hold, reserves, and holds during disputes; whether changing the period needs notice; reserve structure (segregated or not); reserve retention after termination (draft: placeholder 90 to 180 days); unclaimed-property handling. | ST 14.2, 14.7, 15.2, 21.3; PAY 6.2 | State a default and a maximum (for example, not more than 30 days without notice), give notice before changing, no interest, and a release schedule. Run the unclaimed-property analysis with the finance adviser (dormancy periods differ by state). |
| OQ-07 | Allocation of liability for negative balances between Stripe, AfroTransact and the seller. The engineering configuration has Stripe liable for the connected account's negative balance. | PAY 8.3 | Describe accurately once the Stripe contract is read: the seller stays liable to Stripe and to AfroTransact, and AfroTransact's recovery rights (offset, debit where permitted) remain. Do not tell sellers that Stripe's liability releases them. |
| OQ-08 | Fee-change notice period (draft: 30 days) and whether shorter notice applies to changes that favour sellers or are required by Stripe or law; error-notification period for statements (draft placeholder 60 days). | ST 6.4, 25.1; PAY 6.9 | 30 days for increases, immediate for decreases and for changes imposed by law, Stripe or a network. 60 days for statement errors. Matches the seeded text's "30 days' notice of changes". |
| OQ-09 | Which Stripe agreement and jurisdiction version each connected account accepts (Connected Account Agreement incorporating the Services Agreement is assumed from our account configuration). | PAY 3.1 | Check one live account's service-agreement and acceptance record in Stripe, and store the version, date and IP per seller in the seller service. Platform engineering story if acceptance is not already recorded. |
| OQ-10 | Stripe prohibited and restricted businesses: decide which restricted categories the marketplace will accept (herbal and health products, immigration or legal services, travel, shipping and freight, remittance-like services, CBD, tobacco) and whether Stripe has approved them for the account. | ST 17.2; PT 2.3, 13.1 | Read the live Stripe list; email Stripe's risk team about the categories diaspora sellers commonly use; bar anything Stripe prohibits; list restricted categories as "approval required" in the Prohibited & Restricted Items policy (customer-facing, separate agent) to match. |

## C. Tax and licensing

| ID | Open question | Where | Recommendation |
|---|---|---|---|
| OQ-16 | Tax registration status: states in which AfroTransact is registered and collecting sales tax; marketplace-facilitator positions per state; taxability of services and shipping; whether AfroTransact collects any tax on service bookings; treatment of AfroTransact Retail separately. | ST 16.1, PT 7.4, SF 9.1 | A CPA or tax adviser should map nexus and registrations (the repo already has a nexus tracker and tax rules, and its own design notes require CPA approval). Keep the documents generic until registration is complete. |
| OQ-17 | Information reporting (Form 1099-K or 1099-NEC, backup withholding), thresholds, and who files. | ST 16.5 | Confirm with the tax adviser; Stripe may file 1099-K for card payments in some structures, so avoid double reporting. |
| OQ-18 | State and local licensing that applies to AfroTransact itself (money transmission in OQ-05; sales-tax permits; food-marketplace or cottage-food rules; regulated goods such as alcohol) and to sellers and providers (food permits, cosmetology, health). | ST 2.1, 4.2; PT 2.2 | Create a launch-state matrix: what AfroTransact must hold, what each seller category must hold, and what the marketplace verifies. Today's onboarding collects food permit, labelling confirmation and licence and insurance details, which supports a verification-based model. |
| OQ-19 | Minimum insurance requirements (product liability for sellers; professional and general liability for providers; additional-insured status for AfroTransact). | ST 20.2; PT 3.1 | Set category-based minimums with the broker; require certificates at onboarding for services and food. |

## D. Product and policy decisions that need a legal view

| ID | Open question | Where | Recommendation |
|---|---|---|---|
| OQ-20 | Seller appeal of help-request and claim decisions: offer one at all, and its time limit. Also whether to commit to notice and appeal before performance-based termination. | ST 11.7, 21.1 | Offer one internal review within 7 to 14 days on new evidence; commit to notice before performance-based termination except urgent cases. The seeded text already promised notice and a chance to appeal. |
| OQ-21 | Compensation for sellers when a delivery run is cancelled by staff. | ST 8.4 | State that no compensation is owed (current draft) but provide that any seller-side costs already incurred through our fault are reviewed case by case. Product owner to confirm. |
| OQ-22 | Name of the customer guarantee. "A-to-z" is a third party's brand style; the repo uses "A-to-z claims" internally and the seller text says "A-to-z style guarantee". | ST 12 heading | Rename publicly to something owned (for example "AfroTransact Guarantee") before launch. Legal to clear the name. |
| OQ-23 | Health and sensitive-data services (HIPAA and state health privacy, telehealth, wellness, immigration or legal advice) and whether to prohibit or limit them. | PT 12.2, 13.1; DP 3.5 | Launch without services that require HIPAA-grade handling; limit to non-clinical personal services; require providers to handle any health data under their own obligations. |
| OQ-24 | Seller acceptance of Stripe prohibited and restricted lists by reference (incorporation by reference of a list that Stripe changes). | ST 17.2 | Keep incorporation, plus an obligation to follow notices; ask counsel whether incorporating third-party lists is enforceable against small sellers. |

## E. Data protection and transfers

| ID | Open question | Where | Recommendation |
|---|---|---|---|
| OQ-25 | Which privacy laws apply in each live market (CCPA and other state laws; UK and EU GDPR if diaspora markets outside the US go live). | DP 1.3 | Start with US state laws; add UK and EU instruments only when those markets launch. The seller application currently lists live countries (default US only). |
| OQ-26 | International transfer mechanism: SCC modules, UK addendum, Swiss addendum, Data Privacy Framework status. | DP 11.1, SP 12.1 | Not needed while operations are US-only; prepare SCC annexes (controller-to-controller) ahead of EU and UK launch. |
| OQ-27 | Incident-notice periods (draft: 72 hours both ways), deletion deadlines after closure, audit rights and cost allocation. | DP 6, 8.3, 10, 12 | 72 hours is common; consider 48 hours from sellers who handle sensitive data. Align with the Privacy Notice (the seeded notice states 7-year order retention). |
| OQ-28 | Record-retention period for sellers and for the ledger (draft: longer of 7 years or the legal period). | ST 26.1, PAY 12.1 | Keep 7 years, consistent with the seeded Privacy Notice; confirm with tax adviser. |
| OQ-29 | Sub-processor change notice (draft: 30 days) and the objection and termination right. | SP 13.2, DP 7.2 | 30 days and a right to object and close the account, which is the common market position. |
| OQ-30 | Statements about AI: that conversations are not used to train third-party models; PII redaction before model calls; OpenRouter prompt-logging and zero-data-retention settings. | SP 5 | Engineering to confirm account settings (logging off, ZDR enforced) and that redaction works as described, then keep or remove the statement. |
| OQ-31 | Confirmation of facts in the sub-processor list: cloud provider and region, Layerbase, Cloudflare R2, Grafana Cloud, Sentry, advertising pixels, Resend region and DPA, links, which failover providers are actually enabled. | SP throughout | Operations to confirm against the production environment and replace each `[[LEGAL: confirm link]]`. |

## F. Vendor terms not yet read

| ID | Open question | Where | Recommendation |
|---|---|---|---|
| OQ-11 | Full text of Stripe Connected Account Agreement, Stripe Services Agreement, Connect Platform Agreement, Twilio AUP, WhatsApp Business Terms and Commerce Policy, and Resend terms and DPA was not read directly (network blocked). | `provider-terms-business.md` | Allow the vendor hosts and re-run the review, or have a person read them, then update `provider-terms-business.md` and the documents. |
| OQ-12 | WhatsApp regulated verticals and commerce policy against the marketplace's categories. | ST 17; PT 13 | After reading the Commerce Policy, ensure the WhatsApp channel is not used to sell or discuss regulated goods. |
| OQ-13 | See OQ-30 (OpenRouter settings). | SP 5 | As OQ-30. |
| OQ-14 | Google Maps caching and attribution against our stored geocodes of addresses. | `provider-terms-business.md` | Engineering and counsel to check the Maps Service Specific Terms. |
| OQ-15 | Carrier agreements (UPS, USPS, FedEx, DHL) and what AfroTransact has signed. | SP 10 | Provide the signed carrier agreements to counsel to check pass-through of label costs and any restrictions on resale of shipping. |
