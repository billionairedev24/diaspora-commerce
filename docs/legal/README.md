# Customer-facing legal documents (drafts)

> **These are drafts prepared with AI assistance. They have NOT been reviewed by a lawyer and MUST be reviewed, edited and approved by qualified counsel licensed in the launch jurisdictions before any of them is published or relied on.** Nothing here is legal advice. Every document carries `draft: true` in its front matter so the app shows the "draft for counsel review" banner. Do not set `draft: false` until counsel has signed off.

## Files

Each document is `docs/legal/<key>.en.md`: YAML front matter (`key`, `title`, `version`, `effective`, `change_summary`, `applies_to`, `draft`), then `# Title` and `##` sections. The `##` headings become the page outline in the Legal Center, so keep them stable. Placeholders `{phone}`, `{privacy}`, `{legal}`, `{accessibility}`, `{address}` and `{smsProgramName}` are filled from site config when a document is served (same as the existing seed text). `[[LEGAL: Qnn ...]]` marks a decision for counsel; each is listed in `open-questions-for-counsel.md`. All placeholders must be resolved and removed before `draft` is set to `false`.

| Key | Title | Owner (proposed) | Notes |
|---|---|---|---|
| `conditions-of-use` | Conditions of Use | Legal / Product | Master terms. Arbitration, governing law and liability are placeholders |
| `privacy` | Privacy Notice | Legal / Privacy / Engineering | Must match the data map and the subprocessor page |
| `cookies` | Cookie Notice | Privacy / Web | Table mirrors `cookie_definition`; regenerate before launch |
| `privacy-choices` | Your Privacy Choices | Privacy | US state rights, GPC, opt-outs, appeals |
| `returns` | Returns, Refunds and Problems With Your Order | Support / Legal | Owner decision: no self-service return or refund after delivery |
| `sms-terms` | SMS and Notification Terms | Messaging / Legal | Must match the Twilio campaign registration |
| `accessibility` | Accessibility Statement | Web / Design | WCAG 2.2 AA; see `docs/engineering/accessibility.md` |
| `prohibited-items` | Prohibited and Restricted Items | Trust and Safety | Customer summary; seller policy is separate |
| `gift-card-terms` | Gift Card Terms | Payments / Legal | ADR 0006 |
| `store-credit-terms` | Store Credit Terms | Payments / Legal | ADR 0006; never convertible to cash |
| `membership-terms` | Plus Membership Terms | Payments / Legal | Auto-renewal disclosures; `docs/engineering/membership.md` |
| `referral-terms` | Referral Program Terms | Growth / Legal | `docs/engineering/referral-program.md` |
| `preorder-delivery-terms` | Pre-order and Delivery Run Terms | Operations / Legal | ADR 0005 |
| `community-guidelines` | Community Guidelines | Trust and Safety | Reviews, messaging, Lola; `docs/engineering/product-reviews.md` |
| `ip-policy` | Intellectual Property and Copyright Policy | Legal / Trust and Safety | Needs a registered DMCA agent |

Supporting files: `provider-terms-matrix.md` (third-party terms and where we meet them) and `open-questions-for-counsel.md` (every placeholder, with a recommendation).

Not covered here (another document set): seller and provider terms, payments and payout terms, the subprocessor list. The privacy notice points to the subprocessor page; keep them consistent.

## Relationship to the existing seeded text

`java/services/content/src/main/resources/db/migration/V5__seed_legal.sql` seeds short placeholder documents for `conditions-of-use`, `privacy`, `cookies`, `privacy-choices`, `returns`, `seller-terms`, `prohibited-items`, `sms-terms` and `accessibility`. These files are the proposed replacements (version 1.0) and the new keys (`gift-card-terms`, `store-credit-terms`, `membership-terms`, `referral-terms`, `preorder-delivery-terms`, `community-guidelines`, `ip-policy`). Wiring them into the content service, and the new `doc_type` values, is a code task and was not done here. The seeded `returns` v2.0 (30-day returns) contradicts the new owner decision and must be replaced.

## Review checklist

Before any document is published:

1. **Counsel review** of every document and every `[[LEGAL: ...]]` item; resolve and delete each placeholder.
2. **Provider terms:** a named person reads the live text of each provider in `provider-terms-matrix.md` (Stripe, Twilio, WhatsApp/Meta, CTIA, OpenRouter and model providers, Google Maps, Smarty, Resend, AWS, Cloudflare, carriers) and records the date. The first draft could not read the providers' own pages.
3. **Match the product:** the sign-up page, checkout, membership page, SMS consent box, cookie banner, Account > Help and gift card page say what the documents say. Check button labels and paths named in the documents (Account > Help, Account > Membership, Account > Notifications, Account > Privacy, Account > Credit, Account > Gift cards, Account > Refer a friend), because several are descriptions of intended screens.
4. **Numbers:** examples (gift card $10 to $500, Plus $9.99/$99 with 14-day trial and $25 free-delivery minimum, referral $10 / $30 / 30 days, SMS marketing cap of 4 a month, 7-year tax records, 7-day membership refund window) match the staff settings at launch, or the document points to the app.
5. **Messaging:** the SMS terms match the registered 10DLC or toll-free campaign (name, use cases, sample messages, opt-in screenshot); the quiet-hours promise matches the notifications configuration.
6. **Privacy:** data map, retention jobs, GPC handling, privacy request form, opt-out of sale/share, and the AI statements (OpenRouter settings) are real before the notice says they are.
7. **Auto-renewal:** consent record kept, confirmation email, cancellation path, reminders, and state-law check for every launch state; re-check the FTC click-to-cancel rule status.
8. **Registrations:** DMCA agent registered; Stripe, Twilio, Meta business accounts use the same entity name.
9. **Accessibility** of the documents themselves (headings, contrast, readable tables) and of the Legal Center page.
10. **Set** `effective` to a real date, bump `version` if text changes, and set `draft: false` only after sign-off. Archive the superseded version (the content service keeps history).
