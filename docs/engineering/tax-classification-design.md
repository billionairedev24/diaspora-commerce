# Sales tax classification — design spec (draft for CPA review)

**Status:** proposal, nothing built yet. **Owner:** finance + tax-returns. **Not legal or tax advice:** a CPA must approve the code map, the state list and the seller wording before launch.

## Problem

The tax a customer pays depends on *what the item is* (a tax code) and *where it ships*. Today tax-returns maps the catalog **product type** to `GENERAL`, `GROCERY` or `PREPARED_FOOD`. That is too coarse (a `grocery` type holds exempt rice and taxable soft drinks), the config names do not match the catalog types (`grocery` and `perishable` fall through to `GENERAL`), and `custom` carries no information. See [shipping-and-tax-data.md](./shipping-and-tax-data.md).

## Principles

1. **Tax code is separate from browse category.** Search and navigation keep the catalog tree; tax uses its own code.
2. **Derived, not typed.** Most listings get a default from their leaf category; for food and similar, plain answers to listing questions decide it. Sellers never type a tax code.
3. **Safe default.** Anything not positively classified is *general taxable*. Under-collecting is our liability; over-collecting is refunded.
4. **One engine, one place.** Rates and state rules live in the tax provider (Stripe Tax or Avalara behind the `TaxCalculator` port; the rules engine stays for local development and as a fallback). Sellers and the BFF never hold rates.
5. **Provider-neutral.** Our codes map to each provider's codes; switching providers changes one mapping column.

## Design

**Mapping table** (`tax_code_map`, staff-maintained, versioned): leaf category (and, where needed, attribute values) → our tax code → provider code (e.g. Stripe `txcd_…`). A shortlist of roughly 30 to 60 codes covers the catalog: general goods, clothing, packaged food, fresh produce, prepared food, beverages, supplements, books, personal care, children's goods.

**Listing questions** (only where the answer changes the tax): sold hot or prepared; carbonated or sweetened drink; candy; dietary supplement; alcohol; medicated or over-the-counter product. Answers are stored on the product and resolve the code. `custom` listings stay *general taxable* until staff classify them.

**Quote line:** `taxCode` (ours) and `providerTaxCode` are sent on every quote and stored on the order line snapshot, so refunds reverse with the code the sale used.

**Staff tools:**
- *Review queue:* new or edited listings in tax-sensitive categories (food, drinks, supplements, children's goods, `custom`) and every seller attribute change that lowers the tax.
- *Reclassify:* change a mapping or a listing's code with a reason; shows the orders affected. History is kept.
- *Reports:* sales and tax by tax code and state; the existing liability report (net to remit) stays the finance source.

**Seller statement:** each month, tax collected on the seller's behalf per jurisdiction (the endpoint exists; a Seller Center screen is needed).

## Seller responsibility (to be added to the seller terms)

Sellers must make sure the information they give about their products is accurate and complete, including category, product type and every tax-relevant answer (such as whether an item is hot or prepared, a soft drink, a supplement or an over-the-counter product). AfroTransact derives the tax treatment from that information. Sellers are responsible for it being correct, must correct it promptly when it is not, and agree that AfroTransact may reclassify listings, recalculate tax and recover from the seller any tax, interest or penalties caused by inaccurate listing information, as the seller terms allow. This wording needs legal review.

## Out of scope for this spec

Nexus and registration tracker, filing and remittance record, tax-exempt buyers and resale certificates, services and bookings taxability, and AfroTransact Retail's own sales tax (it sells as the seller, not as facilitator). Each needs its own note.

## Build order

1. Fix the product-type mismatch in config (`grocery`, `perishable`).
2. `tax_code_map`, the code on quote lines and snapshots, and the listing questions.
3. Stripe Tax adapter behind `TaxCalculator`; keep the ledger and liability report.
4. Staff review queue, reclassify tool, reports; seller statement screen.
5. CPA sign-off on the mapping and sample items before go-live.
