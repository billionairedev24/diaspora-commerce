# Tax shadow mode: every configured provider next to our own rules

Service: `java/services/tax-returns`. Staff screen: Sales tax, **Shadow report**. Cutover procedure:
[tax-provider-cutover.md](../runbooks/tax-provider-cutover.md). Design: [category-tax-design.md](category-tax-design.md) section 8.

## What shadow mode does

The charging method (`RULES`, `STRIPE_TAX` or `AVALARA`, set in *Who calculates tax*) prices the order. In the background, for every real order
quote (a quote that carries an order reference; cart estimates only with `at.taxreturns.tax.shadow.cart-quotes`), the same lines are also sent to
**every provider that is set up and is not the one charging**, each with the code the provider code map holds for the line's outcome. Each answer is compared with
our rules engine's answer for the same line and stored in `tax_shadow_quote` (one row per provider per line). Nothing a provider says is ever charged,
and nothing a provider does can delay or fail a checkout (timeout `TAX_SHADOW_TIMEOUT`, at most 50 comparisons in flight).

Turning shadow mode on is still the *Shadow mode* choice in *Who calculates tax* (`PUT /v1/tax/settings`, `shadowProvider`): a non-empty value switches it on, and
from then on **all** configured non-charging providers are asked, not only the one named. (A plain on/off switch would be clearer; the contract keeps the
provider enum for now so existing clients keep working.) `TaxMethodSource.shadowProviders()` computes the list.

## What the report says for each line and provider

| State | Row kind / failure | Meaning |
|---|---|---|
| Answered | `MATCH`, `OUTCOME`, `RATE` (`BOTH` reserved) | the provider priced the line; tax, rate and the difference from ours are shown |
| No code mapped | `NO_CODE` | no (verified, when `TAX_REQUIRE_VERIFIED_CODES=true`) code of this provider exists for the line's outcome, so it was **not asked**. Never a $0.00 quote |
| Not configured | `PROVIDER_ERROR` + `NOT_CONFIGURED` | credentials missing, so it was not asked. The text names the provider and the variables to set |
| Timed out | `PROVIDER_ERROR` + `TIMEOUT` | the provider did not answer within `TAX_SHADOW_TIMEOUT` (default 4s) |
| Provider error | `PROVIDER_ERROR` + `ERROR` | asked and refused or failed: the provider's own error code and message, or a response that missed the line. For Avalara also: no ship-from address was given for the line, so it was **not asked** (see below) |
| No decision | `NO_DECISION` | our engine had no decision for the line (blocked), so there is nothing to compare |

The reason text always names the provider ("Avalara did not answer within PT4S", "Stripe Tax is not configured ..."). The staff console shows our quote, then one column per provider,
with a state pill and the reason when there is no quote, plus a card per provider (configured, charging or asked, and its tallies). A provider that is not configured has no rows at all; its column
says so for every line. Search (order number, category, outcome code, ATIN, line id) and the *Show* filter (differs, matches, did not answer, no code) are applied by the BFF
(`GET /v1/staff/tax/shadow?status=&search=&page=&size=`), which reads up to 2000 recorded rows of the period. Each answered cell also says how exactly the ship-to was known (`addressPrecision`): when only a ZIP or city was sent it reads "Estimated from the ZIP code: no street address was sent to <provider>", so a rate difference is not mistaken for the provider being wrong.

## Why a line could show "The provider did not answer" before this change

1. Only one provider was ever asked (the one named in the shadow setting); the other had no data.
2. The Avalara adapter was a skeleton: configured but always failing with "not implemented", so every Avalara row was a provider error. (It was then a hand-rolled HTTP client that sent only the ship-to; it now uses Avalara's SDK and sends both addresses.)
3. A missing mapped code (the Avalara column of the code map starts empty) was recorded as the same kind of row as a real outage, with no provider named.
4. For Stripe Tax: a non-US ship-to, a timeout, an HTTP error or a response without the line each gave the same generic text.

## The Avalara adapter (`AvalaraTaxCalculator`, on Avalara's official Java SDK)

The adapter calls AvaTax REST v2 through `net.avalara.avatax:avatax-rest-v2-api-java_2.11` (`AvaTaxClient`), wrapped by one class (`SdkAvaTaxGateway`) behind an
interface (`AvaTaxGateway`) so the SDK appears in one place. Port and statuses are unchanged (`ProviderTaxCalculator`: Answered, Not configured, Timed out, Provider error,
No code mapped).

### What is sent

A `CreateTransactionModel` with `type: SalesOrder` and `commit: false` (AvaTax prices it and records nothing), `companyCode`, `date` (today), `customerCode`, `currencyCode`, and:

* **`addresses.shipTo`**: `line1` (the delivery street, when the caller has it), `city`, `region` (state), `country`, `postalCode`. Only what is known is sent: a ZIP-only
  ship-to sends no street and no city. A street is never invented. The row records the precision (`ADDRESS`, `CITY`, `ZIP`).
* **`addresses.shipFrom`**: the origin of the line (a seller's ship-from location, or the AfroTransact fulfilment centre that holds the stock). When every line ships from the same
  place the document carries both addresses; when lines ship from different places each line carries both ends (line addresses override the document's).
* **`lines[]`**: `number`, `quantity`, `amount` (price x quantity less discount), `itemCode` (the variant ATIN), `description`, `taxCode` (from the provider code map for the line's outcome),
  `taxIncluded: false`; plus a separate shipping line (`AVALARA_SHIPPING_TAX_CODE`, default `FR020100`) when the line carries a delivery charge. Its tax is folded into the item line.

Nothing is guessed: **a line without an Avalara code is not sent, and a line without a ship-from address is not sent** (state, ZIP and a country are the minimum). Strict (shadow) calls refuse the
whole call with a plain reason ("No Avalara code for lines [...]", "Avalara prices by origin and destination, and no ship-from address was given for lines [...]"); a charging quote
(`method = AVALARA`) falls back visibly to our rules (`RULES-FALLBACK`, `why` = `no-code`, `no-ship-from`, `not-configured`, `unavailable`).
Decision: **`ResolveAddress` is not called.** The tax call resolves the address itself and a separate validation call would add latency and cost per order without a documented accuracy gain; revisit if the sandbox reports unresolved addresses.

### Where the ship-from comes from

The quote request (`contracts/openapi/tax-returns.yaml`) now carries an optional `shipFrom` per line (`TaxShipFrom`: street, city, state, ZIP, country, `source` `SELLER_LOCATION | FULFILMENT_CENTRE`) and an optional `shipTo.line1`.
orders-service fills them at checkout (`HttpShipFromAddresses`, best effort, never fails checkout):

| Shipment | Ship-from read from |
|---|---|
| AfroTransact-held stock (FBA, Retail 1P), the node the stock is reserved at | inventory `GET /v1/nodes/{id}/address` (the centre's street address) |
| AFROTRANSACT_DELIVERY (an offer with a pickup location) | seller `GET /v1/ship-from-locations/{id}` |
| Seller-fulfilled | seller `GET /v1/orgs/{orgId}/default-ship-from-location` (new; service scope `sellers:ship-from.read`): the seller's default ship-from address |

The gateway-bff cart and checkout *estimates* do not send a ship-from (they are not compared in shadow mode, and the rules engine does not use it). Orders already placed before this change carry no
ship-from in `tax_shadow_quote`; only new quotes do. Requirement for useful rows: every seller has a default ship-from address (Seller center, Ship-from addresses) and every fulfilment centre node has its street address set.

### Response and failures

Tax and the sum of the jurisdiction rates per line. A refusal carries AvaTax's own error code and message (never the request, never the license key): `AuthenticationException` for wrong credentials, a message
about a company code for a wrong `AVALARA_COMPANY_CODE`. A slow answer is a timeout (5 s per request for connect, connection lease and read; shadow also stops waiting after `TAX_SHADOW_TIMEOUT`); an unreadable answer (an error page, a 5xx) is a provider error.
The SDK logs one line per call with the URL and the status (no credentials).

### Test connection

Staff console, Sales tax, Who calculates tax, Provider codes section: **Test connection** (`POST /v1/staff/tax/providers/AVALARA/connection-test`, needs `tax:configure`) runs the SDK's `Ping`
and shows *authenticated yes/no*, the account name Avalara reports and the environment. It never shows the license key. "Not set up" is shown when the variables are missing.

### Settings (names only; values live in the secret store, never in the repo)

| Variable | Meaning |
|---|---|
| `AVALARA_ACCOUNT_ID` | AvaTax account id (the SDK's basic-auth user) |
| `AVALARA_LICENSE_KEY` | AvaTax license key (secret) |
| `AVALARA_COMPANY_CODE` | company the transactions belong to in AvaTax |
| `AVALARA_ENVIRONMENT` | `sandbox` (default, `sandbox-rest.avatax.com`) or `production` (`rest.avatax.com`); anything else stops the service at start |
| `AVALARA_SHIPPING_TAX_CODE` | optional; AvaTax tax code for a separately stated delivery charge (default `FR020100`, see "Unverified" below) |
| `AVALARA_BASE_URL` | optional host override (tests, proxies); normally unset |
| `AVALARA_APP_NAME`, `AVALARA_APP_VERSION` | the application name and version the SDK sends in its client header (defaults `AfroTransact`, `2.0`) |

Avalara counts as configured when account id, license key and company code are set. Stripe Tax needs `STRIPE_SECRET_KEY`.

### SDK findings (checked 2026-11-14)

| | |
|---|---|
| Artifact | `net.avalara.avatax:avatax-rest-v2-api-java_2.11` (the `_2.11` is a Scala build suffix; the classes are plain Java). Source: [avadev/AvaTax-REST-V2-JRE-SDK](https://github.com/avadev/AvaTax-REST-V2-JRE-SDK) |
| Version | **26.9.0**, published to Maven Central 2026-09-16 (about 8 weeks old). 26.10.0 (2026-10-13), 26.10.1 and 26.10.2 (2026-11-03) are newer than the 4-week floor; bump after a soak. The release list is current: [maven-metadata.xml](https://repo1.maven.org/maven2/net/avalara/avatax/avatax-rest-v2-api-java_2.11/maven-metadata.xml) |
| Licence | Apache-2.0 (pom and repository) |
| Dependencies | `gson`, `httpclient` 4.x, `slf4j-api`, and `scala-library` 2.11.12, which the pom declares as compile scope but **no class in the jar references** (checked: no `scala/` reference in any class), so it is **excluded** in `platform-parent` `dependencyManagement`. Managed by the Boot BOM: gson 2.13.2, commons-logging 1.3.6, commons-codec 1.21.0, slf4j 2.0.20; pinned by us: httpclient 4.5.14 (last 4.5 release; httpcore 4.4.16) |
| Java 25 | Compiles and runs: the tax-returns unit tests (including the SDK against a local fake server) pass on JDK 25.0.5. The jar is plain Java bytecode (Java 8 target); gson maps the SDK's plain model classes (BigDecimal, Date, ArrayList, enums) without reflecting into JDK internals |
| Spring Boot 4 / Jackson 3 | No clash: the SDK does not use Jackson at all (gson). `mvn dependency:tree` shows no Jackson, no Spring and no Scala from it. Its HttpClient 4.x runs beside Spring's HttpClient 5 and the JDK client (different packages) |
| Maintenance | Releases track Avalara's monthly API releases (26.2.0 ... 26.10.2 in 2026). The README does not mark it deprecated. A different, OpenAPI-generated artifact (`io.github.avasachinbaijal:Avalara.SDK`, AvaTax v3) turned up in search under a personal namespace; I could not establish that it is Avalara's own package, so it was not used |
| Vulnerabilities | **Not machine-checked in this session**: OSV, deps.dev and the GitHub advisory API are blocked from the sandbox. By version: gson 2.13.2 and httpclient 4.5.14 are past the fixes I know of (gson < 2.8.9 CVE-2022-25647, httpclient < 4.5.13 CVE-2020-13956); scala-library is excluded. The `Security` workflow runs Trivy on every PR and OWASP Dependency-Check weekly over this reactor; treat their result on the pushing commit as the verdict |

### Verification status of the request shape (what I could and could not confirm)

The Avalara developer docs (`developer.avalara.com`) and the tax code lookup (`taxcode.avatax.avalara.com`) are outside this session's allowed network, so the shape was confirmed from the
SDK itself and from secondary sources, not from Avalara's published reference:

* Confirmed from the SDK 26.9.0 classes (`javap`): `CreateTransactionModel` has `type` (`DocumentType.SalesOrder`), `companyCode`, `date`, `customerCode`, `currencyCode`, `commit`, `addresses` (`AddressesModel`: `shipFrom`, `shipTo`, `singleLocation`, `pointOfOrderOrigin`; each `AddressLocationInfo`: `line1`, `city`, `region`, `country`, `postalCode`) and `lines` (`LineItemModel`: `number`, `quantity`, `amount`, `taxCode`, `itemCode`, `description`, `taxIncluded`, per-line `addresses`). `PingResultModel` has `authenticated` and `authenticatedUserName`. The README shows `client.ping()` and `getAuthenticated()`.
* Confirmed by test against a fake server: the JSON the SDK puts on the wire for all of the above, the Basic authorization header, the client header, and how the SDK reports a 401 (an `ErrorResult` with the code and message) and a non-JSON 5xx.
* **Not confirmed against Avalara's docs: the shipping tax code `FR020100`.** Secondary sources only: a state tax matrix lists it as "Shipping only, common carrier, FOB destination", and shopping-cart integrations use it as their default shipping code. Its taxability is state specific. Look it up in Avalara's tax code lookup before relying on it; `AVALARA_SHIPPING_TAX_CODE` overrides it without a release.
* **Not confirmed: what AvaTax does with a line-level `addresses` object when the document-level one is absent**, and whether it needs a registered company location when `shipFrom` is given. The sandbox run in the checklist below shows it: an error would come back as an `AvaTaxError` message on the report.

### Avalara sandbox checklist (for the owner)

1. Create an Avalara AvaTax **sandbox** account (free developer account at Avalara's developer site). Note the account id; create a license key in the AvaTax admin (Settings, License and API keys). Keep both out of chat and out of the repo.
2. In the sandbox company (AvaTax, Settings, Companies), note the **company code**, and add a **location** (the ship-from address, Settings, Locations) for the origin you will use; also make sure the company's
   nexus covers Texas (Settings, Nexus), otherwise AvaTax answers $0 tax and that shows up as an outcome difference.
3. In each seller's Seller center, make sure there is a default **ship-from address** (street, city, state, ZIP); set the street address of the fulfilment centre node in inventory. Without them the report says Avalara was not asked.
4. On the Mac that runs tax-returns, set the environment variables (names only): `AVALARA_ACCOUNT_ID`, `AVALARA_LICENSE_KEY`, `AVALARA_COMPANY_CODE`, `AVALARA_ENVIRONMENT=sandbox`, and optionally `AVALARA_APP_NAME`. Restart tax-returns.
5. Staff console, Sales tax, **Who calculates tax**, Provider codes: press **Test connection**. Expect "Avalara accepted the credentials", authenticated yes, your account name, environment sandbox. Anything else says what to fix.
6. Provider codes tab, provider Avalara, filter **Avalara code needed**: map and verify at least the codes of the products you will order (import/export CSV works per provider).
7. Who calculates tax: set **Shadow mode** to Avalara (keep the method on our own rules). Place a test order for a mapped product, shipped to a full Texas address.
8. Sales tax, **Shadow report**: find the order. The Avalara column shows the tax and the difference from ours, with "Estimated from the ZIP code" only when no street was sent. An error carries Avalara's own message.
9. Record the result (order number, our tax, Avalara's tax, any message) on story S451 before marking S448 done. No real Avalara call has been made yet.

## Known limits

* Tested against a fake AvaTax (JDK HttpServer) through the real SDK: request body (both addresses, `commit=false`, line codes), parsing, 401, 5xx, timeout, missing code, missing ship-from, ping. **It has not been run against a real Avalara sandbox.**
* Seller-fulfilled shipments need the seller's default ship-from address, fulfilment centres need their street address; without one the Avalara row says it was not asked. Orders already recorded have none.
* Two-decimal currencies only (USD at launch).
* The staff report recomputes its tallies per request (a few calls to tax-returns); the line table reads at most 2000 recorded rows of the period.
