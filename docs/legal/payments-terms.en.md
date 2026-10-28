---
key: payments-terms
title: Payment Terms for Sellers and Providers
version: 1.0
effective: TBD-at-launch
change_summary: New document. Explains payment processing for sellers and providers through Stripe Connect, the Stripe agreements you accept, verification, AfroTransact's role, settlement and payout holds, fees, refunds, chargebacks, negative balances, sanctions and record retention.
applies_to: Every seller and service provider with a connected payment account on AfroTransact. Part of the Seller Terms and the Service Provider Terms.
draft: true
---

# Payment Terms for Sellers and Providers

> DRAFT. This document was prepared with AI assistance and has not been reviewed by qualified counsel. It must be reviewed and approved by counsel before it is published or relied on. Text in double square brackets marked LEGAL is an open decision. Descriptions of third-party terms are summaries, not substitutes for the third party's own text.

## 1. Scope and order of priority

1.1 These Payment Terms apply to everything about how money moves between customers, AfroTransact and you: payment acceptance, settlement, payouts, holds, reserves, refunds, chargebacks, fees and negative balances. They are part of the Seller Terms and the Service Provider Terms (together, the "Marketplace Terms"). For anything about money movement these Payment Terms control over the Marketplace Terms.

1.2 "We" and "AfroTransact" mean [[LEGAL: contracting entity name]]. "Stripe" means the Stripe entity that provides payment services to you under the Stripe Agreements in section 3. "Connected Account" means your Stripe account created through AfroTransact.

## 2. Who does what

2.1 Payment processing is provided by Stripe. AfroTransact is a platform that uses Stripe Connect to accept customer payments for your sales and to send you your share. AfroTransact is not a bank. Your connected account is held with Stripe, not with AfroTransact.

2.2 Customers pay AfroTransact's checkout. We authorise the payment when an order or booking is placed and capture it as described in the Marketplace Terms (for products, when a package leaves for delivery; for services, a deposit when the booking is confirmed and the balance on completion). We then settle your share to your Connected Account after the hold window in section 6.

2.3 [[LEGAL: money-transmission positioning. Confirm, with licensing counsel, whether AfroTransact's receipt of customer funds and onward transfer to sellers and providers is structured as an agent-of-payee arrangement, a payment facilitator or marketplace exemption, or otherwise, and what state licences or registrations are or are not required. Do not publish this section until confirmed.]] We state that, as between you and us, we receive payments as the platform that facilitates your sale and settle your share through Stripe Connect.

2.4 Where AfroTransact Retail sells as seller of record, the sale proceeds belong to AfroTransact Retail; there is no payout or transfer to it as if it were a third-party seller.

2.5 We are not responsible for Stripe's acts or omissions, but we will cooperate to resolve problems. Nothing in these Payment Terms makes Stripe a party to the Marketplace Terms except where those Terms say Stripe may rely on them.

## 3. Stripe agreements you accept

3.1 To receive payments you must have a Connected Account and accept Stripe's terms. Your Connected Account is opened through embedded onboarding inside Seller Center or Provider Hub. By opening it you agree to the Stripe Connected Account Agreement, which incorporates the Stripe Services Agreement, and Stripe's privacy policy [[LEGAL: confirm with counsel and Stripe's current documents which Stripe service agreement applies to the connected account configuration used: Stripe verifies identity, Stripe is liable for negative balances of the connected account, the account pays Stripe's fees, and there is no Stripe dashboard. Record the agreement version, date and jurisdiction version accepted by each account]]. Links will be shown at the point of acceptance:
- Stripe Connected Account Agreement: https://stripe.com/legal/connect-account
- Stripe Services Agreement: https://stripe.com/legal/ssa
- Stripe Privacy Center: https://stripe.com/privacy

3.2 The Stripe agreements are contracts between you and Stripe. We cannot change them or waive them. If they conflict with the Marketplace Terms about Stripe's own rights and services, the Stripe agreements control as between you and Stripe. We may suspend your payouts or listings if Stripe closes, restricts or disables your Connected Account.

3.3 You must comply with Stripe's rules at all times, including its list of prohibited and restricted businesses (https://stripe.com/legal/restricted-businesses) and the card network rules that Stripe passes on. A business or product that Stripe prohibits cannot be sold or provided through AfroTransact, even if our own policies would otherwise allow it. Stripe may change its lists; the current list applies.

3.4 Stripe may change its agreements and services. We are not liable for those changes, but we will tell you of changes to the Stripe requirements that we learn about and that affect you.

## 4. Verification (KYC / KYB) and information

4.1 You must complete Stripe's identity and business verification before payouts are enabled. Stripe collects and verifies the information it needs (for example the legal name, tax identification number, date of birth, address, beneficial owners, and your payout bank account). You may be asked to supply more at any time, including when your volume grows.

4.2 You must provide accurate, complete and up-to-date information to Stripe and to us, and respond by Stripe's deadlines. If verification items become past due, Stripe may disable your charges or payouts, and we will pause payouts and hide your listings until they are resolved.

4.3 Stripe collects the verification data and the payout bank account itself, through its own components in Seller Center or Provider Hub. AfroTransact does not store your full bank account number or full identity documents. We receive verification results, status flags and the last digits of your account for display and compliance. Tax identification numbers you give us during onboarding are stored encrypted and visible in masked form to reviewers.

4.4 We may also run our own checks (including sanctions and adverse-media screening, document review and the two-step review in the Seller Terms). Stripe's verification does not replace ours.

4.5 You must tell us and Stripe promptly if your ownership, control, business model or category changes.

## 5. Fees and who bears them

5.1 AfroTransact fees (plan fee, commission, connection fee, labels, handling, delivery) are described in the Seller Terms, the Service Provider Terms and the Seller Fees Summary and are configured by Staff.

5.2 Stripe's processing fees on each payment and Stripe's account and payout fees are charged under the Stripe agreements. As configured, your Connected Account bears Stripe's account and payout fees, and the processing cost of each payment is allocated to the lines it relates to and shown on your statement. The exact allocation is shown in Seller Center.

5.3 We may deduct our fees and amounts under section 8 from payments, transfers and payouts, from your Connected Account where Stripe permits, or invoice you.

5.4 Currency. Customers pay in US dollars. Payouts are made in the currency and to the bank accounts Stripe supports for your Connected Account. Stripe may charge conversion fees.

## 6. Settlement, hold window and payouts

6.1 Authorisation and capture. For products, the payment is captured per shipment when the package is collected or leaves for delivery. For services, see the Service Provider Terms.

6.2 Hold window. After delivery (or service completion) your net earnings for that order are held by AfroTransact for a hold window. The default is seven (7) days and Staff may configure it. The hold covers time for delivery problems, complaints, claims, refunds and chargebacks. [[LEGAL: confirm that the hold is not characterised as an unlawful retention or as custody of funds requiring a licence; confirm notice requirements for changing the period]]

6.3 Transfer. After the hold window, we transfer the net amount to your Connected Account through Stripe, after deducting commission, fees, refunds, chargebacks, label charges, plan fees collected from earnings (if you chose that option) and other amounts under section 8. Transfers may be made in several parts.

6.4 Payout. Your Connected Account is set to manual payouts. You request a payout from Seller Center or Provider Hub, subject to the minimum amount and frequency shown there (at the time of drafting, USD 25 and one request per day). Requests may be reviewed and approved. Approved payouts are sent by Stripe to your verified bank account and arrive according to Stripe's and your bank's timing.

6.5 Holds. We may delay, pause or hold payouts or transfers, in whole or in part: while verification items are due or past due; during a suspension; during a claim, help request, dispute or chargeback; if we suspect fraud, sanctions risk or a breach; on Stripe's request; and as required by law, a court or a regulator. A hold stays for as long as the reason exists, and we will tell you the reason when we lawfully can.

6.6 Reserves. We may require a reserve as set out in the Seller Terms. Stripe may also establish a reserve or delay payouts under the Stripe Services Agreement, in which case Stripe's reserve terms (which Stripe notifies to you) apply and we cannot override them.

6.7 No interest. No interest accrues on any funds we hold.

6.8 Incorrect or failed payouts. If a payout fails (for example because your bank details are wrong) it is returned to your balance, and you must correct your details. We are not liable for delays caused by Stripe, your bank or incorrect details.

6.9 Statements. Seller Center and Provider Hub show your balance, pending earnings, holds, transfers, payouts and deductions, per order, and statement exports. You must review them and tell us of errors within [[LEGAL: error-notification period, for example 60 days]].

## 7. Refunds

7.1 Refunds to customers follow the Marketplace Terms and the Returns & Refunds policy. When a refund is made, the original payment method is credited for the refunded amount (and a part may be returned as AfroTransact store credit where Staff decide so in a support resolution).

7.2 The refund is charged to the party responsible: to you when attributable to you, otherwise to AfroTransact. A refund reduces your earnings by the refunded item amount, adjusted by the commission or connection fee returned in proportion where the schedule says so. If the refund happens after we have transferred earnings, we may reverse the transfer, debit your Connected Account where Stripe permits, deduct from future earnings or reserves, or charge your payment method.

7.3 Delivery fees follow the fault rule: where the problem is attributable to the seller or provider, the customer can be refunded the delivery charge and AfroTransact keeps any seller-side delivery fee; where the customer changed their mind, the delivery charge is not refundable and the seller-side fee is returned in proportion. Details are in the Seller Fees Summary.

## 8. Chargebacks, disputes, negative balances and recovery

8.1 A chargeback or dispute is a reversal initiated by a customer's bank or card network. Stripe and the networks apply their own rules and deadlines. Where a dispute relates to your order or booking, you are responsible for it, for the dispute fee, and for fines or penalties, as provided in the Stripe agreements and the Seller Terms.

8.2 We may respond to a dispute on your behalf or ask you for evidence by a deadline. You must supply evidence (proof of delivery, tracking, communications, licences) on time. The bank or network decides. If a dispute is lost, the amount and fees are recovered from you.

8.3 Negative balance. If your balance with us is negative (for example because refunds or chargebacks exceed your earnings) you owe the shortfall. We may recover it by deducting from future earnings, from reserves, by debiting your Connected Account where Stripe permits, or by charging your payment method on file, and we may stop payouts until it is repaid. Under the configuration of your Connected Account, Stripe bears the risk of your Connected Account's negative balance as between Stripe and AfroTransact, but that does not release you from liability to Stripe under the Stripe agreements or to us under the Marketplace Terms. [[LEGAL: confirm how liability for negative balances is allocated between Stripe, AfroTransact and the seller and how to describe it]]

8.4 Excessive disputes, refunds or fraud may lead to additional reserves, delayed payouts or termination.

8.5 You must not fund, process or encourage transactions that you know to be unauthorised, card testing, laundering, or transaction laundering (processing payments for another business).

## 9. Taxes and reporting

9.1 Sales tax on marketplace sales is handled as in the Seller Terms. We and Stripe report payments to tax authorities where required. You must provide valid tax information. We may withhold payouts, or withhold tax, where tax information is missing or invalid.

9.2 Your payout statements are not tax advice. You are responsible for your own income taxes.

## 10. Sanctions, anti-money laundering and prohibited use

10.1 You represent that you, your owners and your Practitioners are not subject to sanctions and are not located in or doing business with embargoed jurisdictions in breach of law. You will not use the Marketplace or your Connected Account for any activity that would violate sanctions, export-control, anti-money-laundering or anti-corruption laws.

10.2 We and Stripe may screen you, block payments, freeze balances and report to authorities as required by law, without notice where the law forbids notice.

10.3 We may refuse, reverse or hold any transaction that we believe violates law, card network rules or these terms.

## 11. Data about payments

11.1 Customers' card numbers are collected by Stripe and are never stored by AfroTransact or shown to you. You receive only what you need to fulfil the order.

11.2 Stripe processes your data and customers' data as an independent controller for its own purposes (such as fraud prevention and legal compliance) and as our processor for others, under its own privacy policy and agreements. The Sub-processors list describes this. You must not attempt to obtain card data.

## 12. Records

12.1 We keep an immutable ledger of money movements and related records for at least the period required by law and by our policies [[LEGAL: confirm retention period; the Privacy Notice states 7 years for order records]]. You must keep your own records of sales, refunds, shipments, services, tax and payouts for at least seven (7) years or longer if the law requires, and provide them to us or Stripe on request.

12.2 You authorise us to share information about you and your transactions with Stripe, banks, card networks, tax authorities and regulators as needed.

## 13. Suspension, termination and survival

13.1 We may close or restrict your ability to receive payments at any time under the Marketplace Terms or if Stripe so requires. Closing your Connected Account does not remove obligations for past transactions.

13.2 After termination, remaining earnings are paid after the hold window and after deductions and any reserve under the Seller Terms.

13.3 Sections on fees, refunds, chargebacks, negative balances, taxes, records and liability survive.

## 14. Changes and contact

14.1 We may change these Payment Terms as set out in the Seller Terms, and sooner where a change is required by Stripe, a card network, law or a regulator.

14.2 Governing law and disputes are as in the Seller Terms: [[LEGAL: governing law and venue]]. Questions go to {legal}.
