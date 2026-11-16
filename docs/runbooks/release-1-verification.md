# Release 1: verification (core + go) and the owner's end-to-end checklist

Branch `release-1`. Written 2026-11-16 from a session with no Docker, no Kafka, no real Temporal server, no Stripe and no signed JWT, so everything
that needs them is in section 6 as **not verified**, and section 4 is the list of steps you run on a Mac with Docker to close those gaps.
Environment variable **names** only; values come from your shell or `.local/secrets.env`.

## 1. What was asked and the short answer

| Question | Answer | Evidence |
|---|---|---|
| Are all features intact (every route of every mounted module answers on core)? | Yes, with three contract entries that no build ever implemented and one routing hazard (section 3) | `tools/release/route_scan.py` (static, all 16 Java modules), `tools/release/route_probe.py` (live, 13 modules on a throw-away Postgres) |
| Does the UI work as it did with microservices? | The web, mobile and e2e trees are **byte-identical to `main`** (`git diff main -- web mobile tests` is empty), and the UI only talks to gateway-bff's paths, which core and `afrotransact-go` serve unchanged. The Playwright suite could **not be run here** (no `node_modules`, 1 GB free disk); it runs against in-process MSW mocks, so it cannot see Java or Go anyway. Real flows: section 4 | git |
| Is checkout transactional (one step fails, everything rolls back)? | Checkout is a **Temporal saga, not a database transaction, by design**; every module commits in its own schema. The compensation path was proven for every step through the in-process adapters: stock, coupon, delivery-run slot, PaymentIntent and the order all end up given back and the shopper gets the right failure code | `InProcessCheckoutSagaFailureTest` (22 tests), section 2 |

## 2. Automated verification (all green when written)

All run with `mvn -pl services/orders -am test -Dtest=<class>` (JDK 25, no Docker). Test classes are new; **no existing test was edited**.

| What | Class (orders unless noted) | Tests | Result |
|---|---|---|---|
| Saga failure paths: real `CheckoutWorkflowImpl` + real `CheckoutActivitiesImpl` on Temporal's in-JVM test environment (time skipping), real in-process adapters of orders over stateful stand-ins for the called modules' published APIs (stock ledger, PaymentIntents, coupon redemptions, orders' own persistence) | `application.InProcessCheckoutSagaFailureTest` | 22 | pass |
| Error semantics, in-process vs HTTP: the same callee failure through the HTTP adapter (real local HTTP server answering the status and RFC 9457 problem) and through the in-process adapter; outcomes (result or `UpstreamFailure` service/status/code/retryable/shortfall lines/message) must be equal. 14 callee failure classes x 28 operations of inventory, payments, coupons, tax, offer inventory, product facts, markets, member benefits, pickup locations, ship-from | `adapter.out.module.InProcessVersusHttpFailureParityTest` | 393 | pass after the fixes of section 7 |
| fulfilment -> orders/inventory: 404 as none, crash text not carried | `fulfilment ... InProcessFulfilmentFailureParityTest` | 3 | pass |
| Local-only checkout fault injection (section 4.5): fails the named step before or after it ran; the bean does not exist outside the `local` profile or while off | `application.LocalCheckoutFaultsTest` | 5 | pass |
| Whole unit and ArchUnit suites (no `*IT`) of orders, fulfilment, trust-ops, tax-returns and identity and of the libraries they build on, after all changes | `mvn -pl services/orders,services/fulfilment,services/trust-ops,services/tax-returns,services/identity -am test` | all | BUILD SUCCESS, 0 failures, 0 errors, 1 skipped |
| Existing: `CheckoutWorkflowTest` (scripted activities), the four `InProcess*` consumer tests, `ArchitectureTest` of orders, `TenantIsolationMatrixTest` of identity | existing | 17 + 20 + 2 + 1 | pass (identity's after the fix of section 7) |

Saga scenarios (one forced failure each, end state asserted): insufficient stock (code `orders.insufficient-stock` with the shortfall line, nothing held, no
intent, no order stored); inventory unavailable (retried 5 times, then `orders.upstream-unavailable`); inventory crash (same, and its text never reaches
the shopper); a transient inventory failure (retried away, checkout goes on); inventory rule violation (`orders.upstream-rejected`, no retry); tax
unavailable and a line with no definite tax decision (`orders.tax-line-blocked`), both **after the stock was held, which is given back**; payment
intent declined (402), payments unavailable (502, retried), an intent created whose answer was lost (the retry finds it, never a second one; when it
stays lost the compensation finds it by order id and cancels it); order store fails after the intent exists (intent cancelled, stock and coupon
released, order voided); coupon no longer applies (422) and offers unavailable while redeeming (no redemption left active); payment failed and
payment timeout (intent cancelled, stock, coupon released, order cancelled with the code); cancel answers 409 (already captured: the other compensations
still run); compensations that fail three times are retried until they work; the hold expired before the order could be placed (authorisation
cancelled, `orders.reservation-expired`); delivery-run slot full (`orders.run-full`, stock back, no slot to release) and a run slot given back when
payment fails.

**What these tests stand in for.** The called modules are stand-ins behind their published API interfaces (`ReservationApi`, `OrderPaymentsApi`,
`CheckoutOffersApi`, `TaxQuoteApi`, ...): orders cannot depend on another module's service (ArchUnit), and the real services need PostgreSQL via
Testcontainers (Docker). The callee side of the mapping (`InventoryFailures`, `PaymentsFailures`, ... versus the `*ErrorAdvice` classes) was
**reviewed, not test-compared**. Orders' own persistence (`OrderPlacementService`) is a stand-in, so "no order left active" is asserted on its
state map, not on rows in `orders.customer_order`: section 4 checks the rows.

## 3. Route diff (S4)

`python3 tools/release/route_scan.py` (static): each module's contract routes against the Spring mappings in its source, and routes two contracts share.
`python3 tools/release/route_probe.py --modules ...` (live): every contract METHOD + path sent to a booted core; "no mapping" means Spring's
`No static resource` 404 or a 405.

Booted: core jar (`-DnoAi`) on a throw-away PostgreSQL 16 (port 5433, since stopped and deleted) with a local Redis, local profile, security off, no Kafka,
`TEMPORAL_START_WORKERS=false`, modules identity, catalog, offers, inventory, orders, payments, seller, bookings, trust-ops, tax-returns, content, **retail
and delivery-runs enabled**; fulfilment off (no PostGIS here), ai-gateway and mcp-server off (not in the jar).

| Check | Result |
|---|---|
| Static, all 16 Java modules with a contract | every contract route has a mapping except 3 in seller (below) |
| Live on each module's old port (81xx), 13 modules, 1 082 routes | all answer except the same 3 seller routes |
| Live on the core port with no prefix | same |
| Live under `/modules/<name>/` | same (3 seller routes) |

The three seller entries (`GET` and `POST /v1/billing/stripe-sync`, `GET /v1/orgs/{orgId}/billing/invoices/{invoiceId}/pdf`) were in
`contracts/openapi/seller.yaml` and `java/services/seller/README.md` but no controller implemented them, on `main` either. **Resolved: dropped** (no
caller, and the Stripe Billing objects they describe were removed by the own-billing design); the invoice route that exists, `.../document`, is now in the
contract. See `docs/engineering/plans-and-billing.md` (section "Routes dropped"). After this change the seller contract has no unmapped route.
`contracts/openapi/orders.yaml` **did not parse as
YAML** (an unquoted colon in a response description; also broken on `main`): fixed, which also makes `route_diff.py` usable on it. Orders' contract
lists `POST /v1/orders`: it exists (an alias of `POST /v1/checkout`); the first scanner version missed the array mapping, now fixed.

Not live-probed: **fulfilment** (needs PostGIS), **ai-gateway** and **mcp-server** (static scan only: 13 and 1 routes, all mapped).

### The three routes two modules map

| Route | Modules | What core does | Verified live |
|---|---|---|---|
| `POST /v1/returns` | tax-returns (customer return request), inventory (restock) | module addressed by old port or `/modules/<name>`; on the core port with no prefix the **module mounted first: tax-returns** | port 8103 inventory, 8111 and 8120 tax-returns, both prefixes |
| `POST /v1/receipts` | inventory, retail (off by default) | no prefix: **inventory** | 8103 and 8120 inventory, 8107 and `/modules/retail` retail |
| `POST /v1/reconciliation/runs` | inventory, payments | no prefix: **inventory** | 8103 and 8120 ran inventory's reconciliation, 8105 and `/modules/payments` payments' |

**Hazard (decision, section 5):** gateway-bff's staff console calls `POST /v1/reconciliation/runs` on its *payments* upstream (`staff_resources_ledger.go`) and on
its *inventory* upstream. If both upstreams point at core with no prefix, the payments reconciliation button runs **inventory's** reconciliation. The
fix is configuration, not code: the payments upstream base URL must carry `/modules/payments` (`PAYMENTS_URL=http://<core>/modules/payments`). Customer
returns (`tax-returns` first) and retail receipts (retail is off) are not affected.

## 4. Owner checklist: real flows on a Mac with Docker

Preflight once: `make preflight`, then `make up` (release 1 stack: infra, `core` database, core on :8120, afrotransact-go on :8200, storefront :3000,
Seller Center :3010, staff console :3002; it seeds demo data and prints all links); `make status` must show core readiness UP with every module. For real card
decline you need Stripe **test** keys in `.local/secrets.env` (names `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, and
`stripe listen` as in `docs/engineering/local-development.md`). Without them payments uses a local stand-in that authorises every card (nothing can be
declined; use the Temporal UI route of 4.6).

Accounts (password `Test1234!Local`, all `@afrotransact.test`): `customer.houston` (Houston 77042), `seller.owner` (Mama Put Pantry), `staff.admin`,
`staff.tax`, `provider.owner` (Aminata's Braids), `driver`. Staff sign in with a two-step code (mail in Mailpit, :8025).

SQL helper used below (the database is `core`, one schema per module):
`docker compose -f infra/local/docker-compose.yml exec postgres psql -U postgres -d core -c "<sql>"`.

### 4.1 Browse, search, cart

1. Storefront http://localhost:3000, deliver-to 77042: home, a category, a product page open; images load (seeded photos).
2. Search a seeded product name (Meilisearch): results and facets appear. If empty: `make reindex-search`.
3. Add two different products to the cart as `customer.houston`, change a quantity, reload: the cart persists (afrotransact-go cart module).
   Expected in the gateway log: calls go to `http://...inproc` for cart and search (in-process), no connection errors.

### 4.2 Checkout with a card (the happy path)

1. Cart -> Checkout -> choose the saved address and AfroTransact Delivery -> pay with the labelled test card (4242 4242 4242 4242) -> order page "placed".
2. Rows: `select status from orders.customer_order order by created_at desc limit 1;` is `PLACED`;
   `select status, failure_code from orders.checkout_request order by created_at desc limit 1;`;
   `select status from payments.payment order by placed_at desc limit 1;` (authorised);
   `select sku, on_hand, reserved from inventory.inventory_position where sku in (<your SKUs>);` (reserved went up, then the hold is `COMMITTED` on shipping);
   `select status from inventory.reservation order by created_at desc limit 1;` is `HELD` until shipped.
3. Temporal UI http://localhost:8088: workflow `checkout-<orderId>` Completed.

### 4.3 Forced inventory failure (first step)

1. As `staff.admin` (staff console -> Inventory) set a seeded SKU's on-hand to **1** (or pick a SKU with little stock) and note `reserved`.
2. As the customer put **3** of it in the cart and check out.
3. Expected: the checkout answers 409 `orders.insufficient-stock` naming the SKU ("requested 3, available 1"); no PaymentIntent; the cart is untouched.
   Rows: `select status, failure_code from orders.checkout_request order by created_at desc limit 1;` is `FAILED`/`orders.insufficient-stock`;
   `select status from orders.customer_order order by created_at desc limit 1;` is **not** `PENDING_PAYMENT`/`PLACED` for this order (cancelled, or absent);
   `inventory_position.reserved` is exactly the value you noted; `select count(*) from payments.payment where order_id = '<orderId>'` is 0.

### 4.4 Forced payment failure (the intent exists, the stock is held, then payment fails)

Pick one:
* **Stripe test card** `4000 0000 0000 0002` (declined) with test keys and `stripe listen`.
* **Temporal UI (no Stripe)**: start a checkout and stop at the pay step; in http://localhost:8088 open workflow `checkout-<orderId>` -> Send a signal ->
  name `paymentFailed`, input `"Your card was declined"`.
* **Timeout**: restart core with `CHECKOUT_PAYMENT_TIMEOUT=1m` (`make restart SERVICE=core`) and do not pay.

Expected, within seconds (timeout: after 1 minute): failure `orders.payment-failed` (or `orders.payment-timeout`); `inventory.reservation` is `RELEASED` and
`inventory_position.reserved` is back to the noted value; `payments.payment` is `canceled`; with a coupon (`WELCOME10` if seeded, else any valid coupon):
`select status from offers.coupon_redemption where order_id = '<orderId>'` is `RELEASED`; the order is `CANCELLED` with the code. Temporal shows the compensation
activities (`ReleaseReservation`, `CancelPayment`, `Abandon`) Completed.

### 4.5 Forced failure of any other step (local-only fault injection)

Only in the `local` profile, default off, no production class touched (a Temporal worker interceptor, `config/LocalCheckoutFaults.java`; it does not exist under `dev`,
`uat` or `prod`, proven by `LocalCheckoutFaultsTest`). Set, then `make restart SERVICE=core` (restart re-reads your shell and `.local/secrets.env`):

| Variable | Meaning |
|---|---|
| `AT_ORDERS_FAULT_INJECTION_ENABLED=true` | the switch (default false) |
| `AT_ORDERS_FAULT_INJECTION_FAIL_BEFORE=CreatePayment` | activity names failed before they run: `Reserve`, `ReserveRunSlot`, `RedeemCoupon`, `CreatePayment`, `PersistPending`, `Place` (comma separated) |
| `AT_ORDERS_FAULT_INJECTION_FAIL_AFTER=CreatePayment` | failed after they ran (the work is done and the answer is lost) |
| `AT_ORDERS_FAULT_INJECTION_KIND=UNAVAILABLE` or `REJECTED` | retryable (default: 5 attempts, then `orders.upstream-unavailable`) or not (`orders.upstream-rejected`) |
| `AT_ORDERS_FAULT_INJECTION_TIMES=1` | how many calls fail (default all) |

Core logs a WARN line "CHECKOUT FAULT INJECTION IS ON" at start and one per injected failure. Suggested runs, each followed by the checks of 4.4 (stock back, intent
cancelled, coupon released, order cancelled, right code):
1. `FAIL_BEFORE=CreatePayment KIND=REJECTED` (payment intent creation fails after the stock is held).
2. `FAIL_AFTER=CreatePayment` (intent exists at payments, answer lost: `payments.payment` for the order must end `canceled`, never two rows).
3. `FAIL_BEFORE=PersistPending` (order creation fails after reservation and intent).
4. `FAIL_BEFORE=RedeemCoupon KIND=REJECTED` with a coupon in the cart.
5. `FAIL_BEFORE=Place` after a successful payment (placing the order fails: authorisation must be cancelled).
6. `FAIL_BEFORE=Reserve TIMES=2` (retried away: checkout succeeds).
Unset the variables and restart when done. **Not exercised by me against a real Temporal server** (the interceptor is proven on the in-JVM test environment; that
Temporal's Spring starter applies `WorkerInterceptor` beans to the checkout worker is its documented behaviour but unobserved here: if the log line of the first injected
failure does not appear, tell me).

### 4.6 Seller listing

`seller.owner` in Seller Center http://localhost:3010 -> Products -> add a listing for an existing catalog product (price, stock, pickup location). Expected: it saves
(catalog -> offers -> seller in-process calls), and the product page on the storefront shows the new offer after a minute (search reindex via Kafka/indexer);
an unapproved category shows the approval notice. Check `select status from offers.offer order by created_at desc limit 1;`.

### 4.7 Tax shadow

`staff.tax` or `staff.admin` -> staff console -> Fees & tax -> Sales tax -> "Who calculates tax": set a *Shadow mode* provider -> place a checkout (4.2) -> Sales tax ->
**Shadow report** shows the order's lines with our quote and a column for the provider (no keys: "not configured" with the variable names, never a $0.00 quote). Orders' tax step
calls tax-returns in-process: also check the checkout still shows the tax line. See `docs/engineering/tax-shadow-mode.md`.

### 4.8 Staff delivery proof

Place an order with AfroTransact Delivery (4.2) -> `staff.dispatch` -> staff console -> Delivery: take the delivery, go out, deliver with a photo (or the driver app :8082,
`driver@afrotransact.test`) -> the proof and audit trail show; the customer's order page shows Delivered with the proof. The step crosses fulfilment -> orders
in-process (`ShipmentApi`): fulfilment is the one module **I could not boot** (PostGIS), so this is its first run in core.

### 4.9 Store page

`seller.owner` -> Seller Center -> Storefront/Store page: edit and publish; open the public store page on the storefront as an anonymous visitor: 200 with the content
(the seller storefront read is a public permit on core's shared security chain). Repeat with security **on** (`AT_SECURITY_ENABLED=true`, not the local default) to check the
anonymous permit and a 401 on `GET /v1/offers`.

### 4.10 Booking

`customer.houston` -> storefront -> Services -> "Aminata's Braids" -> pick a slot -> pay the deposit -> booking appears under Orders/Bookings for the customer and in
`provider.owner`'s calendar. Payment goes bookings -> payments in-process (`BookingPaymentsApi`). Cancel it and check the refund shows.

### 4.11 Staff invitation

`staff.admin` -> staff console -> Settings -> Staff -> invite a work-domain email -> the invitation mail arrives in Mailpit :8025 -> open the link (`/invite#token=...`): the
page shows who it is for, set a password, sign in. Reuse the link: it says it is used. (Identity's matrix row duplication that blocked its tenant-isolation test is fixed.)

### 4.12 Close the gaps this session could not

| Gap | How |
|---|---|
| Testcontainers ITs | `make java-verify` |
| Kafka consumers and the outbox in core | `make up`, place an order, then `select count(*) from orders.platform_outbox where published_at is null` drains; consumer groups `orders-service`, `payments-service`... listed in Kafka UI :8090 |
| Real Temporal server | 4.2 to 4.5 above |
| Signed JWT across modules | sign in on the storefront and open an account page; with `AT_SECURITY_ENABLED=true` for core |
| Stripe | `stripe listen`, 4.4 with the declined card |
| fulfilment, ai-gateway, mcp-server live | 4.8; ask Lola in the storefront chat; connect an MCP client to :8114 `/mcp` |
| Playwright smoke and e2e | `pnpm install && pnpm --filter @afrotransact/e2e test` (mock BFF, also run by CI on `main`) |
| Route probe against full core | `python3 tools/release/route_probe.py --modules fulfilment,ai-gateway,mcp-server,identity,catalog,offers,inventory,orders,payments,seller,bookings,trust-ops,tax-returns,content` after `make up` (expects the 3 seller routes only) |

## 5. Deployment prerequisites found (uat/prod; none of this is a local problem)

The local profile serves the old ports `8100-8115` from core (`at.core.module-ports`); **no other profile does**, and the release-1 charts set no upstream URL and create no alias Services.
Statically read (`apps/core/deploy/helm`, `go/apps/afrotransact-go/deploy/helm`, `go/services/gateway-bff/config/prod.yaml`, `orders/.../application-prod.yml`):

1. **gateway-bff** calls `http://payments`, `http://orders`, `http://identity` ... (the per-service DNS names). Unless Services of those names select core's pods, every Go -> Java call fails.
2. **Remaining Java -> Java HTTP edges** use the same names, e.g. orders -> identity `IDENTITY_URL` (default `http://identity` in uat/prod). **Checkout resolves the shipping address through it
   (`HttpAddressBook`, `GET /v1/me/addresses` with the shopper's token, `CheckoutServiceImpl` line ~221): without a route to identity inside core, `POST /v1/checkout` fails in uat/prod.** Smallest
   fix: `IDENTITY_URL=http://localhost:${PORT}` on the core container (the route is core's own and unique). Same for trust-ops, ai-gateway and mcp-server edges (`CATALOG_URL`, `ORDERS_URL`, ...),
   and for orders/fulfilment -> delivery-runs only if delivery-runs is turned on.
3. The three duplicate routes need the module prefix on the callers that mean the non-first module: `PAYMENTS_URL=http://<core>/modules/payments` for gateway-bff (section 3).
4. Go callees stay HTTP from Java (`CART_URL`, `GEO_URL`, `PROMISE_URL`, `CARRIER_GATEWAY_URL`): they must resolve to the `afrotransact-go` Service (and `CART_URL` to its `/modules/cart` prefix: cart and
   search are mounted prefixed there).

The CI/Helm owner decides how (alias Services, `extraEnv`, or `at.core.module-ports` set outside the local profile); I changed none of the deploy files.

## 6. Not verified

No Docker (Testcontainers ITs, `*IT` classes of every module), no Kafka (consumers, outbox publishing, the payment events that feed `paymentAuthorized`/`paymentFailed` in production), no real Temporal server
(only the in-JVM test environment: serialisation of `ApplicationFailure` details, the worker/Spring wiring, the task-queue pollers), no Stripe, no signed JWT (the in-process actor is the `Actor` model,
not a token; identity issuing and other modules validating through core is unrun), fulfilment (PostGIS), ai-gateway, mcp-server (provider keys; not in the probed jar), Playwright (no `node_modules`, 1 GB free),
the callee side of the failure mapping as a test, orders' own persistence rows during a failure (section 4 checks them), load and the checkout SLO, metrics and traces of converted edges
(HTTP client metrics of those edges no longer exist), `at.core.module-ports` outside local, and everything in section 5.

## 7. Differences from microservice behaviour (explicit list)

| # | Difference | Severity | Fixed / decision |
|---|---|---|---|
| 1 | In-process calls skip the HTTP caches of the old clients (seller entitlements 60 s, selling status 10 s, ...): reads are fresher, the callee is hit on every call | Low | Accepted; cache in the adapter if a hot path shows load |
| 2 | **No timeouts**: HTTP clients gave up after 5 s (tax 2 s); an in-process call waits for the callee. The saga's 30 s activity timeout still bounds the step (Temporal retries), but the callee's thread keeps running and a hung callee holds a request thread. ADR 0011 promised "a bounded executor wait" | Medium | **Decision needed**: bounded wait per adapter (needs the caller principal and tracing context re-bound on the waiting thread) or accept the activity timeout |
| 3 | No `@Valid` / Bean Validation of controller DTOs and no `Idempotency-Key` header check: the caller passes the key as an argument and the service-level rules still run; input orders builds itself is trusted | Low | Accepted for service-to-service; revisit for the end-user edges when converted |
| 4 | No loopback: converted edges never touch a socket; the others still do locally (old ports) and have no target in uat/prod (section 5) | **High for uat/prod** | **Decision needed** (section 5) |
| 5 | End-user actor needed: orders -> identity address book and every mcp-server tool relay the shopper's/staff member's token, so they cannot become service-principal calls | Medium | Stays HTTP; needs an end-user in-process entry (an `Actor` carried across, not a service principal) |
| 6 | Controller-level security (filter chain, JWT scope on the route) is not applied to an in-process call; only the called service's own `PermissionChecker` runs, with the caller's registered scopes (12.7: tested per API method) | Low | Accepted; the per-method 403 tests are the guard |
| 7 | A callee's unexpected exception text was copied into `UpstreamFailure.message` and from there to the checkout failure the **shopper sees** (over HTTP that is a bare 5xx) | Medium | **Fixed** in orders, fulfilment, trust-ops, tax-returns (`ModuleUpstream`): "<service> did not answer", detail in the log. Test: `aCrashInsideInventory...` |
| 8 | A callee 404 was an error where the HTTP adapter read it as "none": payment of an order, offer inventory SKU, pickup location (orders), reservation of an order (fulfilment). Over HTTP the saga's `cancelPayment` would have fallen through; in-process it would have failed `orders.upstream-rejected` | Medium | **Fixed** in the four in-process adapters; covered by the 393-case matrix |
| 9 | HTTP client metrics and trace spans between modules disappear for converted edges (dashboards on upstream latency lose those series) | Low-Medium | Not fixed; add a Micrometer timer in `ModuleUpstream` if wanted |
| 10 | Route collisions: `POST /v1/returns`, `/v1/receipts`, `/v1/reconciliation/runs` go to the first mounted module without a prefix (section 3). `POST /v1/reconciliation/runs` from the staff payments screen runs inventory's | **High** (wrong action) if misconfigured | Disambiguated by port (local) or prefix; **config needed** for gateway-bff (section 5.3) |
| 11 | Checkout failure messages differ in wording for I/O failures ("... did not answer" without the exception tail) | Trivial | Accepted |
| 12 | Contract drift independent of release 1: 3 seller routes unimplemented; `orders.yaml` was invalid YAML | Low | YAML fixed; seller routes: decide implement or drop |
| 13 | Transactions: modules commit in separate schemas and separate transaction managers, so a caller's rollback never undoes a callee's commit, **as over HTTP**. Checkout relies on compensations, which are proven | None | By design |

## 8. `make smoke`: proof the running stack works (and re-seeding)

`make smoke` (`tools/smoke/release1.sh`, bash + curl + jq) runs against the stack `make up` started and prints one `PASS` / `FAIL` line per
check (`FAIL  <check>  ->  <URL> status <code>`), a summary and exits non-zero when anything failed. `make up` prints the hint at the end; it
is never run automatically. It is read-only except an add-to-cart and one checkout that stops at the payment step (the test customer
`customer.houston@afrotransact.test` gets an unpaid "awaiting payment" order; `SMOKE_CHECKOUT=0` skips it).

| Check | How |
|---|---|
| core readiness, every module UP | `:8120/actuator/health/readiness`, `/actuator/health` (no DOWN component); STACK=services: `:8100-8115` |
| afrotransact-go ready | `:8200/readyz` |
| products >= 40 (`SMOKE_MIN_PRODUCTS`), photo on every hit, files served, home rails have photos | `GET /v1/search`, `GET /v1/home` through the BFF, then a HEAD on three photo URLs |
| search `iru`, `rice` | `GET /v1/search?q=` |
| a product in every top-level department | category tree, then `GET /v1/search?department=` (`SMOKE_DEPT_SKIP`, default `Services`, for departments that are not products in search) |
| product page price, photo, seller; storefront `/p/<atin>`; a seller store `/b/<slug>` with products | `GET /v1/products/<atin>`, storefront pages |
| cart add and read, estimated tax for ZIP 77042 | guest cookie jar: `POST /v1/cart/items`, `GET /v1/cart`, `GET /v1/checkout/tax-estimate?zip=77042` |
| checkout reaches the payment-intent step | signs the test customer in like the BFF, `GET /v1/checkout/summary`, `POST /v1/checkout/orders`, expects a `paymentClientSecret` (local Stripe stand-in) |
| staff console and Seller Center login pages, Mailpit | 200 after redirects, `:8025/api/v1/info` |
| search index document count > 0 | Meilisearch `/stats` (or Elasticsearch `_cat/count`); on 0 it says to run `make reindex-search` |
| seeded orders in the staff order list | `GET /v1/admin/orders` on core (`ORDERS_URL`; `:8104` in STACK=services) |
| Prometheus targets for core and afrotransact-go UP | `:9090/api/v1/targets`: `:8101` (served by core; core's own `:8120` is not a scrape target) and `:8200` |

Addresses: `BFF_URL`, `CORE_URL`, `IDENTITY_URL`, `ORDERS_URL`, `STOREFRONT_URL`, `SELLER_CENTER_URL`, `STAFF_CONSOLE_URL`, `MAILPIT_URL`,
`MEILISEARCH_URL`, `PROMETHEUS_URL` override the defaults; the web ports follow `.local/web-ports`; `STACK=services` switches to the old
ports. `make smoke-test` runs the script against a fake stack (no stack needed). **Not run against a real stack when written**: the response
shapes come from `contracts/openapi/gateway-bff.yaml`; the first real run may show a check whose field name differs.

Re-seeding, wiping and the seed validation are in `release-1-local.md` ("Demo data"). Two defects found while preparing this section and
fixed: identity answered `403 identity.first-party-client-required` to every first-party sign-in in core on the local profile (the seeder's
order data and any BFF sign-in), and `make seed` run by hand used the pre-release-1 ports of media, geo, cart and search in STACK=monolith.

