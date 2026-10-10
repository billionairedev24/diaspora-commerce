# content-service

"Nothing promotional or legal is hard-coded." Placements (announcement bar, hero carousel, tiles, banners, product
rows, checkout cross-sell), Markets & locations, the Site content & legal configuration, legal documents and cookie
consent, feature flags (OpenFeature / OFREP), newsletter double opt-in, launch lists and careers.
Spring Boot 4.1 · Java 25 · PostgreSQL `content` · port **8112**. Architecture §19 of
`design/AfroTransact Architecture.dc.html`; design intent in `design/chats/chat1.md` / `chat2.md`; defaults from
`design/at-site.js`. Stories S126, S128, S129, S130, S191 (API), S192 (API).

## Model

| Table | What it holds |
|---|---|
| `slot` | Slot catalogue: key, max items per context, size spec, **payload JSON Schema (2020-12)** |
| `placement` | Payload (jsonb, validated against the slot schema), targeting, schedule, frequency cap, priority, paid/advertiser, workflow status, author / submitter / approver, `version` |
| `market_country`, `market_region` | **Draft** Markets & locations: every ISO 3166 country (status `LIVE · PAUSED · COMING_SOON · PLANNED`, launch note) and states/provinces with switches `marketplace · services · food · pickup · taxRegistration` + ZIP/FSA prefixes. DB check: marketplace off ⇒ all off |
| `market_publication`, `market_*_published` | Immutable published versions; eligibility reads only the latest one |
| `launch_list_entry` | (email, country, audience) waiting for a launch |
| `site_config_revision` | Append-only site configuration revisions (jsonb document) |
| `legal_version` | Versions per document (`major.minor.patch`, effective date, `CURRENT`/`ARCHIVED`, counsel-review banner, markdown per locale); one CURRENT per document (partial unique index) |
| `cookie_category`, `cookie_definition`, `consent_record` | Consent banner categories (required, GPC opt-out), cookie table, stored decisions |
| `feature_flag` | Enabled, rollout % (two decimals), kill switch, targeting (markets, roles, allow-list), `version` |
| `newsletter_subscription` | `PENDING → CONFIRMED → UNSUBSCRIBED` |
| `job`, `job_application`, `career_reference_seq` | Roles and applications with `CAR-######` references |
| `audit_log` | Every back-office change (actor, action, entity, detail) |

Seeds (Flyway V2–V6): the slot schemas and the prototype's live placements; all ISO countries (PLANNED), the US with
50 states + DC (launch set TX, GA, NJ, NY, MD, DC, VA, PA, MA, IL, MN, CA, FL, NC live; food in the FC states;
pickup in TX/GA/NJ), Canada COMING_SOON (early access, 90 days 0 % commission), UK PLANNED; site config revision 1
(socials, contacts, address, payment chips, "AfroTransact Alerts", en/fr/es/ar with RTL); English first drafts of
all nine legal documents with archived predecessors (including the Twilio/CTIA SMS wording: program name,
frequency, rates, STOP/HELP, carriers not liable, no sharing of mobile information); cookie categories and table;
six launch flags; the ten open roles.

## Rules

- **Placements**: `DRAFT → IN_REVIEW → SCHEDULED → LIVE → ENDED` (reject → DRAFT with a note; only drafts are
  editable). Approving a **paid third-party creative** needs someone other than its author and submitter
  (`422 content.four-eyes-required`). Approval goes straight to LIVE when the window has started; a job (60 s)
  activates and ends placements on schedule. Resolution: servable now ∧ targeting (market, community, ZIP prefix or
  DMA, device, new/returning, category) ∧ frequency cap (`seen` counts) → priority desc → at most `maxItems` per slot.
  Never sensitive categories.
- **Markets**: edits change the draft; `POST /v1/markets/publications` makes a new version (409 when nothing
  changed). Marketplace off in a state turns everything off; enabling another capability while it is off is
  `422 content.marketplace-off`. Eligibility: unknown country → `unsupported-country`; non-LIVE →
  `country-not-live` (onboarding blocked, launch list offered for COMING_SOON/PLANNED); state from `state` or ZIP
  (they must agree); marketplace off → `region-disabled` (checkout must block delivery).
- **Site config**: social URL must match the network's profile pattern (bad link blocks saving), support phone in
  E.164, an email per purpose, address, app-store links, payment chips, SMS program name, IdP button order (enabled
  flags stay in identity), locales (English required). Saves need `expectedRevision` (stale → 409), create a new
  revision, are audited and emit `content.site-config.changed`.
- **Legal**: raising a version requires a higher number, an effective date not before the current one and the
  English body; the old version is ARCHIVED and stays retrievable. `{phone}`, `{privacy}`, `{legal}`,
  `{accessibility}`, `{address}`, `{smsProgramName}` are filled from the live site config when served. Other locales
  fall back to the binding English text (`translationFallback: true`).
- **Consent**: required categories forced on; `gpcOptOut` categories forced off when `gpc` or `Sec-GPC: 1`; the
  Cookie Notice version is stored with every record.
- **Flags**: kill switch → disabled → allow-list → market → role → rollout. Rollout bucket =
  murmur3_x86_32(`flag:subject`) mod 10 000, so a subject keeps its bucket and raising a rollout only adds subjects.
  Roles are trusted only from SERVICE callers; otherwise the caller's own token roles are used. Flags are cached per
  replica for `FLAGS_CACHE_TTL` (30 s): kill switches apply everywhere within 30 s.
- **Newsletter**: subscribe → PENDING + `content.newsletter.confirmation_requested` (signed HMAC confirm link,
  72 h, and unsubscribe link) → confirm → CONFIRMED. Re-subscribing a confirmed address is a no-op.
- **Careers**: applications validated (name, email, LinkedIn `/in/` URL or empty, work authorisation, résumé asset id
  + PDF/Word file name, consent) before a `CAR-######` reference is issued.

## API

RFC 9457 problems with `code` `content.*` (422 with `fieldErrors`). Contract: `contracts/openapi/content.yaml`.
Reference pages: `/swagger-ui.html`, `/docs`, `/redoc` (off in prod).

| Public (no token) | |
|---|---|
| `GET /v1/placements/resolve?market=&slot=&zip=&dma=&device=&returning=&communities=&category=&seen=` | Live placements for a context; ETag/304; `public, max-age=30` (`no-store` when `seen` is sent) |
| `GET /v1/placements/bundles/{market}` | Compiled per-market bundle for the CDN (`s-maxage=300`, `Cache-Tag: placements:US`) |
| `GET /v1/markets/eligibility?country=&state=&zip=` · `GET /v1/markets/countries[/{code}]` · `POST /v1/markets/launch-list` | Eligibility, pickers, launch lists |
| `GET /v1/site-config` | BFF `SiteConfig` shape; ETag + `X-Config-Revision` |
| `GET /v1/legal`, `/v1/legal/{type}?locale=`, `/v1/legal/{type}/versions[/{version}]` | Legal center |
| `GET /v1/consent/cookie-policy` · `POST /v1/consent/records` | Consent banner and records |
| `POST /v1/flags/evaluate` · `POST /ofrep/v1/evaluate/flags[/{key}]` | Map of flag → bool; OpenFeature OFREP (ETag on bulk) |
| `POST /v1/newsletter/subscriptions` · `/confirmations` · `/unsubscriptions` | Double opt-in |
| `GET /v1/careers/jobs?team=&location=` · `GET /v1/careers/jobs/{id}` · `POST /v1/careers/jobs/{id}/applications` | Careers |

Back office (JWT): `/v1/slots`, `/v1/placements[/{id}[/submit|/approve|/reject|/end]]`,
`/v1/markets/draft/countries[/{code}[/regions/{region}|/activate-all|/deactivate-all]]`, `/v1/markets/draft/changes`,
`/v1/markets/publications`, `PUT /v1/site-config`, `/v1/site-config/settings|revisions`,
`POST /v1/legal/{type}/versions`, `PATCH /v1/legal/{type}/current`, `/v1/consent/categories/{key}`,
`/v1/consent/cookies/{name}`, `/v1/flags[/{key}[/kill-switch]]`, `/v1/careers/admin/jobs`, `POST|PUT /v1/careers/jobs`,
`/v1/careers/applications`, `/v1/audit`.

Permissions (edit implies view): `content:placements.view|edit|approve`, `content:markets.view|edit`,
`content:site.view|edit`, `content:legal.view|edit`, `content:flags.view|edit`, `content:careers.view|edit`,
`content:audit.view`. Site content & legal: Super Admin all, Marketing edit, Ops / Support / Trust & Safety view.

## Events (outbox → Kafka `at.content.events`, `contracts/asyncapi/fragments/content.yaml`)

One topic (the pre-provisioned `content` aggregate); the key keeps per-entity order.

| Event | Key | Consumers |
|---|---|---|
| `content.placements.published` (purge paths + cache tags) | market or `*` | edge worker, gateway-bff |
| `content.markets.published` (newly live countries, disabled/enabled regions) | `markets` | gateway-bff, cart, orders, seller, notifications |
| `content.site-config.changed` (revision, changed sections) | `site-config` | gateway-bff → web/mobile refresh |
| `content.legal.version_published`, `content.legal.version_amended` | document key | gateway-bff, notifications, identity |
| `content.flag.changed` | flag key | OFREP providers (optional early refresh) |
| `content.newsletter.confirmation_requested`, `.subscribed`, `.unsubscribed` | subscription id | notifications |
| `content.launch-list.joined` | country | notifications |
| `content.career-application.received` | CAR reference | notifications, recruiting |

## Configuration

`application.yml` + `application-{local,dev,uat,prod}.yml`. Secrets only from env: `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `KAFKA_BOOTSTRAP`, `NEWSLETTER_TOKEN_KEY` (≥ 32 chars; the service refuses to start without it outside
`local`), `AUTH_ISSUER_URI` per env. Tunables (`at.content.*`): `flags.cache-ttl` (`FLAGS_CACHE_TTL`, 30s),
`markets.cache-ttl` (30s), `placements.cache-ttl` (10s), `placements.resolve-max-age`, `bundle-max-age`,
`bundle-shared-max-age`, `site.max-age`, `site.legal-base-href`, `newsletter.token-ttl` (72h),
`newsletter.confirm-url` / `unsubscribe-url` (per env), `jobs.enabled` (`CONTENT_JOBS_ENABLED`),
`jobs.placement-lifecycle-interval`, `public-post-paths` (anonymous POSTs).

## Run & test

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
cd java && ./mvnw -B -f services/content/pom.xml verify     # checkstyle, unit, ArchUnit, Testcontainers ITs
docker compose -f infra/local/docker-compose.yml up -d postgres kafka   # then:
java -jar services/content/target/content-service-0.1.0-SNAPSHOT.jar   # local profile, port 8112, auth off
tools/contracts/export_java_openapi.sh content content 8112
```

Helm: `deploy/helm` (library chart `at-service`); validate with
`helm dependency build java/services/content/deploy/helm && helm template x java/services/content/deploy/helm -f java/services/content/deploy/helm/values-prod.yaml | kubeconform -strict -ignore-missing-schemas`.
