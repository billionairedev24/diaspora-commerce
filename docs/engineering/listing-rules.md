# Listing rules, approval gating and launch shipping (APPROVED SPEC)

**Status:** approved by the owner as recommended (all of D1 to D22; D23 added 18 Nov 2026 after owner feedback, in review). Agents must follow this strictly. Source of the field and category rules: `tools/listing-rules/rules.json` (generated from the reviewed matrix; edit that file and this document together). Reference: Amazon Seller Central practice, with the owner-supplied "Categories and products that require approval" page.

Related: `docs/engineering/category-tax-design.md` (tax), `docs/engineering/shipping-and-tax-data.md` (shipping today), `docs/runbooks/application-review-workflow.md` (review workflow), `docs/engineering/service-areas.md`.

## 1. Decisions (final)

### D1. Should most categories be open, with approval only where the risk is real?

- **Decision:** Yes. Open by default, with the three states shown in the product form. Today every listing waits for a human, which is the opposite of Amazon and slows honest sellers.
- **Amazon standard:** Amazon lets sellers list in most categories and gates a defined set. Its three states are "Sell this product", "Apply to sell" and "Not available".
- **Why:** Sellers in open categories go live as soon as validation passes. Staff time goes only to the gated ones.

### D2. Which categories need approval?

- **Decision:** Approval: perishable food, food service, baby formula, supplements, OTC medicines, medicated skincare, medical devices, car seats, digital products, gift cards, event tickets, photography and tutoring. Conditional: jewelry with precious metals, stones or watches. Brand-gated: clothing, bags, electronics, perfume. Open: everything else, including packaged groceries and toys. I changed my earlier view on toys: Amazon keeps them mostly open, so we require the safety certificate instead of approval.
- **Amazon standard:** Amazon's list (your paste) includes jewelry, watches, clothing and accessories, services, collectibles, fine art, music and DVD, streaming players, coins and stamps, Made in Italy. Public guides also gate grocery, supplements, topicals and some baby feeding items, but they disagree.
- **Why:** See the category table below. Move any category with one line, for example "toys to approval".

### D3. Is approval per seller and scope, with no second review of each listing?

- **Decision:** Yes. Approve per seller and scope. Review an individual listing only when flagged (a report, a risk score, a changed category).
- **Amazon standard:** Amazon approves the seller for a category, brand or product once. Listings then go live. Only restricted individual products are reviewed.
- **Why:** Avoids double review. A second check on every listing in gated categories would repeat the approval and recreate the queue problem.

### D4. What happens when a document expires?

- **Decision:** Warn at 30, 14 and 7 days. After expiry give 14 days of grace, then pause that scope's listings. They return on renewal. Nothing is deleted.
- **Amazon standard:** Amazon says privileges change when insurance expires, safety status changes or performance drops, and restricts the listings.
- **Why:** Prevents a surprise takedown caused by a forgotten renewal, while still protecting shoppers.

### D5. Can a disqualified seller appeal?

- **Decision:** Same, but show the reason and a reapply date set by staff. The reviewer must record a reason.
- **Amazon standard:** Amazon: "Your account does not qualify" cannot be appealed.
- **Why:** Clear rule for staff and sellers. A reapply date avoids permanent bans for fixable problems.

### D6. How fast should staff answer an approval application?

- **Decision:** Target 2 business days. The review SLA and escalation you now have apply as they are.
- **Amazon standard:** Amazon quotes 2 to 7 business days.
- **Why:** Faster than Amazon, which suits a small catalog. Escalation catches the exceptions.

### D7. Should tax review become risk-based instead of reviewing every variant?

- **Decision:** Auto-approve when the category is Active and the rules resolve cleanly. Send to a tax reviewer when the status is needs-approval, refine-code, conflict or bundle, or the item is medicated or a supplement. Sample 5% of auto-approved items for audit. **Update 13 Nov: owner decision, this is now the default** (tax is automatic from the rules; a person reviews only exceptions). Staff can still choose to review every item.
- **Amazon standard:** Amazon does not hold a listing for a per-product tax review.
- **Why:** Today every variant waits, which is why the seeded items stayed queued. The checkout safeguard stays: a line without a clear decision is blocked.

### D8. Should package length, width, height and weight be required?

- **Decision:** Required for every physical variant, whoever ships it. Stored per variant, with a copy-down default from the first variant. Hidden for digital items, gift cards and services. Keep the 108 in and 150 lb maximums.
- **Amazon standard:** Public guides describe dimensions and weight as core listing data and a requirement for FBA. Amazon's inbound page sets box limits and requires accurate box weights.
- **Why:** It is what labels, carrier rates, vehicle choice and any live shipping rate need. Today it is optional unless the seller uses Fulfilled by AfroTransact, and stored once per listing even when sizes weigh differently.

### D9. When is Condition shown?

- **Decision:** Hide Condition and fix it to New for food, beverage, beauty, health, baby feeding, pet food, car seats, clothing, jewelry, digital and gift cards. Show New, Renewed and Used for electronics, books, general goods and toys. Used clothing can be a later pre-owned programme with approval.
- **Amazon standard:** Amazon allows used items in some categories (books, electronics, many home goods) and not in others such as grocery, beauty and health. Clothing used is restricted. This is my reading of public guidance.
- **Why:** Removes a meaningless question from most listings, and keeps unsafe used goods out.
- **Refined 17 Nov 2026 (owner feedback: "a grocery cannot be New, we do not need that"):** Condition is asked only where a used,
  renewed or refurbished item is a real thing to sell, and it is **never required** (New is the answer when it is left alone; toys
  went from "required or reason" to optional). It is shown for exactly 11 categories: computers and laptops, gaming, phones, TVs and
  audio, printed books, magazines, toys and games, furniture and small appliances, kitchenware and cookware, sports goods, school and
  office supplies. It is hidden, and the server stores New whatever is sent, for everything else, including the general-goods
  categories that are consumables or hygiene items (hair accessories, wigs, cleaning and paper products, candles and decor, diapers and
  wipes, bedding, pet grooming, handmade). The same data decides every place: the product form, the bulk template, the offer form for an
  existing page (`allowedConditions` on the match answers) and offers' `offers.condition-not-allowed`. No UI file lists categories.

### D10. What replaces typed ingredients and allergens for groceries?

- **Decision:** Packaged groceries: no typed ingredients. An optional photo of the ingredients and nutrition panel (required for baby feeding, beauty, health and pet food), plus an optional "contains" tick list. Food service: typed ingredients and allergens required. Beauty: typed ingredient list required. Supplements: Supplement Facts photo.
- **Amazon standard:** Amazon expects the label to show ingredients and allergens on the product images for food. Public guides also list ingredients as an attribute.
- **Why:** Matches your instruction that typing ingredients for every grocery is excessive, while keeping the label in front of shoppers. Counsel to confirm the labelling wording.

### D11. Is Food service a verified capability?

- **Decision:** A seller capability "Food service" granted through approval with a permit upload and an expiry date. It replaces the current bakery flag. Groceries stay permit-free, as you decided.
- **Amazon standard:** Amazon gates the Services category and requires licences where regulation applies.
- **Why:** One clear route for cooks, bakers and caterers, with the same approval workflow as everything else.

### D12. Is country of origin required?

- **Decision (changed by the owner, 2026-11):** Country of origin is not asked on products at all. It is not used for anything on the product, so the field, its rule and the product page "Origin" row are removed. Values already stored on old listings stay in the database but are never read, shown or asked (`origin` is a superseded detail key). Customs data for international parcels is a separate concern in carrier-gateway and is not touched here.
- **Why:** An answer nothing reads is effort for sellers with no benefit.

### D13. Do we ask the dangerous goods question?

- **Decision:** Required answer in beauty, health, electronics, candles, cleaners and toys with batteries. Not shown elsewhere. A "yes" blocks fulfilment by AfroTransact and limits carriers until staff review.
- **Amazon standard:** Amazon treats hazmat as a listing classification that packaging and the page must agree with.
- **Why:** Perfume and aerosols cannot go by every carrier or air.

### D14. Product ID (barcode)?

- **Decision:** Keep our existing rule: GTIN or ISBN, or an exemption with a reason. Books always need the ISBN. Handmade and unbranded items may use the exemption.
- **Amazon standard:** Amazon requires a product ID or an exemption.
- **Why:** Already built. The matrix just states it per kind.

### D15. What about the main image size and image count?

- **Decision (supersedes the relaxed rule of 2026-11-11, owner decision 2026-11-12):** the main image is **enforced** again, using the
  longest side as Amazon does (the old rule asked for both edges): at least **1000 px on the longest side** (2000 px recommended) and
  500 px on the shortest, a **pure white background**, and the product filling at least **75%** of the frame (a warning from 75% to
  85%). Other images warn under 1000 px and are rejected under 500 px. At least one image is required; the "7 extra photos" are a
  recommendation shown as a quality meter, never required. A label photo is optional for groceries and food service, and required for baby feeding, beauty, health and pet food. WebP and the
  8 MB cap stay. Apparel main images on a model are not allowed by default (a setting). Every threshold is media-service
  configuration (`images.*`), staff can change the main ones at run time, and a "warn only" switch turns the appearance rejects
  (not white, fill, border) into warnings per environment.
- **Existing listings:** grandfathered. They stay live with an "Improve your image" task; after 60 days (staff-editable) listings that
  still fail may be limited in search and the buy box, but only when a separate flag is on (off until the owner switches it on).
  Nothing is deleted. See `docs/engineering/bulk-upload-and-images.md` section 6.
- **Amazon standard:** Amazon requires a main image on a plain white background, at least 1000 px on the longest side for zoom, filling
  about 85% of the frame (third-party sources; the Amazon numbers are still **[verify]**).
- **Why:** Zoom sells, and a consistent white main image makes the catalog look trustworthy.

### D16. Who sets the shopper's shipping charge at launch?

- **Decision:** AfroTransact sets all shipping at launch. One flat fee, editable by staff, for Fulfilled by AfroTransact orders and for store collection by our drivers, with an optional free-above threshold. Sellers do not set shipping. The customer can collect from the seller's store or an AfroTransact pickup point for $0. Seller-shipped carrier options are switched off in checkout until sellers ship themselves.
- **Amazon standard:** Amazon sellers use shipping templates. FBA orders ship at Amazon's own rates.
- **Why:** Launch is built on Fulfilled by AfroTransact, so there is one price to explain and no carrier cost to absorb. The flat fee and the weight-based mode already exist in the delivery settings, so this is configuration plus tests.

### D17. Carriers or an aggregator?

- **Decision:** No carrier rates at checkout in v1. Carrier-gateway stays for our own outbound labels and tracking. Revisit live rates, and direct carriers versus an aggregator, only when sellers start shipping themselves or the flat fee loses money.
- **Amazon standard:** Not an Amazon question.
- **Why:** No new vendor cost or integration risk before launch. The package size and weight you now require is what makes the later step possible.

### D18. Should the flat fee ever depend on size and weight?

- **Decision:** Launch with one flat fee. When data shows heavy or oversize items lose money, switch the existing weight mode on (base plus per pound), then size tiers. Staff change it in settings, with a reason.
- **Amazon standard:** Amazon FBA fees depend on size tier and weight.
- **Why:** Dimensions are collected from day one, so no seller has to re-enter anything later.

### D19. Mixed baskets: how is delivery tax split?

- **Decision:** Keep the current per-line allocation, flagged in the launch checklist, until the Texas tax professional chooses a method.
- **Amazon standard:** Not an Amazon question.
- **Why:** Open legal decision 5. Needs the adviser.

### D20. What happens when the address is too far?

- **Decision:** Checkout never dead-ends. Outside the flat-fee delivery zone it shows "Too far for standard delivery. Ask us for a quote". That creates a case in the staff queue, with the same assignment and escalation as applications. Customer care agrees an amount (reason required, shipping permission needed). The customer gets a pay link valid for 48 hours. The order carries that shipping amount, taxed like any shipping. Staff see who agreed what.
- **Amazon standard:** Not an Amazon question.
- **Why:** This replaces ad-hoc phone deals with a record, and gives tax a definite shipping amount for the order.

### D21. Where does "too far" start?

- **Decision:** Use delivery zones, not a hard mile limit. Inside a zone the flat fee applies. Outside, the quote path applies. Staff can add or widen zones. Shoppers can still choose their own search radius for services, which is a different feature.
- **Amazon standard:** Not an Amazon question.
- **Why:** Zones already exist for eligibility, so this avoids a second rule system.

### D22. How do pre-orders fit?

- **Decision:** Keep community pre-order windows as built. In checkout the line shows the window date and the delivery fee rule of that run. Pre-order lines and in-stock lines go in separate packages, each with its own fee. This needs end-to-end tests with the new flat fee.
- **Amazon standard:** Amazon pre-orders charge at ship time and show a release date.
- **Why:** A pre-order must never be blocked or double-charged by the shipping change.

### D23. Is the brand always required? (owner feedback 18 Nov 2026: "groceries need no brand")

- **Decision:** No. The brand is decided per category in the same matrix as every other field, never in code or in the UI. Four
  outcomes: **required** (name the brand; there is no "No brand" choice), **brand or reason** (a brand, or "No brand" or "Handmade";
  the matrix cell is `R*`), **optional** (leave it empty or choose "No brand") and **hidden** (no question). The audit of all 73
  categories is in section 7. The product form, the API, the bulk upload and the offer paths read the same cell: where the rule says
  optional the server never asks for a brand and the form shows no "required" mark.
- **Where it matters:** the brand is required where it identifies the responsible manufacturer for safety or recalls (baby formula
  and bottles, supplements, OTC medicines, medical devices, first aid, medicated skincare, car seats), where authenticity is
  the risk (electronics, fragrance, gift cards) and where the brand is the whole product (gift cards). It is brand or reason where
  branded goods are also made by a tailor, a maker or a cook (clothing, bags, toys, jewelry, skin and hair care, household goods). It is
  optional where loose, local, store-brand, bulk or handmade goods are normal (groceries, drinks, pet food, hair accessories, wigs,
  bedding, candles, school supplies, sports goods, digital products). It is hidden where the seller is the maker or there is no
  product (food service, services, books and magazines: books ask for author and publisher instead, in the product type).
- **A brand that is not in the list:** the seller types it and chooses "Add “…” as a new brand". It is stored as a seller-proposed
  brand (status `PENDING`), usable at once by that seller, attached to the listing, queued for staff to approve, merge into an
  existing brand or reject. The seller is never held up by that review. See section 7.
- **Amazon standard:** Amazon requires a brand or "Generic" for most categories and lets sellers request a missing brand; it
  exempts some categories. We replace "Generic" with an honest "No brand" reason.
- **Why:** A mandatory brand for rice, produce or spices produces made-up brands ("Generic", "N/A") that pollute search facets and the
  brand registry, and blocks honest sellers of loose goods. Where the brand is required, the picker always lets the seller add a
  missing one, so nobody is stuck.

## 2. Approval model

Three states shown in the product form after category and brand are chosen: `SELL` (no approval, live after validation), `APPLY` (seller applies once; after approval every listing in that scope goes live), `NOT_AVAILABLE` (prohibited or disqualified, reason shown, not appealable; staff set a reapply date).

Five scopes: department or category; product type; brand; brand within a category; single product (barcode or ATIN). The seller needs approval for every scope that applies.

Documents a rule can require: purchase invoices dated within 180 days; photos of all sides; brand authorization letter; safety or lab certificate; permit or licence; liability insurance naming AfroTransact as additional insured with an expiry date.

Lifecycle: application becomes a staff case beside seller and provider applications (reviewer, approver, assignment, who-has-it, escalation, names not ids); target answer 2 business days; warnings at 30, 14 and 7 days before a document expires; 14 days grace after expiry, then listings in that scope are paused (never deleted) and restored on renewal. A new rule or a performance problem can also pause listings. Food service is a verified seller capability (permit with expiry) replacing the bakery flag.

## 3. Launch shipping (v1)

AfroTransact sets all shipping. No carrier names or carrier options anywhere in checkout (no USPS, UPS, FedEx, DHL). Seller-shipped carrier options are hidden behind a staff setting that is OFF.

| Situation | Option | Fee |
|---|---|---|
| Fulfilled by AfroTransact item | Delivery by AfroTransact | One flat fee set by staff (default $5.99), optional free-above threshold (default none) |
| Fulfilled by AfroTransact item | Pickup at an AfroTransact point | $0 |
| Item sold from a seller store | AfroTransact collects from the store and delivers | Same flat fee |
| Item sold from a seller store | Customer collects from the store | $0, address and hours shown |
| Pre-order item | Window date and the run delivery fee | As set on the run; own package |
| Address outside the delivery zones | Ask for a delivery quote | Agreed by customer care (D20) |

One source for fees: the estimate the shopper sees and the amount orders charges must come from the same settings and be proven equal by a test. Later (not now): weight tiers using the existing per-pound mode, then live carrier rates for seller-shipped orders, then seller choice per listing.

## 4. Field rules by kind

Codes: required, required_or_exemption (needs a reason to skip), document (upload reviewed at approval), optional, hidden.

Kinds: `PKG` Packaged food and drink, `PER` Perishable food, `SFD` Food service (prepared), `BBY` Baby feeding, `BEA` Beauty and personal care, `HLT` Health products, `FSH` Clothing, shoes, bags, `JWL` Jewelry, `ELC` Electronics, `HOM` General goods, `KID` Toys and car seats, `PET` Pet food, `BOK` Books and magazines, `DIG` Digital products, `GFT` Gift cards, `SRV` Services (in person)

| Field | PKG | PER | SFD | BBY | BEA | HLT | FSH | JWL | ELC | HOM | KID | PET | BOK | DIG | GFT | SRV |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Identity** | | | | | | | | | | | | | | | | |
| Title | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R |
| Brand | o | o | - | R | R* | R | R* | R* | R | R* | R | o | - | o | R | - |
| Product ID (barcode or ISBN) | R* | o | - | R* | R* | R | R* | R* | R | R* | R* | R* | R | - | - | - |
| **Content** | | | | | | | | | | | | | | | | |
| Description | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R |
| Search keywords | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o | o |
| At least one image | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R |
| Label photo | o | o | o | R | R | R | - | - | - | - | - | R | - | - | - | - |
| **Offer** | | | | | | | | | | | | | | | | |
| Condition | - | - | - | - | - | - | - | - | o | o | o | - | o | - | - | - |
| Price | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R | R |
| Quantity | R | R | R | R | R | R | R | R | R | R | R | R | R | - | - | - |
| Handling time (days) | R | R | R | R | R | R | R | R | R | R | R | R | R | - | - | - |
| Ship-from location | R | R | R | R | R | R | R | R | R | R | R | R | R | - | - | R |
| **Shipping and compliance basics** | | | | | | | | | | | | | | | | |
| Package length, width, height | R | R | R | R | R | R | R | R | R | R | R | R | R | - | - | - |
| Package weight | R | R | R | R | R | R | R | R | R | R | R | R | R | - | - | - |
| Dangerous goods question | - | - | - | - | R | R | - | - | R | R | R | - | - | - | - | - |
| **Product facts** | | | | | | | | | | | | | | | | |
| Net weight or volume | R | R | R | R | R | R | - | - | - | - | - | R | - | - | - | - |
| Servings or unit count | R | o | R | R | - | R | - | - | - | - | - | - | - | - | - | - |
| Shelf life or expiry | R | R | R | R | R* | R | - | - | - | - | - | R | - | - | - | - |
| Storage | o | R | R | o | - | o | - | - | - | - | - | o | - | - | - | - |
| Ingredients (typed) | - | - | R | - | R | - | - | - | - | - | - | - | - | - | - | - |
| Allergens ("contains") | o | o | R | o | - | - | - | - | - | - | - | - | - | - | - | - |
| Material | - | - | - | - | - | - | R | R | - | R* | o | - | - | - | - | - |
| Model number | - | - | - | - | - | - | - | - | R | R* | - | - | - | - | - | - |
| Age grade and safety standard | - | - | - | R* | - | - | R* | - | - | - | R | - | - | - | - | - |
| Sizes and size chart | - | - | - | - | - | - | R | R* | - | - | - | - | - | - | - | - |
| **Documents (reviewed at approval)** | | | | | | | | | | | | | | | | |
| Permit or licence | - | R* | DOC | - | - | DOC | - | - | - | - | - | - | - | - | - | R* |
| Brand authorization | R* | R* | - | R* | R* | R* | R* | R* | R* | R* | R* | R* | R* | - | - | - |
| Safety or lab certificate | - | - | - | DOC | - | R* | R* | - | R* | - | DOC | - | - | - | - | - |
| Liability insurance | - | R* | DOC | - | - | DOC | - | - | - | - | R* | - | - | - | - | R* |
| Authenticity or purchase invoice | - | - | - | - | R* | - | R* | DOC | R* | - | - | - | - | - | - | - |

Legend: R required, R* required or an exemption with a reason, DOC document upload, o optional, - hidden. Per-field notes are in rules.json.

## 5. Categories

| Category | Department | Kind | Approval | Tax status | Note |
|---|---|---|---|---|---|
| Baby food & formula (`baby-food-formula`) | Groceries & Food | BBY | approval | ACTIVE | Formula needs the FDA infant formula notification and a purchase invoice. |
| Bakery products (`bakery-products`) | Groceries & Food | SFD | approval | ACTIVE | Seller-baked is food service. A resold factory product is packaged food. |
| Beans, pantry foods & sauces (`beans-pantry-sauces`) | Groceries & Food | PKG | open | ACTIVE |  |
| Candy & gum (`candy-gum`) | Groceries & Food | PKG | open | ACTIVE |  |
| Cooking oils (`cooking-oils`) | Groceries & Food | PKG | open | ACTIVE |  |
| Dairy & eggs (`dairy-eggs`) | Groceries & Food | PER | approval | ACTIVE | Cold chain documents. Ships express or local only. |
| Flours & starches (`flours-starches`) | Groceries & Food | PKG | open | ACTIVE |  |
| Fresh produce (`fresh-produce`) | Groceries & Food | PER | approval | ACTIVE | Cold chain documents. Ships express or local only. |
| Frozen meals (`frozen-meals`) | Groceries & Food | PER | approval | ACTIVE | Made by the seller means food service rules apply. |
| Meat, poultry & fish (`meat-poultry-fish`) | Groceries & Food | PER | approval | ACTIVE | Cold chain documents and supplier invoice. |
| Nuts & seeds (`nuts-seeds`) | Groceries & Food | PKG | open | ACTIVE | Servings and weight decide the tax outcome. |
| Packaged snacks (`packaged-snacks`) | Groceries & Food | PKG | open | ACTIVE |  |
| Pasta & noodles (`pasta-noodles`) | Groceries & Food | PKG | open | ACTIVE |  |
| Prepared meals (`prepared-meals`) | Groceries & Food | SFD | approval | ACTIVE | Needs the verified Food service capability and permit. |
| Rice & grains (`rice-grains`) | Groceries & Food | PKG | open | ACTIVE |  |
| Spices & seasonings (`spices-seasonings`) | Groceries & Food | PKG | open | ACTIVE |  |
| Coffee & tea, dry (`coffee-tea-dry`) | Beverages | PKG | open | ACTIVE |  |
| Energy, protein & drink mixes (`energy-protein-drink-mixes`) | Beverages | PKG | open | ACTIVE | If it makes health claims it moves to Dietary supplements. |
| Flavored water & soda (`flavored-water-soda`) | Beverages | PKG | open | CONFLICT_OPEN | Tax decision still open. |
| Fruit & vegetable juice (`fruit-vegetable-juice`) | Beverages | PKG | open | ACTIVE |  |
| Plain water (`plain-water`) | Beverages | PKG | open | ACTIVE |  |
| Ready-to-drink coffee & tea (`ready-to-drink-coffee-tea`) | Beverages | PKG | open | ACTIVE |  |
| Hair accessories (`hair-accessories`) | Beauty & Personal Care | HOM | open | ACTIVE |  |
| Haircare (`haircare`) | Beauty & Personal Care | BEA | open | ACTIVE | Typed ingredient list required. |
| Medicated skincare (`medicated-skincare`) | Beauty & Personal Care | HLT | approval | ACTIVE | Amazon gates topicals. Drug Facts photo and documents. |
| Perfumes & fragrances (`perfumes-fragrances`) | Beauty & Personal Care | BEA | brand_gated | ACTIVE | Flammable, so the dangerous goods answer applies. Brand authenticity invoice. |
| Skincare & cosmetics (`skincare-cosmetics`) | Beauty & Personal Care | BEA | open | ACTIVE | Typed ingredient list required. |
| Wigs & extensions (`wigs-extensions`) | Beauty & Personal Care | HOM | open | ACTIVE |  |
| Adult clothing & footwear (`adult-clothing-footwear`) | Fashion & Accessories | FSH | brand_gated | ACTIVE | Amazon gates Clothing and Accessories. We gate known brands and keep the category open. |
| Bags & purses (`bags-purses`) | Fashion & Accessories | FSH | brand_gated | ACTIVE |  |
| Children’s clothing (`childrens-clothing`) | Fashion & Accessories | FSH | brand_gated | ACTIVE | Children's product certificate and tracking label. |
| Jewelry & accessories (`jewelry-accessories`) | Fashion & Accessories | JWL | conditional_approval | ACTIVE | Fashion and beaded jewelry are open. Gold, silver, gemstones and watches need approval. |
| Bedding & linens (`bedding-linens`) | Home & Household | HOM | open | ACTIVE |  |
| Cleaning & paper products (`cleaning-paper-products`) | Home & Household | HOM | open | REFINE_CODE | Dangerous goods answer applies to cleaners. |
| Decor & candles (`decor-candles`) | Home & Household | HOM | open | ACTIVE | Candles ask the dangerous goods question. |
| Furniture & small appliances (`furniture-small-appliances`) | Home & Household | HOM | open | REFINE_CODE | Model number and electrical certificate for appliances. |
| Kitchenware & cookware (`kitchenware-cookware`) | Home & Household | HOM | open | REFINE_CODE |  |
| Computers & laptops (`computers-laptops`) | Electronics | ELC | brand_gated | ACTIVE | New, Renewed or Used allowed. |
| Gaming (`gaming`) | Electronics | ELC | brand_gated | ACTIVE |  |
| Phones (`phones`) | Electronics | ELC | brand_gated | ACTIVE | Renewed phones need a grade and warranty. |
| TVs, audio & accessories (`tvs-audio-accessories`) | Electronics | ELC | brand_gated | ACTIVE |  |
| Magazines (`magazines`) | Books & Media | BOK | open | ACTIVE |  |
| Printed books (`printed-books`) | Books & Media | BOK | open | ACTIVE | ISBN required. New or Used. |
| Dietary supplements (`dietary-supplements`) | Health & Wellness | HLT | approval | ACTIVE | Supplement Facts photo, insurance, and a certificate of analysis. |
| Feminine hygiene (`feminine-hygiene`) | Health & Wellness | BEA | open | ACTIVE |  |
| First aid & wound care (`first-aid-wound-care`) | Health & Wellness | HLT | open | ACTIVE | Low risk items such as plasters and gauze. Staff can gate a product. |
| Medical devices (`medical-devices`) | Health & Wellness | HLT | approval | ACTIVE | FDA listing for the device class. |
| OTC medicines (`otc-medicines`) | Health & Wellness | HLT | approval | ACTIVE | Drug Facts photo and NDC or FDA listing. |
| Personal hygiene (`personal-hygiene`) | Health & Wellness | BEA | open | ACTIVE |  |
| Baby bottles (`baby-bottles`) | Baby & Kids | BBY | open | ACTIVE | Safety certificate required. |
| Car seats (`car-seats`) | Baby & Kids | KID | approval | ACTIVE | FMVSS 213 certificate and insurance. New only. |
| Diapers & wipes (`diapers-wipes`) | Baby & Kids | HOM | open | ACTIVE |  |
| Toys & games (`toys-games`) | Baby & Kids | KID | open | REFINE_CODE | Age grade and CPSIA or ASTM F963 certificate. Amazon tightens this in the holiday season. |
| Grooming & accessories (`pet-grooming-accessories`) | Pets | HOM | open | REFINE_CODE |  |
| Pet food (`pet-food`) | Pets | PET | open | ACTIVE | Species and life stage. Label photo. |
| Audiobooks (`audiobooks`) | Digital Products | DIG | approval | NEEDS_APPROVAL | Rights holder confirmation. |
| Digital art & downloads (`digital-art-downloads`) | Digital Products | DIG | approval | NEEDS_APPROVAL | Rights holder confirmation. |
| Downloadable e-books (`downloadable-ebooks`) | Digital Products | DIG | approval | NEEDS_APPROVAL | Rights holder confirmation. |
| Downloadable software (`downloadable-software`) | Digital Products | DIG | approval | ACTIVE | Malware scan and publisher identity. |
| Online courses (`online-courses`) | Digital Products | DIG | approval | NEEDS_APPROVAL | Instructor credentials. |
| Event tickets (`event-tickets`) | Services | SRV | approval | NEEDS_APPROVAL | Organizer verification. |
| Fitness classes (`fitness-classes`) | Services | SRV | provider_application | ACTIVE | Insurance document. |
| Hair & beauty appointments (`hair-beauty-appointments`) | Services | SRV | provider_application | ACTIVE | Cosmetology licence where the state requires one. |
| Laundry & dry cleaning (`laundry-dry-cleaning`) | Services | SRV | provider_application | ACTIVE |  |
| Photography & media (`photography-media`) | Services | SRV | approval | NEEDS_APPROVAL | Portfolio and licence terms. |
| Repairs (`repairs`) | Services | SRV | provider_application | ACTIVE | Trade licence where required. |
| Residential cleaning (`residential-cleaning`) | Services | SRV | provider_application | ACTIVE | Insurance document. |
| Tutoring & consulting (`tutoring-consulting`) | Services | SRV | approval | CONFLICT_OPEN | Tax decision still open. |
| Gift baskets / bundles (`gift-baskets-bundles`) | Other & Specialty | GFT | blocked | BLOCKED_BUNDLE | Blocked until bundles can be itemised for tax. |
| Gift cards (`gift-cards`) | Other & Specialty | GFT | approval | NEEDS_APPROVAL | Fraud controls and tax approval first. |
| Handmade products (`handmade-products`) | Other & Specialty | HOM | open | REFINE_CODE | Barcode exemption allowed. Brand "Handmade". |
| School & office supplies (`school-office-supplies`) | Other & Specialty | HOM | open | ACTIVE |  |
| Sports goods (`sports-goods`) | Other & Specialty | HOM | open | ACTIVE |  |

## 6. Implementation notes (catalog, seller center, gateway)

One source, three consumers. `tools/listing-rules/rules.json` is the only copy of the data. Catalog loads it through the
`RulesRegistry` port (`ClasspathRulesRegistry`); the build copy in `java/services/catalog/src/main/resources/listing-rules/rules.json`
is produced by `tools/listing-rules/sync.sh` and `ClasspathRulesRegistryTest` fails when it differs from the source (it also
checks the matrix in section 4 of this document cell by cell, and that every rule category is a category of the seed
taxonomy). The product form gets the rules for the chosen category from `GET /v1/listing-rules?categoryCode=` (through the
gateway as `GET /v1/seller/catalog/listing-rules`); the API validation (`DefaultProductDraftPolicy` plus `ListingRuleChecker`)
and the bulk upload (`ImportColumns.forCategories`, `ImportValidator`) read the same `CategoryRules`. The gateway holds no rule
data: it checks the shape of a submission and forwards catalog's field errors.

What the statuses mean in code:

- `required`: must be answered. `required_or_exemption`: answered, or the seller gives a reason (`listing.exemptions[fieldId]`,
  the `why_not_provided` column in uploads). The product ID keeps its own mechanic (`gtinExempt`, books always need the ISBN) and
  the brand its reason (`brand_reason`); a `required` barcode has no exemption.
- `hidden`: not shown, not required, and a submitted value is ignored the same way by the form, the API and the upload
  (`ListingNormaliser`): attributes are dropped, condition becomes New, the package and dangerous goods answer are dropped for
  digital, gift cards and services, a hidden barcode makes the variant exempt. An upload cell for a hidden question is ignored
  with a warning.
- `document`, and the document rows of section 4 (`required`, `required_or_exemption`, `document`): the form collects the file
  (private upload, stored in `listing.documents` marked `PENDING_REVIEW`) and the approval engine reviews it. They never block
  a listing in catalog, because whether a document is due depends on the approval scope (gated brand, cold chain), which the
  approval engine decides. When it exposes that decision, catalog can enforce the `required*` document rows.
- Sizes and size chart: the rule applies where the product type is sized (it has a size dimension). `required` makes the chart
  required, `required_or_exemption` and `optional` leave it optional, `hidden` removes it. A product type without sizes
  (fabrics sold by the yard, bags) is not asked for sizes, whatever its kind.

Clarifications this implementation made:

- The rules know a category by the code of the CATEGORY node above a product type (`dairy-eggs`). A category without rules
  (created by staff after the matrix) and a requested category get the general goods rules (`HOM`, marked provisional); the
  endpoint answers them for the code `general`.
- `fabrics-textiles` is in `rules.json` with `provisional: true` (kind FSH, open). It is not one of the 73 approved categories;
  consumers must skip provisional entries when they count.
- A category can override a field of its kind (`overrides`). Car seats are `KID` with Condition hidden, so they are New only
  while toys choose. The audit of 17 Nov 2026 added these overrides (category: field becomes status), each a question that meant
  nothing for the category: Condition hidden for hair accessories, wigs and extensions, cleaning and paper products, decor and
  candles, diapers and wipes, pet grooming, handmade products and bedding; Model number hidden for those and for school and office
  supplies, optional for sports goods and kitchenware; Material hidden for cleaning and paper products and diapers and wipes,
  optional for school and office supplies, sports goods and pet grooming; Age grade hidden for adult clothing and footwear, bags
  and purses and fabrics; Sizes optional for bags and purses; Servings hidden for medical devices, medicated skincare and first aid;
  Net weight and Shelf life optional for medical devices. The kind-level matrix above is unchanged except Condition for toys.
- Package length, width, height and weight are stored per variant (`product_variant.package_*`, migration V2, back-filled from the
  listing package where it was valid). `listing.packaging` stays as the default for variants without their own and is set to the
  first variant's package for readers that only know the listing. The event payload carries `packaging` per variant (falling back
  to the listing package for listings not edited yet) and `dangerousGoods`.
- Existing listings are not changed by the migration. They are checked against the rules when they are next edited, so an old
  listing in a category that requires a label photo must get one before it can be saved again. System updates that go through
  the same writer (attaching library images) report such a listing as skipped with the reason.
- The label photo is an image with `role: LABEL`, kept last in `images` so the first picture stays the main image.
- Description is required for every kind (it was optional before).

## 7. Brand rules, the brand picker and brand gating

### 7.1 The brand cell per category (D23)

The kind-level cells are in the matrix of section 4 (Brand row). A category overrides its kind in `rules.json` (`overrides.brand`):
perfumes and fragrances are `required` (kind BEA is brand or reason); hair accessories, wigs, bedding, decor and candles, pet
grooming, handmade products, school and office supplies, sports goods and gift baskets are `optional` (kind HOM is brand or reason,
kind GFT is required); toys and games are brand or reason (kind KID is required; owner decision 18 Nov 2026: toys are relaxed, because handmade and local toys are common; every toy safety gate stays, see D23); fabrics (provisional) are optional. `BrandRulesPerCategoryTest` and the seller center test
`product form: the brand rule is decided per category` write the decision of every category out, so a change to the source cannot
slip through.

| Brand | Categories (count) | Why |
|---|---|---|
| Required, no way out (14) | Baby food & formula, Baby bottles, Dietary supplements, OTC medicines, Medical devices, First aid & wound care, Medicated skincare, Car seats, Perfumes & fragrances, Computers & laptops, Gaming, Phones, TVs & audio, Gift cards | Safety or recall traceability (the brand is the responsible manufacturer, and the safety certificate or FDA listing is filed against it), or counterfeit risk (electronics, fragrance), or the brand is the product (gift cards). If the brand is not in the list the seller adds it. |
| Brand or reason, `R*` (13) | Adult clothing & footwear, Children's clothing, Toys & games, Bags & purses, Jewelry & accessories, Skincare & cosmetics, Haircare, Feminine hygiene, Personal hygiene, Cleaning & paper products, Furniture & small appliances, Kitchenware & cookware, Diapers & wipes | Branded goods that are also made by a tailor, a maker or a small producer. "No brand" or "Handmade" is accepted with no further reason. Hygiene, cleaning and appliance categories keep the question because shoppers and recalls depend on it. |
| Optional (34) | All 15 groceries and food categories (pantry, candy, oils, flours, nuts, snacks, pasta, rice, spices, dairy, produce, frozen meals, meat and fish), all 6 beverage categories, Pet food, Hair accessories, Wigs, Bedding, Decor & candles, Pet grooming, Handmade, School & office supplies, Sports goods, Gift baskets, all 5 digital product categories | Loose, local, store-brand, bulk, handmade and digital goods are normal. The seller may leave it empty or choose "No brand" (listed first). A brand, when given, still feeds search facets and the registry. Pet food keeps its label photo and species rules. |
| Hidden (12) | Bakery products, Prepared meals, Printed books, Magazines and the 8 service categories (Event tickets, Fitness classes, Hair & beauty appointments, Laundry & dry cleaning, Photography & media, Repairs, Residential cleaning, Tutoring & consulting) | The seller is the maker (food service) or there is no goods brand (services, events, tickets). Books and magazines ask for author and publisher through the product type. |

### 7.2 The picker

One combobox (`BrandField`, backed by `GET /v1/seller/catalog/brands`, `.../brands/similar` and `POST .../brands`):

- Searches the brands of the chosen category's department (never another department's), the organisation's most used first.
- Always offers **Add “<typed text>” as a new brand** for any typed name. A name that differs from a listed brand only in case,
  accents or punctuation ("LEVIS", "Levi’s") is not added: the listed brand is returned and used, so no duplicate is created.
- **Did you mean:** while the seller types, the picker asks `GET /v1/brands/similar`. A close name (one or two typing mistakes, or
  the same name with a few letters more; "Nikee" for "Nike") is shown as "Did you mean this brand?" beside the Add row. The
  seller may still add the new name; the review queue then shows "Possible duplicate of Nike" so staff can merge it.
- Optional categories list **No brand** first. Brand-or-reason categories list "No brand" and "Handmade (no brand)" after the brands.
  Required categories list neither, and the tooltip says why a brand is needed.
- A new brand is `PENDING`, visible only to its organisation, attached to the listing, and recorded in the taxonomy audit trail
  as `BRAND_PROPOSED` (actor: the seller organisation). It appears in Staff console, Catalog, Brands, "Waiting for review", naming the
  seller (never an id), the products that hold it, where it applies and the possible duplicates. Staff approve it, merge it into an
  existing brand (the listings follow) or reject it (the listing must pick another brand when next edited). A seller is never
  blocked while it waits.
- **Limits:** names are 2 to 120 characters; letters, digits and `& ' ’ . , - + ( ) ! / ® ™` only, starting with a letter or digit,
  with at least one letter; no web addresses, no e-mail addresses, no profanity (whole words, English, French and Spanish seed list;
  staff catch the rest), no runs of five identical characters, and "Generic", "N/A", "No brand" are read as the "No brand" reason,
  never a brand. At most **10 new brands per organisation per rolling 24 hours** (`at.catalog.brands.max-new-per-org-per-day`) and
  100 awaiting review. A name that matches an existing brand is never refused by a limit. Pending brands are tenant-isolated
  (`Brand.offeredTo`); "did you mean" shows only approved brands and the organisation's own.
- **No squatting:** a typed name that matches an approved brand in the category always resolves to that brand, never a copy. The
  same name in an unrelated department ("Apple" produce and "Apple" phones) is a separate brand, scoped to its department.

### 7.3 Brand gating, hygiene gating and the barcode path

- Gating (D2) is by brand **name**, through the selling rules (`BRAND` and `BRAND_IN_CATEGORY` scopes, names compared without
  case, accents, spaces and punctuation: section 7.5), and is independent of the registry status. In a `brand_gated` category (clothing, bags,
  electronics, perfume) a listing needs the seller's approval only when its brand name is a gated brand. A listing with an
  unbranded reason, or a new brand whose name is not gated, goes live like an open category. A pending brand never blocks a listing.
  A `PENDING` brand whose name equals a gated brand still triggers the gate, because the gate reads the name, not the status, so
  a seller cannot dodge approval by adding a gated brand as "new".
- A required brand category (electronics, perfume) refuses "No brand" with: "This category needs a brand. If it is not listed,
  type it and add it as a new brand." Nothing waits on staff; the new brand is usable at once.
- Hygiene (D9): condition is hidden; the brand stays asked (brand or reason) for hygiene, feminine hygiene and diapers so recalls
  and shopper trust work.
- Barcode (D14): in a brand-gated category a listing without a barcode needs staff to accept the exemption (reason
  `BRAND_GATED_CATEGORY`), except an item the seller declared without a brand (No brand or Handmade with its reason, no brand
  named or chosen): see section 7.6. Outside gated categories a no-barcode listing is checked against the "known GS1
  brand" rule using the brand's id (a brand with enough pages that carry barcodes). A new pending brand has none, so it is not
  flagged; once staff merge it into a known brand its listings are.
- Merge: the products of the merged brand are repointed to the target and republished (search facets follow).

### 7.4 Things this change does not do

- Nothing outstanding about brand names: the gate key folds accents since 7.5.

### 7.5 Gated brand names fold accents like the registry (S631)

- `SellingScope.normaliseBrand` (seller) is the same normalisation as the catalog `BrandNames.normalise` and the Postgres
  `brand_key`: lower case, accents folded, only letters and digits kept. "Hermès", "HERMES" and "Hermes" are one gated brand, in
  `BRAND` scopes and in the brand half of `BRAND_IN_CATEGORY` (`hermes|bags`). The category half is untouched.
- Seller migration `V93__fold_brand_selling_keys.sql` folds the keys stored in `selling_rule`, `selling_approval` and
  `selling_application`. Nothing is lost: every changed key and every merged row is written, with the full old row, to
  `selling_scope_key_fold_log` (read-only history). Two rules that fold to one key merge into the active, stricter one (their audit
  history, approvals and applications follow it; a `MERGE` row in `selling_rule_audit` keeps the other's old content). Two approvals
  of one organisation merge the same way (never across organisations). Two open applications of one organisation for one brand keep
  the first and close the second as a duplicate with an application event. Running it again changes nothing.
- Catalog migration `V11__fold_brand_selling_hold_keys.sql` folds `product_selling_hold` keys, and the live-listing lookup for a brand
  scope compares `brand_key(brand)`, so accented listing brands are found when a rule or approval changes.
- Verified: the SQL on PostgreSQL 16 (collision, second organisation untouched, second run), and the domain tests. Not verified: the
  Flyway run inside the services and a Testcontainers IT (no Docker).

### 7.6 Brand-gated category, no barcode, no brand (S631)

- `GtinExemptionServiceImpl.requirement`: the `brand_gated` approval level alone no longer sends a no-barcode listing to staff when
  the seller declared it without a brand (`brand_reason` UNBRANDED or HANDMADE, and no brand name or id). A named brand, including a
  seller-proposed pending one, follows the gate as before.
- Unchanged on purpose: staff's own gated list (`exemptionGatedCategoryCodes`, for example perfume), the known GS1 brand rule, and every
  safety, hygiene or regulated gate (selling approvals or the category's own approval level, not this barcode rule).
- Tests: `GtinExemptionServiceImplTest`.
