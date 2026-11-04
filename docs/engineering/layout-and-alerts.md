# Layout and alerts

How back-office pages (Seller Center, staff console) and account pages show status messages, key figures and vertical
spacing. All of it lives in `@afrotransact/ui` and `@afrotransact/tokens`; do not hand-roll banners, boxed stat tiles or
per-page gaps. The look is flat: no cards, no shadows, a thin accent bar and tint instead.

## Alert

`Alert` (`web/packages/ui/src/feedback/Alert.tsx`) is the one alert. The apps' `Notice` components are thin adapters
over it (`tone` maps to `variant`; `icon="eye" | "shield"` swaps the glyph).

| Variant | Colour | Use for | Role |
|---|---|---|---|
| `info` | blue | tips, "read-only", what is happening, next steps | `status` |
| `success` | green | something finished or is in good standing | `status` |
| `warning` | amber | needs attention soon: low stock, fees change, missing info | `status` (`alert` with `urgent`) |
| `danger` | red | failures, blocked, payment failed, payouts paused | `alert` |
| `neutral` | grey | quiet context with no urgency | `status` |

- Parts: 4px accent bar on the start edge, status icon, optional bold `title` run in before the message, optional single
  action, optional dismiss (`onDismiss`, label with `dismissLabel`).
- Width: fits its content up to 720px (`--at-alert-max`). `fill` makes it full width; use it rarely.
- Action: `<AlertAction onClick>` (a button) or `<AlertAction href>` (a link), shown as an underlined link, never a pill.
  Any `<a>` or `<button>` passed as `action` is restyled the same way.
- Colours come from `--at-color-alert-<variant>-{bg,border,accent,icon,text}`. Text is 10:1 or better and icons 5:1 or
  better on the tint (WCAG AA, asserted in `web/packages/tokens/test/tokens.test.ts`).
- Roles: only `danger` and urgent warnings are assertive. Render an alert that appears after an action conditionally so
  it is announced; one that is on the page at load is read in reading order.
- Inline field errors and one-line form results stay `Message` / `Field` errors; `Alert` is for a block that stands alone.

Do:

```tsx
<Alert variant="warning" title="1 SKU is running low on stock."
  action={<AlertAction onClick={plan}>Create inbound plan</AlertAction>}>
  Send stock to AfroTransact to avoid stockouts.
</Alert>
```

Don't: stretch an alert across the page, use red for "heads up", put a pill button inside it, use `role="alert"` for
information, or stack more than two alerts above the content.

## StatStrip

`StatStrip` shows key figures as one flat row: small muted label, large tabular-number value, optional `sub` line
(`tone`: positive, attention, negative). Thin vertical rules separate stats; there are no borders around each one. Two
columns below 640px. Give a stat `onSelect` (or `href`) to make it a button (link), for example "Low-stock SKUs" fills the
table search. Don't use it for charts or for more than about six figures.

## Page rhythm

Spacing is set once, as tokens (`--at-page-gap-*`) and applied by shared components:

| Between | Token | Value |
|---|---|---|
| page header and tabs / first block | `--at-page-gap-header` (the `<main>` gap) | 24px |
| tab row and tab content | `--at-page-gap-tabs` (`Tabs` panel) | 24px |
| alert and the next alert or the stat strip | `--at-page-gap-alert` | 16px |
| blocks (stat strip, toolbar, table, sections) | `--at-page-gap-section` | 24px |
| toolbar and table | `--at-page-gap-toolbar` (defined; the data table sets its own gap of about this size) | 12px |

`ModulePage` wraps its body in `PageStack`, which applies the block and alert gaps. Inside a tab or any other
container put the blocks in a `PageStack` too. Order a page as: alerts, stat strip, toolbar and table. Do not add
`mt-*` or `gap-*` utilities between these parts.
