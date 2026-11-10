# Seller catalog scope: sellers list only in the departments they chose

A seller that sells food should not see "Size charts", and should not be able to create fashion listings, however it
asks. Each seller organisation has a set of **departments** it may sell in. The catalog refuses anything else on the
server; Seller Center only hides what would be refused.

## Source of truth

| Question | Answer | Where |
|---|---|---|
| Which departments exist? | The department nodes (level `DEPARTMENT`) of the catalog's category tree. | catalog-service `category` table |
| How is a department named in other services? | By its **node `code`** (`groceries`, `home-art`), stable and unique. | `category.code`, `CategoryNode.code` |
| What may this organisation sell in? | The department codes recorded for it. | seller-service `org_department` (migration `V88`) |
| What does a department ask the seller to have on file? | Compliance data **on the department node**: `FOOD_PERMIT`, `BEAUTY_LABELLING`. | `category.compliance` (catalog baseline), returned as `compliance` on `GET /v1/categories` |

Nothing in code names a department. Onboarding offers the departments of the tree (`GET /v1/seller/departments`), the
wizard asks for the food permit exactly when a chosen department says `FOOD_PERMIT`, and the catalog guard works on node
codes and the tree. The old hard-coded `PRODUCT_CATEGORIES`, `FOOD_CATEGORIES` and `BEAUTY_CATEGORIES` lists are gone.

Onboarding step 7 (`catalog-compliance`) stores `departments` (department codes) instead of the old category keys.
When the application is finally approved, seller-service records those codes for the new organisation. There is no
compatibility layer: data is created from scratch (local only), so there is no legacy derivation, backfill or key
mapping.

An organisation with **no departments recorded cannot create anything that belongs to a department** until some are
recorded (`403 catalog.no-departments`: "Your store has no departments set up yet ...").

## Who is exempt

* **AfroTransact Retail** (the platform-owned org, `ListingOwnerKind.PLATFORM`; seller-service answers `exempt: true`).
* **Staff and service clients** (`PrincipalType.STAFF`, `SERVICE`), for example the seeder and trust-ops.
* **Service providers** (bookings) are not product sellers; their flows are unchanged.

## Server-side enforcement (catalog-service)

`SellerScopeGuard` (`application`) returns a `DepartmentScope` (`domain`) for a listing owner or an actor. It reads the
organisation's departments from seller-service through the `SellerScopes` port (adapter `HttpSellerScopes`:
`GET /v1/orgs/{orgId}/catalog-scope` with catalog's client-credentials token, scope `sellers:catalog-scope.read`, cached
for 15 s per organisation). The scope is then applied to every path that creates or changes something:

| Operation | Check |
|---|---|
| Product create and edit (form, bulk upload job, image library attach) | `ProductWriterImpl.placement`: the product-type node's department. Editing price, stock or text without changing the category does not re-check. |
| Product with a requested new category | the request's parent path department |
| Drafts (create, save) | the department of the saved category path |
| Bulk import rows and templates | rows naming a category of another department are refused in the row (`Your store is not set up to sell in X. Request the department ...`); the template lists only allowed categories; the job re-checks when it runs |
| Size charts: create, update, duplicate, suggestions, template, import (also dry runs) | the chart's product type must be used in an allowed department |
| Brand choices and "add a brand" | the category / product type asked for |
| Category requests (`POST /v1/category-requests`) | the parent path's department; a new category in another department goes through the department request instead |
| `GET /v1/categories?forSeller=true`, `GET /v1/product-types?forSeller=true` | the tree and schemas, filtered to the caller's departments (the unfiltered tree stays public for shoppers) |

Refusals are RFC 9457 problems with plain messages:

* `403 catalog.department-not-allowed`: "Your store is not set up to sell in Fashion. Choose "Need another department?
  Request it" under Listings in Seller Center, and AfroTransact will review it."
* `403 catalog.no-departments`: the organisation has none recorded.
* `503 catalog.scope-unavailable`: **fail closed.** When seller-service cannot be asked, non-exempt organisations are
  refused (a 503 is never cached, nothing is guessed). Exempt actors do not call seller-service at all.

Live products in a department the organisation no longer has stay live. Nothing special is done for them: editing
price, stock or text keeps working, changing their category is refused. Removing a department is a staff action
(below).

`GET /v1/seller/catalog-scope` (member token) is the one read model for Seller Center: the allowed departments (id,
code, name, `hasSizing`), whether any allowed department has a product type with the sizing trait, and the
organisation's department requests.

## Adding a department

* **Seller**: "Need another department? Request it" (under every category picker) calls
  `POST /v1/seller/department-requests` (BFF) -> `POST /v1/orgs/{orgId}/department-requests` (seller-service; owner,
  admin or catalog role). The answer lists what the department also needs (`needs`: a food permit, beauty labelling), taken
  from the department's compliance data and what the organisation already has on file. A second request for the same
  department while one is pending returns it.
* **Staff** (applications:approve; review queue needs applications:review): `GET /v1/department-requests?status=PENDING`,
  `POST /v1/department-requests/{id}/decision` `{approve, note}`: approving adds the department at once.
  `PUT /v1/orgs/{orgId}/catalog-scope/departments` `{departments, reason}` replaces an organisation's set (reason of at
  least 10 characters, department codes checked against the catalog).
  A rejection needs a note (3+ characters), which the seller reads. Both decisions are audited: seller-service publishes
  `seller.department_request.decided` and `seller.org.departments_changed` (who, what changed, why) on
  `at.seller-application.events` and trust-ops copies them into the audit log. `GET /v1/department-requests/{id}` reads one
  request. The decided queue lists the newest decisions first (at most 200 per status).
  The staff console has a screen for all of this, see below.
* **Demo data**: `POST /v1/orgs` (provisioning, local and dev only) accepts `departments` and adds them on every call.

## Staff console

Module **Department requests** (key `deptRequests`, Partners group, `/department-requests`). No new permission: its level
derives from the seller service's own (`applications:review` = VIEW, `applications:approve` = EDIT), so it is hidden
without them. The pending count is its nav badge (`GET /v1/staff/session`).

| BFF route (`gateway-bff-staff.yaml`, tag Department requests) | Does |
|---|---|
| `GET /v1/staff/department-requests` | Pending / Decided (`status=PENDING|DECIDED|APPROVED|REJECTED`), filters `department`, `organisation`, `from`, `to`, `sort`, `page`, `size`; joins organisation, department and people names |
| `GET /v1/staff/department-requests/{id}` | One request: compliance prompts with `satisfied` (from the request's `needs`; null for a rejected one), the organisation's current departments by name |
| `POST .../{id}/approve` `{note?}`, `POST .../{id}/reject` `{reason}` | Decide (reason required, 3-500 characters); a request already decided is `409 ALREADY_DONE` |
| `GET/PUT /v1/staff/organisations/{orgId}/departments` | An organisation's departments and the catalog to choose from; the override needs a reason (10+), a real change and catalog codes |

The decision is one person's (the application review's two-step maker-checker is not used for department decisions in
seller-service). The Organisations sheet shows a **Departments** section with **Edit** (multi-select, required reason,
Save disabled when unchanged). Screens show names only; department codes travel in the API but are never printed.

## Seller Center

`useSellerScope()` (`lib/seller-scope.ts`, TanStack Query key `sellerKeys.scope(orgId)`) feeds everything:

* The product form, bulk upload and its template list, the size chart editor, list and import show only the allowed
  departments (the BFF's `GET /v1/seller/catalog/categories` is the filtered tree, cached per organisation).
* The sidebar shows **Size charts** only when `hasSizing` (or the org is exempt). While the scope loads the entry is
  not drawn; if the scope cannot be read it stays (the server decides).
* A direct URL to `/listings/size-charts` (or its import page) for a store without sized goods shows a calm explanation
  with the request action instead of an empty editor.
* Onboarding offers the catalog's departments; the food permit and beauty labelling prompts follow the departments'
  compliance data.

Audit of the other sidebar entries: Orders, Returns, Disputes, Listings, Reviews, Inventory, Coupons, Payouts, Fees,
Billing, Account health and Team are not category-specific. The food permit and beauty labelling live in onboarding
(and in a department request's `needs`), not in the sidebar. Size charts was the only category-specific entry.

## Operating it

* **Add compliance to a department**: set `category.compliance` (comma separated: `FOOD_PERMIT`, `BEAUTY_LABELLING`) with
  a catalog migration. Staff taxonomy screens do not edit it yet. A new department created by staff needs nothing to
  work: it appears in onboarding at once.
* **Service client**: identity client `catalog-service` carries `sellers:catalog-scope.read` (identity `application-*.yml`).
* **Catalog config**: `at.catalog.seller.base-url` (`SELLER_URL`), `timeout`, `scope-ttl`. **Seller config**:
  `at.seller.catalog.base-url` (catalog, for departments), `timeout`, `departments-ttl`.

## Not verified here

Testcontainers integration tests (no Docker), a real end-to-end run of seller-service against catalog-service, and the
identity client scope with a live token.
