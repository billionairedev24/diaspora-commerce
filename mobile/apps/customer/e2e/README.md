# End-to-end flows (Maestro)

Tap-through tests of the customer app on an emulator, simulator or phone. They run against the in-app mock backend
(`EXPO_PUBLIC_MOCKS=1`), so no services are needed and the data is always the same.

```bash
brew install maestro                              # or https://maestro.mobile.dev
pnpm --filter @afrotransact/customer-app android  # a development build with mocks: EXPO_PUBLIC_MOCKS=1 (profile development-mocks)
maestro test mobile/apps/customer/e2e             # every flow
maestro test mobile/apps/customer/e2e/02-checkout.yaml
```

`config.yaml` sets the app id (`com.afrotransact.customer.local`) and runs flows in file order. Every flow starts with
`_sign-in.yaml` (fresh install → mock sign-in → passcode `135790` → notifications off).

| Flow | What it covers |
|---|---|
| `01-browse` | Home shows products; Browse finds “shito”; the product page opens with Add to cart |
| `02-checkout` | Add to cart → cart → checkout → Place order → order confirmation |
| `03-orders-and-returns` | Orders list, order details, the return screen |
| `04-addresses` | Add an address natively; an unverifiable one asks “Fix it / Keep mine”; it appears in the list |
| `05-bookings` | Opens Book a pro and lists providers (extend with a provider’s name once the labels are final) |
| `06-account` | Payment methods show the saved card; sign out returns to sign-in |

These were written from the screens' labels and **have not been run yet** (they need an emulator). The API journeys
behind them run in CI as `test/flows.test.ts`. Against the real stack, `pnpm smoke` checks products and photos.
