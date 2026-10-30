# Admin content management (Settings → Published pages)

Owner report: "All published pages from Settings in admin can't even be viewed. I want that to be previewable and editable so dev does
not need to do this if anything has to change. Admin can do it."

**Root cause of "page not found".** Staff resources returned a *relative* storefront path as the record `href` (`/help/shipping`,
`/legal/conditions-of-use`) and the console opened it on its own origin (`:3002`), where no such page exists. Every "view live" link is now
built by the BFF on the storefront origin (`PUBLIC_WEB_URL`, `StaffDeps.StorefrontURL`) as an absolute address and opens in a new tab
(`RecordLink.Storefront` in `go/services/gateway-bff`; the mock BFF reads `PUBLIC_WEB_URL` / `NEXT_PUBLIC_STOREFRONT_URL`). A link is dropped,
never left relative, when the origin is unknown.

## Audit: every public content type

Legend: Y = yes in the console, P = partly, N = no (a developer or a database change is needed).
"Before" is the state at the start of this work; "Now" is this branch.

| Public content | Where staff work | View live | Preview draft | Edit | Version history | Roll back | Who / when |
|---|---|---|---|---|---|---|---|
| Legal documents (21 keys, incl. SMS terms, cookie notice text, privacy choices, seller fees) | Before: Settings → Legal documents (sheet) | Before N (relative link, 404). Now Y | Before N. Now Y: published, draft, any version; language, width, draft banner, `[[LEGAL: …]]` markers | Before P (effective date, banner, raw "New version" textarea). Now Y: Markdown editor, translations, validation | Before P (versions table, no text). Now Y: versions with diff | Before N. Now Y: restore an old version as a new draft | Before P (version author in content only). Now Y: table, history, content audit log (reason, author, approver) |
| Help center pages (about, press, shipping, returns, selling, advertise…) | Before: Settings → Help center (sheet) | Before N (relative link). Now Y | Before N (only whole-page DRAFT = hidden). Now Y | Before P (raw sections textarea, saved live). Now Y: editor, draft, publish with a reason | Before N. Now Y: every revision (`help_article_revision`) with diff | Before N. Now Y: republish any revision | Before N. Now Y |
| Contacts, social links, SMS program name (footer, `{phone}`/`{legal}` tokens) | Settings → Contacts & social links | Index row links the storefront | N | Y (form, revision counter, "what changed") | P (revision number; content keeps `/v1/site-config/revisions`, not surfaced) | N | Y (updated by, at) |
| Maintenance notice | Settings → Maintenance mode | Index row | N | Y | N | N | P (audit) |
| Announcement bar, banners, tiles, ads | Marketing → Announcements, Placements | Index rows (home page) | N (workflow DRAFT → review → approve exists; no render) | Y | P (activity) | N | Y |
| Careers: open roles | Before: **no screen** (content API only). Now Settings → Careers | Now Y (`/careers`) | N | Now Y (create, edit, open, close; bullets one per line) | N | N | P (activity, content audit) |
| Cookie table (cookie notice, banner) | Before: **no screen**. Now Settings → Cookies | Now Y (`/legal/cookies`) | N | Now Y (add, edit, remove) | N | N | P (content audit) |
| Notification templates (email, SMS, push, WhatsApp) | Settings → Notifications (existing, S268) | n/a (not a page) | Y (rendered with sample data, SMS segments, test send) | Y (new version) | Y (versions with diff) | P (copy the old text into a new version) | Y |
| Privacy request texts | Cases only (Settings → Privacy requests); the public wording is the legal documents `privacy`, `privacy-choices` above | via legal row | via legal row | via legal row | via legal row | via legal row | Y |
| SMS terms / consent wording | Legal document `sms-terms`; program name in Contacts | via legal row | via legal row | via legal row | via legal row | via legal row | Y |
| About page, FAQ-style copy in `about.*` | **Developer only**: ships in the app catalogues (`about.*` in `storefront/src/i18n`) | Index row | N | N | N | N | n/a |
| Onboarding and application help text, consent checkbox labels, form labels | **Developer only**: app i18n catalogues (storefront, Seller Center). Longer guidance is help center pages | n/a | N | N | N | N | n/a |
| Business pages | Sellers edit their own (`docs/engineering/business-pages.md`) | n/a | n/a | seller | n/a | n/a | n/a |

Gaps left on purpose (listed in the index as "Open in…" or "Developer only", never hidden): in-console previews for site settings,
banners and the maintenance notice; version history and rollback for site settings, banners, careers and cookies; help pages in
translation (the content service stores help pages in English only); the About page and the app catalogues.

## What was built

* **Index** (`GET /v1/staff/content/pages`; Settings → Published pages and the first tab of Site content): every public page with its absolute
  live link, status, version, last updated, updated by (a name, never an id), a draft badge and Preview / Edit actions.
* **Page editor** (`/settings/pages/{legal|help}/{key}`): Preview | Edit | History.
  * Preview renders with the storefront's own components (`web/packages/content`: Markdown renderer, `LegalDocumentContent`, `HelpPageContent`, moved out of
    the storefront), for the published version, the saved draft or any past version, in en/fr/es/ar/yo (English text plus a notice when a translation is
    missing), at desktop / tablet / phone width (the shared CSS answers to the container width, so the preview is the page), with the draft banner and
    highlighted, counted `[[LEGAL: …]]` markers. The words around a page come from the storefront catalogues (a test keeps them equal).
  * Edit: toolbar (headings, bold, italic, lists, quote, link, table, link to a section), paste from Word / Docs / the web converted to Markdown, the outline,
    live checks (repeated section names, links that go nowhere, anchors with no section, unknown placeholders, counsel markers), per-language editing with
    "needs review" marks, a live preview beside the text, change summary, effective date, version, draft banner. A form with no change cannot be saved.
  * Publish needs a reason (5–500 characters). **Legal documents** also need the Approve level on Site content & legal (`siteCfg`) and a second person: whoever
    saved the draft last cannot publish it (content answers 403 `content.second-approver-required`; the BFF checks the level). A document published as final
    (draft banner off) cannot hold `[[LEGAL: …]]` markers. Help pages publish with a reason and no second approver.
  * History: versions with who, when, what changed and why; a line diff between any two; rollback = publish an old help revision as a new revision, or start a
    new legal draft from an old version (a second person publishes it).
* **Content service** (Flyway `V15__content_drafts.sql`): `legal_draft` (one per document, `row_version` optimistic locking), `help_article_draft`,
  `help_article_revision`, `help_article.updated_by`. Endpoints under `/v1/legal/{type}/draft|history|versions/{v}/source`, `/v1/legal/overview` and
  `/v1/help/articles/{slug}/draft|revisions`; every save, publication, restoration and discard writes `audit_log` rows (actor, action, reason, author, approver).
* **Careers and Cookies tables** reuse the generic staff resources (`careers-jobs`, `cookie-definitions`) over APIs that already existed.

## Permissions

| Action | Needs |
|---|---|
| See the index, preview, history | VIEW on Site content & legal |
| Save / discard a legal draft | `content:legal.edit` and EDIT |
| Publish / roll back a legal document | `content:legal.edit`, Approve (or Full) level, and not the draft's last editor |
| Save, publish, roll back a help page | `content:site.edit` and EDIT |
| Careers roles / cookies | `content:careers.edit` / `content:legal.edit` and EDIT |

By default only Super Admin holds `content:legal.edit`, so a legal change needs two people with that permission and the Approve level
(grant it in Users & roles). Marketing holds `content:site.edit` and edits help pages alone.

## Contracts and tests

`contracts/openapi/gateway-bff-staff.yaml` (tag Published pages, resource keys `careers-jobs`, `cookie-definitions`) and `contracts/openapi/content.yaml`.
Go: `TestStaffContentPages` exercises every operation of the tag against the contract; Java: `LegalAuthoringIT`, `HelpAuthoringIT`, `LegalDraftTest`,
`HelpDraftTest`; web: `web/packages/content`, `api-client` (`staff-content`), `staff-console` (`pages-edit`, `published-pages`), Playwright
`tests/e2e/specs/staff-console.published-pages.spec.ts` (needs a fresh mock BFF: its state lives in the server process). Design notes:
`docs/design-notes/admin-content/` (1440 and 390 px; refresh with `E2E_DESIGN_SHOTS=1`).
