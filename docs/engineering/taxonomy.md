# Taxonomy: how categories, product types and their details are managed (S325)

The catalogue has a browse tree of three levels, **department › category › product type** (for example
*Fashion › Women › Dresses*), and every product type points at a **schema**: the details sellers fill in (material,
allergens, a permit number…), the barcode rule (GTIN or ISBN) and the traits that switch form sections on (food,
perishable, regulated, pre-order). Several product-type nodes can share one schema (*Tops* and *Wrappers & skirts* both
use `top`).

Until S325 the tree was created only by the seed migration `V2__seed_taxonomy.sql`. **Staff now manage it in the console
(Catalog › Categories). Migrations are the initial data only.**

## Who can change it

| What | Needs |
|---|---|
| Read the tree, the schemas, the audit trail and the category-request queue | the `catalog` module (View) and the `catalog:taxonomy` permission |
| Add, rename, move, reorder, retire, restore; edit a schema | the same permission and the `catalog` module at **Edit** |
| Approve or reject a seller's category request | `catalog:taxonomy` and the `catalog` module at **Approve** |

`catalog:taxonomy` belongs to the Super Admin and Catalog Manager roles. Everyone else does not see the Categories and
Category requests tabs. The MCP staff tool `category_requests_queue` lists the pending requests (read-only, same
permission).

## Creating a category (what staff do)

1. **Catalog › Categories › Add a department**, or open a department or category and choose **Add a category** or
   **Add a product type**. Every form asks for a **reason** (at least 3 characters); it is kept with the person's name.
2. A new product type either **reuses an existing schema** or **starts a new one** copied from a type you pick
   (the general product by default). The details editor opens straight away so the new schema can be refined.
3. **Edit its details** (a product-type node) opens the editor for the schema: name, barcode rule, traits and every detail
   with its kind (short text, long text, choice, number), choices, units, form section (details, food, regulated),
   *required* and *ask sellers for this*. Next to it the **seller form preview** shows the fields exactly as the
   seller's product form will, and updates with every keystroke. Save stays disabled until something differs from what
   was loaded.

### Category requests

A seller who cannot find a category requests one (`POST /v1/category-requests`); products built against the request wait
in `PENDING_CATEGORY_APPROVAL`. Staff review the queue under **Catalog › Category requests** (the seller is shown by
name, with the requested path and the products waiting).

**Approve & create** is one step: choose where it goes (a *department*, in which case a category is created for it, or an
existing *category*), and either an existing schema or *create a new product type* starting from another. The sheet
says what will be created before anyone confirms (`POST /v1/category-requests/{id}/approval-preview` computes the same
plan the approval then executes). Approving creates the category (if needed) and the product-type leaf, moves the waiting
products into listing review and records everything under the approver's name. **Reject** needs a reason, which the
seller sees; the waiting products are rejected with it.

## Rules (enforced by catalog, shown early by the console)

- Three levels only: departments have no parent, a category goes under a department, a product type under a category.
  A node can never move inside itself; departments do not move (they are reordered).
- Names are unique among siblings (case-insensitive); every node has a unique **code** (lower-case words and hyphens,
  derived from its parent's code and its name unless given); product-type schemas have unique codes (`a-z0-9_`).
- **Retire is soft.** A node, and everything under it, is hidden from the trees sellers and shoppers choose from, but it
  is never deleted. **A category that products still use cannot be retired** (409 `catalog.taxonomy-in-use`): move its
  product types to another category first (products follow the node). Rejected listings do not count. A retired
  category stays on the products that already use it and keeps resolving for them (`GET /v1/categories?includeRetired=true`,
  which the search indexer uses), but cannot be chosen for a new product. **Restore** brings it back with what was
  retired with it.
- **Optimistic locking.** Nodes and schemas carry a `version`; every edit sends it as `If-Match` (412
  `catalog.version-mismatch` when someone saved first, 428 when it is missing). A reorder sends the full sibling list and
  is refused (412) if the list changed.
- A form with nothing changed cannot be saved (422), on the console and on the server.
- Attribute rules: unique keys (`a-z0-9_`, fixed once saved), choices need values, units belong to numbers, the *food*
  section needs the food trait and *regulated* the regulated trait, perishable needs food. The sales-tax question
  (`tax_class`) is owned by tax-returns: its wording and choices cannot be edited, only whether it is asked.
- Changing a schema does not rewrite products: they keep what they hold for a removed or "not asked" detail. A detail
  made required is enforced for products created from then on.

## Audit and events

Every change appends a row to `taxonomy_audit` (who, action, subject, the path or label at the time, the reason and
the before/after) and publishes `catalog.taxonomy.changed` on `at.taxonomy.events` (see
`contracts/asyncapi/fragments/catalog.yaml`). The console shows the history of a node in its sheet
(`GET /v1/staff/taxonomy/audit?subject=`, people by name). Category-request decisions are audited too.

## Where brand and the other details come from

There is **no brand table**. A product's details are a map `attributes` on the product (`product.attributes`, jsonb),
keyed by the attribute key of its product type's schema. **Brand is simply the attribute with key `brand`** that most
fashion, grocery, beauty and home schemas ask for; the Books schema has author and publisher instead.

- *Asked or not:* the schema decides per type. Switch **Ask sellers for this** off (the attribute's `applies` flag) and
  the seller form, the upload template and the validators stop asking while the definition stays. Nothing else needs to
  change: the bulk-upload template still offers an optional Brand column, because `brand` is the one key the platform
  reads everywhere.
- *Who reads it:* the product event carries `brand` (the attribute) to the search indexer for brand filters and
  cards; `GET /v1/brands` suggests the brands a seller already used on their own products for the form's combobox.
- *Other details* (material, fit, ingredients, allergens, shelf life, MoCRA number…) work the same way: define them on
  the schema, and sellers fill them in. The **food** and **regulated** sections show only for types with those traits.
- *Public API:* `GET /v1/product-types[/{code}]` returns the attributes that apply (backward compatible; each attribute
  also carries `applies`); the staff API `GET /v1/taxonomy/product-types[/{code}]` returns all of them with the
  version and where the schema is used.

## Seed migrations are the initial data only

`V2__seed_taxonomy.sql` (generated by `tools/seed/catalog_taxonomy.py` from the design) seeds a new environment once.
**Do not add migrations or regenerate the seed to change the live tree or a schema**: use the console (or the staff
API). Migrations may add *columns and tables* for the taxonomy feature, never rows that staff own. A new local database
gets the initial tree from V2; V11 only added codes, versions and the audit table.

## What follows a rename or move

- Paths of everything below the node and the text paths saved in product-form drafts follow immediately.
- The search indexer re-reads the tree within five minutes; products already in the index keep the old path text until
  they are reindexed (run the indexer backfill after a bulk rename). The indexer does not consume
  `catalog.taxonomy.changed` yet; delivery-runs caches the tree for its own TTL.
