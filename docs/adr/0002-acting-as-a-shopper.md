# ADR 0002 — People shop as themselves: the `X-Acting-As: CUSTOMER` acting context

- **Status:** Accepted
- **Date:** 2026-10-12
- **Deciders:** platform / identity owners
- **Related:** `go/pkg/auth/acting.go`, `java/libs/platform-security` (`ActingAsActorResolver`),
  `go/services/gateway-bff` (`actingContext`, `port.WithShopper`), [ADR 0001](0001-openfga-relationship-authorisation.md)

## Context

Identity issues one access token per person. `DefaultAccessPolicy` gives a person who belongs to a seller or
provider organisation `pt=SELLER_MEMBER` / `PROVIDER_MEMBER` and `org=<active organisation>`. This holds for a pending
applicant's organisation too. gateway-bff uses one OIDC client for every web app, so the storefront relays that
same org-scoped token.

Services that serve only shoppers check the principal type: payments `PaymentsAccess.customer()`
(`payments.customer-only`), orders `OrdersAccess.customer()` (`orders.customer-only`), bookings, tax-returns, offers
coupons and trust-ops claims. Cart treats any non-`CUSTOMER` caller as a guest. A customer whose seller application
was approved therefore lost checkout ("Checkout is unavailable": `GET /v1/checkout/summary` → payments 403 →
`bff.forbidden`), their saved payment methods and their order history. Their cart also became a new guest cart on
every request.

## Decision

The client says which context a request runs in, and the resource servers apply it:

- **Header** `X-Acting-As: CUSTOMER`. go/pkg/auth `Middleware` (via `ActingAs`) and platform-security's
  auto-configured `ActorResolver` (`ActingAsActorResolver` wrapping `JwtActorResolver` or `LocalActorResolver`)
  apply it to a **person**: `CUSTOMER`, `SELLER_MEMBER`, `PROVIDER_MEMBER` or `DRIVER`. That person becomes a
  `CUSTOMER` with the same subject, no organisation, only their `account.*` roles, and the token's own
  permissions. Staff, services, partner apps and AI agents are never changed. Any other value is ignored.
- **gateway-bff** marks every route except `/v1/seller/**` and `/v1/staff/**` as a shopper request, and its upstream
  client (and the Lola stream) sends the header with the person's token. So storefront and customer-app routes
  (cart, checkout, account, orders, returns, bookings, pre-orders, Lola, `/v1/me`, sign-in cart merge) act as the
  person. Seller Center and Provider Hub routes relay the token as issued and act for the active organisation.
- **Relays on behalf of a shopper** send it too: orders → cart and identity at checkout, and ai-gateway's Lola tools.
  The customer app sends it on every authenticated call, because it calls bookings directly.

## Why this is safe

The header can only narrow a person to a shopper. Every account already is one (`account.customer`), the subject
is unchanged, and the organisation is dropped, so org-scoped permissions grant nothing. It cannot make anyone staff,
another person, or a member of an organisation. Staff and service principals ignore it.

## Alternatives considered

- **Separate customer and member tokens from identity** (another OIDC client per app, or token exchange). This is the
  cleanest token model, but it doubles sessions and refresh handling in gateway-bff, and single sign-on across apps
  relies on one client.
- **Accept any person on customer-only endpoints.** This works for single-purpose endpoints. It is ambiguous for
  endpoints that serve both sides (orders, returns), where the principal type selects the shopper view or the
  organisation view.

## Consequences

- New storefront routes act as the shopper by default. New back-office route families must be added to
  gateway-bff's `backOfficePrefixes`.
- A service that relays a shopper's token to another service must forward the acting context.
- mcp-server's tools relay the agent's user token without it (their caller context is not always a shopper). A
  dual-role person's MCP shopper tools still see the organisation context. That is follow-up work.
