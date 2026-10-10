# Accessibility: WCAG 2.2 AA checklist

Target: **WCAG 2.2 level AA** in the storefront, Seller Center / Provider Hub, staff console and both Expo apps
(story S194). The legal Accessibility Statement promises skip links, focus management, live regions, 44 px tap
targets and reduced motion. This page lists what every screen must do and how CI enforces it.

## 1. How it is enforced

| Check | Where | Fails CI on |
|---|---|---|
| axe-core (`wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, `wcag22aa`) on the key pages of all three web apps | `tests/e2e` (`pnpm e2e:a11y`, tests tagged `@a11y`) | any **serious** or **critical** violation |
| Keyboard and preference journeys: skip link, focus visible, keyboard scrolling, dialog focus trap, reduced motion, error summary, RTL | `tests/e2e/specs/storefront.a11y-keyboard.spec.ts`, `staff-console.journeys.spec.ts` | any regression |
| `eslint-plugin-jsx-a11y` (recommended rules) | `pnpm -r lint` (web apps and `@afrotransact/ui`) | lint errors |
| Storybook a11y addon | `web/packages/ui` stories | panel warnings, reviewed in PRs |
| Token contrast | `web/packages/tokens/test/tokens.test.ts` | text tokens drifting from the AA values below |

`tests/e2e/support/a11y-known-issues.ts` is **empty**: there is no allowlist. Adding an entry needs an owner and a
linked issue, and the entry is deleted in the PR that fixes it. Set `E2E_A11Y_SUMMARY=<file>.jsonl` to write every
violation (all impacts) of a run to a file for before/after reports.

Pages scanned: storefront home, search, product, cart, checkout, orders, order detail, account, addresses,
payments, security, returns, services, service detail, about, careers, help, 404, sign-in, sign-up and the home page
in Arabic (RTL); Seller Center home, orders, listings, new listing, inventory, storefront, payouts, billing, account
health, team, onboarding; Provider Hub calendar (week and month), requests, hours, services, policy, public page,
earnings; staff console dashboard, orders, moderation, support, people, payouts, audit log, exports, site content.

## 2. Checklist (web)

### Structure and names
- [ ] One `<h1>` per page; headings in order. Landmarks: `header`, `nav` (each with an `aria-label`), `main`, `footer`.
- [ ] `<html lang>` and `dir` follow the active locale (`ar` is `rtl`); text in another language carries `lang`.
- [ ] Every control has an accessible name: a visible `<label>`, or `aria-label` for icon-only buttons.
- [ ] Images: meaningful images have `alt`; decorative ones `alt=""` / `aria-hidden`. `ImagePlaceholder` with an
      empty `alt` is decorative (hidden), never an unnamed `role="img"`.
- [ ] Composite widgets use complete ARIA patterns: `grid > row > columnheader | rowheader | gridcell` (the Provider
      Hub calendar uses `display: contents` rows so cells stay in the CSS grid), `tablist > tab`, `radiogroup > radio`.
- [ ] Status changes are announced with `aria-live="polite"` (cart updates, toasts, search result counts) and errors
      with `role="alert"`.

### Keyboard and focus
- [ ] "Skip to main content" is the first Tab stop on every page and moves focus to `<main id="main" tabIndex={-1}>`.
- [ ] Everything works with the keyboard alone; the Tab order follows the visual order (no positive `tabIndex`).
- [ ] Focus is always visible: the global `:focus-visible` outline (2 px, `--at-color-focus`) is unlayered so
      components cannot remove it by accident. Never `outline: none` without a replacement.
- [ ] Focus is never hidden behind sticky headers (2.4.11): scroll targets use `scroll-margin-top`.
- [ ] Dialogs and sheets (`@afrotransact/ui` `Dialog`/`Sheet`, Radix) trap focus, close on Escape and return focus to
      the control that opened them.
- [ ] **No visible scrollbars, still keyboard-scrollable.** Scrollbars are hidden globally (design rule), but the page
      scrolls with Page Up/Down, Home/End and arrows. A scrollable region that contains no focusable control gets
      `tabIndex={0}`, a `role="region"` and an `aria-label` so keyboard users can reach and scroll it.

### Visual
- [ ] Text contrast ≥ 4.5:1 (≥ 3:1 for 18.66 px bold / 24 px text); icons and control borders ≥ 3:1. Use the tokens:

  | Token | Value | On white | Note |
  |---|---|---|---|
  | `--at-color-text` | ink-900 | 17.6:1 | body |
  | `--at-color-text-muted` | ink-600 | 7.0:1 | secondary text |
  | `--at-color-text-subtle` | **ink-550** | 5.9:1 | meta only; ≥ 4.9:1 on every light surface |
  | `--at-color-rating-text` | **gold-800** | 5.8:1 | rating value / counts; star glyphs stay gold |
  | `--at-color-link` | green-700 | 7.8:1 | links |

  ink-550 and gold-800 deliberately differ from the design file (ink-500 is 4.45:1, gold-500 text is 1.4:1); the
  overrides are listed in the tokens test. Never put `--at-gold-500` text on a light background.
- [ ] Links inside running text are underlined (not colour alone, 1.4.1): the base layer underlines class-less links
      in `p`, `li`, `label`, `small`, `dd`, `td`, `figcaption`.
- [ ] Element defaults (`a`, `body`…) live in the CSS `base` layer so Tailwind utilities and CSS modules override them.
- [ ] Pages reflow at 320 px without horizontal scrolling; text resizes to 200 % without loss.
- [ ] **Target size**: at least 24 × 24 CSS px for every pointer target (2.5.8, AA); primary actions and mobile
      targets 44 × 44 (our design standard). Carousel dots and calendar bookings are 24 px.

### Motion and time
- [ ] `prefers-reduced-motion: reduce` turns animations and transitions to ~0 ms globally and stops auto-advancing
      content (hero carousel).
- [ ] Anything that moves for more than 5 s has a pause control (the hero carousel's pause/play button), and pauses on
      hover and focus.

### Forms
- [ ] Every field has a visible label; required fields are marked; `autocomplete` is set for personal data.
- [ ] Errors are shown under the field (`aria-invalid`, `aria-describedby`) **and** listed at the top in an
      `ErrorSummary` (`@afrotransact/ui`) that takes focus after a failed submit and links to each field (3.3.1).
      Use `FormErrors` (storefront `components/common`, back offices `components/ui`).
- [ ] No time limits without a way to extend; no CAPTCHA-only steps; passkeys and paste are allowed (3.3.8).

### Language
- [ ] UI text comes from the message catalogues (S193); dates, numbers and currency use `Intl` for the active
      locale. Arabic switches to right-to-left.

## 3. Checklist (mobile, Expo)

- [ ] Every `Pressable` / touchable has `accessibilityRole` and `accessibilityLabel` (or visible text it inherits);
      state via `accessibilityState` (`selected`, `checked`, `disabled`, `busy`).
- [ ] Headings use `accessibilityRole="header"`; banners and errors `accessibilityRole="alert"`; toasts and field
      errors `accessibilityLiveRegion="polite"`.
- [ ] **Dynamic Type / font scaling**: `AppText` keeps `allowFontScaling` and caps at `MAX_FONT_SCALE` (2×).
      Controls use `minHeight` (never a fixed `height`) so they grow with the text.
- [ ] Touch targets ≥ 44 × 44 pt (`t.size.touch`); icon buttons 44 pt; `hitSlop` only on top of that.
- [ ] Inputs have `accessibilityLabel`; errors and hints are exposed with `accessibilityHint`.
- [ ] The language picker (Account → Language, driver Profile → Language) is a `radiogroup` with each option in its
      own language (`accessibilityLanguage`). Arabic flips the layout to RTL on the next launch.
- [ ] Test with VoiceOver and TalkBack before each release; Expo has no automated axe equivalent.

## 4. Reviewing a change

1. Run `pnpm e2e:a11y` (or the full `pnpm e2e`) locally; fix every serious/critical finding.
2. Tab through the change: order, visible focus, Escape closes overlays, focus returns.
3. Zoom to 200 % and narrow to 320 px; switch to Arabic; turn on reduced motion.
4. For mobile, try the largest text size and a screen reader.
