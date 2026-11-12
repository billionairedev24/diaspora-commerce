# ADR 0009 — Guided-tour progress is kept on the server, per user

- **Status:** Accepted
- **Date:** 2026-11-12
- **Related:** ADR 0008 (library); `docs/engineering/guided-tours.md`; backlog S401, S403 to S406

## Context

The first version of the tours kept progress (finished, skipped, "don't show again") in the browser's `localStorage`, per user, tour and
version. People who changed browser or device saw first-time tours again, which is annoying. The owner's decision (final): progress is
saved server-side per user, and the browser keeps a cache only.

Requirements from the owner: works for sellers, providers and staff; authenticated only; a person reads and writes only their own;
idempotent upserts; small payload; the first load asks the server before anything starts by itself and a failed load never starts a tour;
"Don't show again" and completion persist across devices; a new content version shows a quiet "What's new" mark and never pops over work;
Replay always works; a reset in the hub; an optional switch to turn automatic tours off for everyone; one-time migration of what the
browser already has; low-cardinality counters; no personal data in logs.

## Options considered

| Option | For | Against |
|---|---|---|
| **A table in the identity service, behind the BFF's existing `/v1/account/*` routes** (chosen) | identity already owns the person's own settings (`user_setting`, notification switches) in the same database; the BFF already proxies `/v1/me/*` with the person's token for all three apps; no new service, database, queue or deployment | one more small table and use case in identity |
| A new `tour-service` | clean boundary | a service, a database, a chart, a pipeline and an Argo application for 5 columns; against the brief ("prefer the existing per-user preferences store")  |
| The seller service | knows organisations | staff are not sellers; progress belongs to the person, not to an organisation |
| A column or JSON blob on `user_setting` | no new table | a single row rewritten by every step; lost updates between tabs; no per-tour rules |
| Keep browser storage and add a cookie | trivial | does not cross devices; does not meet the brief |

## Decision

- **Owner: identity.** Table `user_tour_progress(user_id, tour_id, content_version, status, last_step, updated_at)`, primary key
  `(user_id, tour_id)`, `on delete cascade` from the account (Flyway `V44`). Hexagonal: `TourProgressService` (+ package-private impl),
  `TourProgressRepository` and `TourMetrics` ports, JDBC and Micrometer adapters, a controller. ArchUnit stays green.
- **Tour ids are namespaced by app** (`sc.` for Seller Center and Provider Hub, `st.` for the staff console) so one account that uses both never collides.
- **Statuses:** `IN_PROGRESS`, `COMPLETED`, `DISMISSED`; "not started" is no row. The version is the content version of the tour.
- **One rule on both sides:** a newer version replaces an older one, an older one is ignored, "in progress" never undoes a final status of the
  same version. Last write wins otherwise (an atomic `insert … on conflict do update`; the rows are tiny, so optimistic locking would add nothing).
  The first-sync merge only fills gaps and moves to newer versions.
- **API:** identity `GET|PUT|DELETE /v1/me/tour-progress`, `PUT|DELETE /v1/me/tour-progress/{tourId}`; the BFF exposes the same under `/v1/account/tour-progress`.
  The person comes from the token. At most 200 tours per person and 100 per merge.
- **Client:** `TourService.sync()` before any auto-start; fail quiet with back-off retries; the cache is `localStorage` (`at.tour.v2:…`), written first for
  instant render and offline, replaced by the server's copy after each load; unsent writes are marked dirty and sent at the next load; the first load copies
  the first version's keys up once.
- **Global switch:** `at.identity.tours.auto-start` (default on), returned with the progress as `autoStartEnabled`. Making it editable in the
  staff console is a follow-up (S406), because it needs a settings section and an audit entry.
- **Metrics:** `identity_tour_events_total{event}` for `started`, `completed`, `dismissed`, `reset`. No tour id and no user label, so cardinality is four.
- **Privacy:** only ids, a status and a time are stored or sent; nothing is logged about tours.

## Consequences

- A tour finished or dismissed on one device does not start on another. A reset in the hub deletes the person's rows (all back offices).
- A new content version is a "What's new" mark in the Tour hub and a dot on Help; it never starts by itself, so it cannot interrupt work.
- If identity or the BFF is down, no first-time tour starts by itself; the hub still works from the cache, and everything catches up when the server answers.
- Deleting an account deletes its rows (foreign key cascade).
- Revisit if tours ever need audience-wide analytics: the four counters are deliberately coarse, and per-tour completion would be a separate event stream, not this table.
