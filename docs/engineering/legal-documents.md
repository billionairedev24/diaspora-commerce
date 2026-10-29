# Legal documents

How the written legal documents get from `docs/legal/` to the pages people read at `/legal/<key>`, and how acceptance of them is
recorded.

```
docs/legal/<key>.<locale>.md      written by legal/content           (source of truth for the text)
        |  python3 tools/legal/build_seed.py
        v
content-service  db/migration/V<n>__legal_documents_<hash>.sql        (generated, committed, applied by Flyway)
        |  GET /v1/legal, /v1/legal/{key}[/versions/{v}]
        v
gateway-bff  GET /v1/legal, /v1/legal/{key}?locale=&version=         (allow-list of 21 keys, cached 60 s)
        v
storefront  /legal (index) and /legal/<key>; seller-center and staff-console link to the same pages
```

Staff keep working in the staff console (Settings, Legal documents): raising a version, counsel sign-off (draft banner off),
effective dates and translations. The migration is how the *first written text* and later *repository-reviewed* releases get in.

## The documents

Twenty-one keys (`LegalDocumentType` in the content service, `LEGAL_DOCUMENT_KEYS` in `@afrotransact/api-client`, `LegalDocumentKeys`
in the gateway-bff). The legal center groups them:

| Group | Documents |
|---|---|
| Customers | `conditions-of-use`, `returns`, `preorder-delivery-terms`, `membership-terms`, `referral-terms` |
| Payments and money | `gift-card-terms`, `store-credit-terms`, `payments-terms`, `seller-fees` |
| Sellers and providers | `seller-terms`, `provider-terms`, `data-processing` |
| Privacy and cookies | `privacy`, `cookies`, `privacy-choices`, `sms-terms`, `subprocessors` |
| Policies | `community-guidelines`, `ip-policy`, `prohibited-items`, `accessibility` |

Every key is seeded (V5 for the first nine, V12 for the other twelve) so that a link to any of them renders from day one; the
loader replaces the seeded drafts. `python3 tools/legal/build_seed.py --list` shows the seeded version of each key and whether a
written file exists.

Short links redirect to the canonical key (`/legal/terms`, `/legal/privacy-notice`, `/legal/refunds`, `/legal/dpa`, `/legal/fees`,
`/legal/gift-cards` ...; the table is `LEGAL_DOCUMENT_ALIASES`). Add an alias there when a link is printed or sent somewhere.

## Writing a document

`docs/legal/<key>.en.md` is required (English is binding). Translations are `<key>.fr.md`, `.es.md`, `.ar.md`, `.yo.md`: until one
exists the page shows the English text under a notice that English is the binding version. Other files in the folder (`README.md`,
`open-questions-*.md`, matrices) are notes, not documents, and are ignored.

```markdown
---
key: gift-card-terms          # = the file name
title: Gift Card Terms
version: 1.1                  # major.minor[.patch]; translations carry the same version
effective: 2026-11-01         # the date it takes effect (shown on the page), or TBD-at-launch
change_summary: Adds the dormant balance rules
applies_to: Anyone who buys or redeems a gift card     # informational
draft: true                   # true = "Draft for legal counsel review - not yet in force" banner
---
# Gift Card Terms

## Scope
Text. Contact {legal} or {phone}.
```

* Front matter is simple `key: value` lines (a colon inside a value is fine; an indented line continues the previous value). It is not parsed
  as YAML. `title` and `applies_to` are informational: the page title comes from the content service and the app catalogues
  (`LegalDocumentType`, `legal.doc.<key>` in the five storefront locales, `LEGAL_DOCUMENT_TITLES`), so keep the document's title
  equal to the app's.
* `effective: TBD-at-launch` (or any non-date) is loaded as the launch date: `--launch-date YYYY-MM-DD` or `LEGAL_LAUNCH_DATE`, default
  2026-12-01. Change it once, regenerate, and every such document moves; staff can still amend a single date in the console.
* `[[LEGAL: Qnn ...]]` counsel markers are allowed while `draft: true` (they show in the text on purpose) and fail the build once a document
  says `draft: false`.
* `# Title` first, then `##` sections. Each `##` becomes a numbered section and an "On this page" entry, so do not number headings
  yourself ("1. Scope" is shown as "Scope").
* Supported Markdown: paragraphs, `###`/`####` sub-headings, `-` / `1.` lists with one nested level, pipe tables, `>` quotes, `---`,
  `**bold**`, `*italic*`, `` `code` ``, `[links](/legal/privacy)` (same-site paths, https, mailto, tel) and bare e-mail addresses.
  Raw HTML is never rendered.
* Placeholders filled from the site config when served (so contact details are never typed into a document): `{phone}`,
  `{phoneE164}`, `{address}`, `{smsProgramName}`, `{support}`, `{privacy}`, `{accessibility}`, `{legal}`, `{press}`,
  `{partners}`, `{careers}`. Any other `{token}` fails the build because it would be shown literally.
* The SMS program wording in `sms-terms` (program name, STOP/HELP, message and data rates, carriers) is the single source for
  every SMS consent line in the apps and in the sign-up page; keep those facts in that document.

## Version rules (the same as the staff console's "raise version")

The generated migration applies, per document:

1. an older CURRENT version is archived (stays retrievable at `/legal/<key>?version=<v>`);
2. the written version is inserted as CURRENT, unless that version or a newer one already exists;
3. a CURRENT version equal to the written one is refreshed in place **only while untouched** since a seed or this loader created it
   (`updated_at = created_at`): text, sign-off or translations edited in the staff console are never overwritten. To supersede
   staff edits, raise the version number;
4. a newer CURRENT version (raised in the staff console) is never touched.

The seeded texts of the first nine documents are placeholders with an invented history (V5 says Conditions of Use 3.2, with archived 3.1
and 3.0). A written version *lower* than a still-untouched seeded one (the first real Conditions of Use is 1.0) replaces the
placeholder and its history (`build_seed.py` prints a note per document). If the placeholder was edited in the staff console it is not
untouched and the written text is not loaded: raise the written version above it, or amend it in the console.
The loader does not publish `legal.version-published` events or audit rows (those come from the staff console action); a deploy
with a new migration changes `updated_at`, which refreshes the site-config fingerprint, so caches pick the change up within
the 60 s cache window.

## Generating and checking

```bash
python3 tools/legal/build_seed.py            # writes V<next>__legal_documents_<hash>.sql (nothing to do when docs/legal is empty)
python3 tools/legal/build_seed.py --check    # CI (.github/workflows/legal-documents.yml): fails when docs/legal changed but the migration did not
python3 tools/legal/build_seed.py --rewrite  # replace the newest generated migration (only before it has been deployed anywhere)
python3 -m unittest discover -s tools/legal -p 'test_*.py'   # loader tests; LEGAL_TEST_PG=postgresql://... also applies the SQL
```

The file name carries a hash of every document (text, version, date, banner, summary), so identical documents always produce the
identical name and content; `--check` fails when the hash differs, or when the file was edited by hand. Each migration contains
every document and is idempotent, so a later migration simply supersedes an earlier one. Do not edit a deployed migration; add a
new one by running the tool. Flyway versions are unique per service: the tool takes the next free number.
Python 3 only (no packages).

## Acceptance

* **Sign-up** (identity hosted page, mobile sign-in): links to `conditions-of-use` and `privacy`; the SMS opt-in names the program
  (identity's `smsProgram` setting, which must equal the content site config's `smsProgramName` that the `sms-terms` document is
  rendered with; both default to "AfroTransact Alerts") and links to `sms-terms`. Identity stores *when* the
  terms were accepted (`termsAcceptedAt`), not which version (follow-up: pass the version the page showed).
* **Sellers** accept `seller-terms`, `payments-terms` and `data-processing` in onboarding (providers: `provider-terms`,
  `payments-terms`, `data-processing`). The gateway-bff stamps the *current version of each document* into the compliance step
  (`agreements: [{key, version}]`) and seller-service records them with the time of acceptance; the review step and the
  application show what was accepted.
* **Checkout** links `conditions-of-use`, `privacy` and `returns`, and `preorder-delivery-terms` when the order holds a pre-order.
  Gift card purchase, membership and referral screens do not exist in the apps yet: when they are built they link
  `gift-card-terms`, `membership-terms` and `referral-terms` with `legalHref(key)`.
* **Cookie banner** links to `cookies`; **footer** links the legal center and the main documents.

Seller Center's agreement checkboxes link each document (new tab) and the review step lists what was accepted with its version;
the staff console's legal documents table lists the twenty-one documents in the legal center's order with group and state, and
"New version" accepts the draft banner and the fr/es/ar/yo translations. `@afrotransact/api-client` holds the shared constants
(`legalHref`, `LEGAL_GROUPS`, `LEGAL_DOCUMENT_TITLES`, `SELLER_AGREEMENTS`, `PROVIDER_AGREEMENTS`).
