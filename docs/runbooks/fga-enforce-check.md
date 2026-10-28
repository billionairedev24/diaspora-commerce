# Runbook: check OpenFGA enforcement end to end (S266)

Goal: with relationship checks on and enforcing, **no first-party service call is refused with `auth.relationship-denied`**, and real
users are still refused on other organisations' data. Passing this lets dev switch from shadow to enforce and closes S266.

Background: [ADR 0001](../adr/0001-openfga-relationship-authorisation.md), [authorisation guide](../engineering/authorisation.md),
model [`contracts/openfga/model.fga`](../../contracts/openfga/model.fga).

## 0. What S266 changed

`go/services/authz-sync` writes `client:<id> service platform:afrotransact` for every first-party service client in
`serviceClients` (`config/<env>.yaml`) on startup. The model derives service access from that tuple
(`platform#service`, `organization#platform_service`). Without it, a service-to-service call (orders → payments, BFF →
offers, …) has no relation and would be denied in enforce mode.

## 1. Prerequisites

`make up` starts the Java services with `SPRING_PROFILES_ACTIVE=local`, where **FGA is off** (`FGA_ENABLED` defaults
to `false`). To test it you must turn it on for the four services that check relations:

| Service | Config keys (`application.yml`) |
|---|---|
| catalog, offers, orders, payments | `at.security.fga.enabled` = `FGA_ENABLED`, `enforce` = `FGA_ENFORCE` (default `true`), `api-url` = `FGA_API_URL` (default `http://localhost:8091`) |

delivery-runs also calls `relationships.requireForOrg` (pick lists) through the shared library, so give it the same
variables.

1. Docker infra up (OpenFGA on **:8091** with its Postgres database, started by `make up`/`tools/dev/local.sh`).
2. `authz-sync` running (port 8210). It creates the store `afrotransact`, loads the model and writes tuples. Check
   `tools/dev/local.sh logs authz-sync` for the model deploy line and the service-client tuples.
3. Restart the four services with the flags, for example:

   ```bash
   FGA_ENABLED=true FGA_ENFORCE=true tools/dev/local.sh restart catalog offers orders payments delivery-runs
   ```

   (Export the variables in your shell first if `restart` does not pass the environment through.)

## 2. Check the tuples exist

Get the store id (`curl -s localhost:8091/stores`), then read the platform tuples:

```bash
curl -s localhost:8091/stores/$STORE/read -X POST -H 'content-type: application/json' \
  -d '{"tuple_key":{"object":"platform:afrotransact","relation":"service"}}' | jq '.tuples[].key.user'
```

Expect one `client:<id>` per entry in `go/services/authz-sync/config/dev.yaml → serviceClients` (gateway-bff,
seller-service, orders-service, cart-service, tax-returns-service, bookings-service, fulfilment-service,
payments-service, notifications-service, trust-ops-service, search-service, delivery-runs-service, media-service,
indexer-service, offers-service, catalog-service, retail-service, inventory-service).

**Important:** the `<id>` must equal the token subject the service actually uses. `FgaSubjectResolver` turns a `SERVICE`
principal into `client:<sub>`; the `sub` is the identity client id (see `java/services/identity/.../application-dev.yml`
client list). I compared the two lists: the 18 confidential `client_credentials` clients in identity's dev config match
`serviceClients` exactly. `customer-app`, `driver-app` and `mcp-client` are public clients that act as the signed-in user
(authorization code), so they are correctly not service tuples. Re-compare if you add a service client; a missing or
misspelled id means that service is denied.

Also read `organization:<org>` tuples for a seeded seller (owner, `platform`, `market`, `sells_products`).

## 3. Flows to exercise (each must succeed)

Run them through the apps or the BFF with seeded demo users. Each row names the code point that does the check.

| # | Flow | Calls | Check in code |
|---|---|---|---|
| 1 | Sign in as seller owner; open Seller Center products, create/edit a product, upload an image | BFF → catalog | `catalog/.../CatalogRelations`, `ProductServiceImpl`, `ProductDraftServiceImpl`, `ProductImportServiceImpl`, `ImageLibraryServiceImpl` |
| 2 | Seller sets price/stock on an offer; list offers | BFF → offers | `offers/.../OfferServiceImpl`, `OffersRelations` |
| 3 | Customer places an order (cart → checkout → pay) | BFF → orders → payments, orders → offers | `orders/.../OrdersAccess` (`CAN_VIEW`, `ORG_VIEW`), `payments/.../PaymentsAccess` (`SERVICE`, `PLATFORM_VIEW_ALL`) |
| 4 | Seller marks a shipment prepared / shipped; staff marks delivered | BFF → orders | `OrdersAccess`, shipment relations in the model |
| 5 | Payment capture, refund, payout view as finance; staff reconciliation | BFF → payments | `PaymentsAccess`, `PaymentsRelations` |
| 6 | Delivery-runs pick list as a seller; staff run list | BFF → delivery-runs | `delivery-runs/.../PickListAccess` |
| 7 | Product review (moderation, seller reply) | BFF → catalog | `ProductReviewServiceImpl`, `SellerReviewServiceImpl` |
| 8 | Background paths: Kafka consumers calling other services (order placed → payments, notifications, tax) | service → service | same classes with `SERVICE` principals |

## 4. Flows that must be refused

| Flow | Expected |
|---|---|
| Seller A's owner reads or edits one of seller B's offers/products/orders (forge the `org` claim in a test token if needed) | 403 `auth.relationship-denied` |
| Seller `catalog` role tries to request a payout / see payouts | 403 |
| Market-scoped staff outside their market | 403 |
| Revoke a seller's role in the identity admin console | access ends after authz-sync catches up (seconds) |

## 5. Where to look

- Denials: `grep -R "auth.relationship-denied" logs/` (also in the response `code` field, RFC 9457 body).
- Store unreachable: `auth.relationship-unavailable` (503): OpenFGA down or `FGA_API_URL` wrong.
- Missing tuple vs wrong model: ask OpenFGA directly:

  ```bash
  curl -s localhost:8091/stores/$STORE/check -X POST -H 'content-type: application/json' \
    -d '{"tuple_key":{"user":"client:orders-service","relation":"service","object":"platform:afrotransact"}}'
  ```

- Model rules without any service running: `fga model test --tests contracts/openfga/model.fga.yaml`.

## 6. Pass criteria

1. Every service client in `serviceClients` has the `service` tuple and matches the identity client ids.
2. All flows in section 3 work with `FGA_ENFORCE=true`, and the logs of catalog, offers, orders, payments and
   delivery-runs contain **no** `auth.relationship-denied` for a service principal.
3. The refusals in section 4 happen.

## 7. After it passes

1. Switch dev to enforce: in `java/services/{catalog,offers,orders,payments}/src/main/resources/application-dev.yml`
   change `enforce: ${FGA_ENFORCE:false}` to `${FGA_ENFORCE:true}` and delete the "shadow until authz-sync seeds
   service-client tuples" comment.
2. Set S266 to `Done` in `docs/backlog/backlog.csv`.
3. Keep `FGA_ENFORCE=false` for uat and prod until the same check has been run there (see
   [production readiness](production-readiness.md)).
