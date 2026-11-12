# ADR 0008 — Guided tours: Driver.js (MIT) behind our own `TourEngine`

- **Status:** Accepted
- **Date:** 2026-11-12
- **Related:** `docs/engineering/guided-tours.md`; `web/packages/tour` (`@afrotransact/tour`); backlog stories on guided tours

## Context

The owner asked for an open-source alternative to Shepherd.js, and for a full set of tours that walk sellers, service providers
and staff through their screens. Shepherd.js and Intro.js were the usual choices, but both are copyleft with a paid commercial
licence for commercial use. We run a commercial marketplace, so the licence decides first and features second.

Every figure below was read on **2026-11-12** from the npm registry (`npm view`), from the published tarball (the `LICENSE`
file and the built files) and, for behaviour, from the built source of the version named. GitHub could not be reached from the
build environment (the session has no access to those repositories), so repository facts (stars, issue history) were **not**
used. Licences are those in the tarball, not from memory.

## Evidence

| | Driver.js | React Joyride | Shepherd.js | Intro.js | OnboardJS (core + react) | In-house on Floating UI |
|---|---|---|---|---|---|---|
| Version read | 1.12.1 | 3.2.0 | 16.0.0 | 8.6.0 | 1.0.0-rc.4 / rc.5 | `@floating-ui/react` 0.27.20 |
| `npm view license` | **MIT** | **MIT** | **AGPL-3.0** | **AGPL-3.0** | core MIT; react: **no licence field and no LICENSE file in the tarball** | MIT |
| Licence file in the tarball | MIT (Kamran Ahmed) | MIT (Gil Barbara) | `LICENSE.md`: "dual-licensed under **AGPL-3.0** (open source and non-commercial use) and a **Commercial License** (for commercial use)"; README: "Commercial license required for commercial products and revenue-generating companies" | README: "If you want to use Intro.js for a commercial application ... the commercial licence is the appropriate license" | none shipped | MIT |
| Last release | 2026-11-12 | 2026-07-09 | 2026-11-11 | 2026-09-21 | 2026-10-02 (rc) | 2026-07-11 |
| JS size (min / gzip) | 45 kB / **14 kB** (+ 1.9 kB CSS) | 85 kB / 20 kB (+ 10 dependencies incl. Floating UI) | 28 kB / 11 kB (+ Floating UI, theme CSS) | 74 kB / 20 kB | core 173 kB / 39 kB (flow engine, not a tour UI) | about 0 kB of tour code, we write it |
| Dependencies | none | 10 | Floating UI | none | `p-queue` | `tabbable`, Floating UI |
| React 19 / Next.js 16 | framework-agnostic: nothing to be incompatible with | peer `react 16.8 - 19`, `'use client'` bundle | wrapper `react-shepherd` 8.0.0 supports 19 | not React | peer 18 or 19 | peer 17 or newer |
| SSR / Cloudflare Workers | no `window` or `document` at import, only when run | needs a client-only boundary | touches `document` at run | touches `document` at run | n/a | ours |
| Keyboard | Esc, arrows (not direction aware), **Tab trap** (popover plus highlighted element) | Esc, **focus trap** option | Esc, arrows, focus trap option | arrows, Esc | n/a | ours |
| Screen readers | `aria-labelledby`/`describedby` on the popover, one `aria-live` region | `role=dialog`, `aria-modal`, `aria-live` | `aria-labelledby`/`describedby` | weak | n/a | ours |
| `prefers-reduced-motion` | CSS for nudges; `animate` / `smoothScroll` switchable in code | partial | CSS | no | n/a | ours |
| RTL | `margin-inline-start` in places; popover alignment is physical (left/right) | not built in | CSS `dir` support is partial | separate `introjs-rtl.css` | n/a | ours |
| Theming with our tokens | plain CSS classes and CSS variables, no inline colours except the overlay fill (overridable in CSS) | inline `styles` object | CSS file, overridable | CSS themes | n/a | ours |
| Open vulnerabilities | none known at the date read; `pnpm audit` on the lock after adding it: clean (see below) | none known | none known | none known | not audited (rc) | n/a |

## Decision

1. **We will not use Shepherd.js or Intro.js.** Both are AGPL-3.0 with a separate paid commercial licence. Linking either into a
   proprietary web app would need that licence (or publishing our app's source), and the owner asked for an open-source choice.
2. **We use Driver.js 1.12.1 (MIT)** as the spotlight and popover engine. It is the smallest (14 kB gzipped, no dependencies), the
   most recently released, framework-agnostic (one engine for Next.js apps today and any other front end later), safe to import on
   the server and on Cloudflare Workers (it touches the DOM only when a tour runs), and already has the hard accessibility parts
   we would otherwise write: a Tab trap between the popover and the highlighted element, a live region, `aria-labelledby` and
   `aria-describedby`.
3. **Driver.js sits behind our own `TourEngine` interface** (`web/packages/tour/src/engine.ts`). Our code never imports it outside
   `driver-engine.ts`. The engine draws one step and reports the buttons and keys used; *what* to show (steps, order, skipping,
   route changes, guards, progress) is our `TourService`. Swapping the library means writing one more `TourEngine`.
4. **What we do ourselves, on top of Driver.js**, because the library does not do it well enough for us:
   - the popover's contents and buttons (translated labels, Back/Next/Skip, "Step n of N", "Don't show again", a success mark);
   - keyboard handling: Esc, and arrows that follow the reading direction (ArrowLeft goes forward in Arabic); Driver.js's own
     keyboard control is switched off;
   - one `aria-live` region that announces the step, focus on the primary button, focus returned to where it was on close;
   - tokens-only styling, a bottom sheet under 640 px, reduced motion, forced-colors and `prefers-contrast` rules;
   - the overlay colour comes from the `--at-color-overlay` token by a CSS rule (Driver.js writes the fill as an attribute).
5. **React Joyride was the runner-up** (MIT, React-first API). It is larger, pulls ten dependencies, and its popover is rendered
   by React inside the library, which makes the "own interface" seam wider. It stays the fallback if Driver.js is ever abandoned.
6. **OnboardJS was rejected**: it models multi-step onboarding flows (state machine, persistence plug-ins), not spotlight tours; its
   React package has no licence information at all (no `license` field, no LICENSE file), which fails the permissive-licence rule.
7. **Building everything on Floating UI** was considered. It gives total control but means writing and then maintaining the focus
   trap, scroll-into-view, the overlay cut-out, resize and scroll tracking, and the cross-browser edge cases that Driver.js already
   has. With our own `TourEngine` seam this stays possible later (replace `driver-engine.ts`).

## Consequences

- One MIT dependency (`driver.js`) and Zod (already in the repo) are added; `pnpm audit` was run after adding them (result in the
  guided-tours doc and the PR).
- RTL: Driver.js positions the popover physically. We pass `side`/`align` from the reading direction (`start` and `end` placements
  swap in Arabic) and verified a mirrored popover stays inside the viewport (end-to-end test).
- Driver.js writes some inline styles (position, overlay). The apps' CSP already allows inline styles (`style-src 'unsafe-inline'`).
  Narrow screens use `!important` in one stylelint-disabled block to turn the popover into a bottom sheet.
- Progress is kept in `localStorage` per user, tour and version. A server copy is a documented port (`TourProgressRemote`) with no
  implementation; deciding whether to persist it server-side is an owner decision (see the guided-tours doc).
- Nothing is sent anywhere: no analytics, no user data in tours. End-to-end tests read an `at-tour` DOM event the service
  dispatches locally.

## Revisit when

- Driver.js changes licence, is archived, or a vulnerability is reported and not fixed within a release.
- We need tours inside the mobile apps (Expo): a different engine is needed there; the tour definitions (data) can be reused.
