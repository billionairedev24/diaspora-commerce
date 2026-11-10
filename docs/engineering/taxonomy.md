# Taxonomy: how categories, product types and their details are managed (S325)

> **Status:** the tree is the 13 > 73 department > category > product type tree of [category-tax-design.md](./category-tax-design.md)
> (plus one provisional category, below). It replaced the 7-department seed of earlier versions. The staff tools, audit, requests and
> brand registry below are unchanged.

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

## Brand registry (Catalog, Categories, Brands tab)

Staff manage brands in the console under `catalog:taxonomy` (the tab is hidden without it, and edits need the same edit
rank as the tree). The tab is a server-driven table over `GET /v1/staff/taxonomy/brands` (status filter, `q` search,
`limit`/`offset`): it opens on `PENDING`, the brands sellers typed in, naming the seller, the products that hold the
brand, the places it applies to and when it was asked for. The row sheet offers the decisions the status allows:
approve, reject, merge into an approved brand (the sheet says how many products move), edit name and scope (a
multi-select over the department, category and product-type tree), retire, and the history by name. *Add a brand*
creates an approved one; *Add many* takes a pasted list (one per line; commas and semicolons also split) and previews
which names are new, already listed or invalid before saving; the client sends only the new ones.

- Every change carries a reason (3 to 1000 characters) and the brand's `version` as `If-Match`; a stale version is a
  412 and the tab reloads. An edit that changes nothing cannot be saved (the button stays disabled; the API answers 422).
- A product type asks for a brand with an attribute of kind `BRAND` (key `brand`). Its options may only be `UNBRANDED`
  ("No brand") and `HANDMADE`; the editor's preview shows the brand search the seller sees, "add it for review", and
  those choices.

## Where brand and the other details come from

Brand *names* now come from the brand registry (next section); the rest of a product's details are not tables. A product's details are a map `attributes` on the product (`product.attributes`, jsonb),
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
- *Sizes and size charts:* the **sizing** trait marks a product type as sized (staff set whether a size chart is
  required and the measurements to suggest: name, unit, how to measure). Sellers then write their own charts and size
  labels; staff never write charts. A variation dimension with `allowCustomValues` (Size, and Color on fashion types)
  takes the seller's own values, the listed options being suggestions. Switching sizing on makes the size dimension
  seller-extensible. The staff API `PATCH /v1/taxonomy/product-types/{code}` takes `traits.sizing` (`{enabled:false}`
  switches it off, omitted fields keep their value) and `themes` (the whole list). See
  [size-charts.md](size-charts.md).
- *Public API:* `GET /v1/product-types[/{code}]` returns the attributes that apply (backward compatible; each attribute
  also carries `applies`); the staff API `GET /v1/taxonomy/product-types[/{code}]` returns all of them with the
  version and where the schema is used.

## The category tax tree: import, tax questions and the publish gate

**Tree.** 13 departments > 73 categories (the tax-bearing level: `taxProfileId` = the category code, `taxStatus` = the gate) >
product types (detail and schema). A category has one product type of its own name, except fashion: *Adult clothing & footwear*
has `dress`, `top`, `menwear` and `footwear`, *Children's clothing* has `kids`, all sized (size charts, own sizes and colours
and the size-chart quality rules are unchanged, see [size-charts.md](./size-charts.md)). The picker shows a category once; the
leaf node under it is the product type.

**Provisional category (owner decision needed).** The source document has no category for fabric by the yard. *Fashion &
Accessories > Fabrics & textiles* exists with status `NEEDS_APPROVAL` and no tax profile, so sellers can keep a draft but nothing
can be published into it until the owner and the tax professional decide.

**Sources.** `tools/taxonomy/category_tax_map.json` (departments, categories, statuses, facts) and
`java/services/catalog/src/main/resources/taxonomy/schemas.json` (the details sellers fill in besides the tax questions: brand,
ingredients, themes, sizing; generated by `python3 tools/seed/catalog_taxonomy.py --write`, checked by `make taxonomy-check`). The
catalog's build copies the dataset onto the classpath.

**Import.** `POST /v1/taxonomy/import` (`catalog:taxonomy`, a reason, optional `dryRun`/`force`/posted `dataset`) and the start-up
seed path (`at.catalog.taxonomy.import-on-start`, on locally) run the same idempotent, audited importer. It creates what is
missing (deterministic ids and codes: `<category>` and `<category>-item` or `<category>-<type>` for leaves), refreshes each type's
tax questions from the dataset, sets a category's `taxProfileId`, and leaves alone what staff changed: names, placement, a status
that tax-returns or the tax screens set, and detail questions. `force` resets all of that. Re-running changes nothing.

**Why migrations may load rows this once.** The rule below (migrations never own staff rows) still holds for the future. The
replacement is a one-time owner decision because nothing is deployed and catalogue data is deleted: `V18` adds the columns and the
evidence table, `V19` retires the initial tree by the deterministic ids V2 gave it (deleting what nothing uses, retiring what is used,
moving starter brand scopes aside), `V20` (a Flyway Java migration, `CategoryTaxTreeMigration`) loads the new tree with the same
planner the importer uses. From here on a change to the tree is a console action or an import with a reason.

**Tax questions (facts).** Each category's schema carries typed, plain-language questions generated from the dataset's fact groups:
`BOOLEAN` (yes/no), `DECIMAL` (with a unit, e.g. oz), `INTEGER`, `PERCENT`, `ENUM` (with plain option labels) and `FILE` (proof). A
question has `showWhen` and `requiredWhen` expressions (`{all}`, `{any}`, `{not}`, leaves `isTrue|isFalse|eq|in|gt|gte|lt|lte`; a
missing answer makes every leaf false). The catalog, the seller form and the reference evaluator share the vectors in
[fact-expression-vectors.json](./fact-expression-vectors.json). Product facts (`owner VARIANT`) are answered per variant and stored
on it (`taxFacts`); seller behaviour facts (`owner OFFER`: sold heated, heated by you, utensils provided; the dataset's
`seller_is_bakery` is asked here too, since the catalog has no seller profile to read it from) are the listing's
(`listing.sellerBehaviour`, the form's "Your listing"). `STAFF` and `CONTEXT` facts are never asked. Sellers never type a tax code.
Tax-fact attributes are dataset-owned: the schema editor cannot change or remove them.

**Publish gate.** On every create and edit the catalog checks the answers, asks tax-returns to resolve each variant
(`POST /v1/tax/classifications/resolve` behind the `TaxResolver` port; `at.catalog.tax.resolver=local` uses a fake built from the
dataset) and stores the status, the missing facts and the decision summary on the variant. A wrong or missing answer, or a category
that is `NEEDS_APPROVAL`, `BLOCKED_BUNDLE` or `CONFLICT_OPEN`, refuses the submission (422, `fieldErrors` keyed
`variants[i].taxFacts.<id>`, `sellerBehaviour.<id>`, `category`, with plain messages); the seller can still save a draft. A
`REFINE_CODE` category can be submitted but never goes live until staff refine the code. Proof files may follow the submission
(they need the item to exist): `POST /v1/variants/{atin}/tax-evidence`. A variant is READY only when the rules resolve and the tax
review approved it (`tax.classification.changed` moves it; locally `at.catalog.tax.review-required=false` skips the review).
`GET /v1/products/{id}/tax-readiness` lists what is missing per variant; approving a product that is not READY is refused (422
`catalog.tax-not-ready`). Edits that leave facts and outcome unchanged keep an approved variant approved; a changed answer starts the
review again. Every change of facts or category publishes `catalog.product.tax-facts-changed`.

**SKU and matching.** `sellerSku` is optional: empty, the catalog generates `<org prefix>-<ATIN>` (stable, unique per organisation,
`-2`, `-3` on a collision), flagged `sellerSkuGenerated`. Edits and bulk updates match a variant by SKU, else GTIN, else ATIN; the
bulk template has an optional ATIN column and one `fact:<id>` column per question (proof files excluded), with row-level messages.
"Download my SKU list" (`GET /v1/image-library/sku-list`) names the image library files.

## Seed migrations are the initial data only (from now on)

`V2__seed_taxonomy.sql` seeded the first tree and is retired by V19. **Do not add migrations or regenerate seeds to change the live
tree or a schema**: use the console, the staff API or the importer. Migrations may add *columns and tables* for the taxonomy feature,
never rows that staff own (V20 is the one documented exception above).

## What follows a rename or move

- Paths of everything below the node and the text paths saved in product-form drafts follow immediately.
- The search indexer re-reads the tree within five minutes; products already in the index keep the old path text until
  they are reindexed (run `make reindex-search` after a bulk rename, without `--since`: a rename changes no product). The indexer does not consume
  `catalog.taxonomy.changed` yet; delivery-runs caches the tree for its own TTL.
