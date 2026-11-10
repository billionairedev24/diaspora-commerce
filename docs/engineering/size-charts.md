# Size charts and seller-written sizes (fashion)

Fashion shoppers return clothes that do not fit. Sellers therefore write their own **size charts** (a table of
measurements per size) and their own **size labels** ("UK 12", "Free size", "EU 38", "Age 6-7"), and the product page
shows the chart beside the size picker. Nothing in the code knows a category by name: the **product type** decides
whether a form shows sizes, whether a chart is required and what it suggests.

## Who creates what

| Who | What |
|---|---|
| Staff (Catalog › Categories › edit the details of a product type) | Switch the **sizing** trait on or off for a product type, say whether a size chart is **required**, and list the **suggested measurements** (name, unit, one line on how to measure). They can also mark a variation dimension as seller-extensible (`allowCustomValues`). They never write charts. |
| Seller (or a team member who may edit listings) | Writes the charts of their own organisation, writes any size or colour label the category does not list, links one chart to each product and says how it fits. |
| The category | Suggests sizes (the dimension's options), measurement columns and a unit. These are only suggestions: the seller may use, rename, drop or add. |

## The model

`size_chart` (catalog, in the baseline `V1__baseline.sql`):

- `owner_org` the seller organisation that wrote it. A chart is **never shared**: a product can link only a chart
  of its own organisation.
- `name` (2–80 characters; unique per organisation ignoring case among charts that are not retired), `unit_system`
  (`CM` or `IN`), the product type and category node it was made for (a hint for filtering, not a restriction),
  ordered `columns` (measurement key, label, unit), `rows` (a size and, per column, a single value or a min–max range),
  `fit_note` (`RUNS_SMALL`, `TRUE_TO_SIZE`, `RUNS_LARGE`), `how_to_measure` text, `version` (optimistic locking) and
  `retired_at` (soft retire: a retired chart keeps showing on the products that use it but can no longer be linked).
- A product's `listing` holds `sizeChartId` and `fitNote` (the product's own fit note wins over the chart's).
- A variation dimension is `{name, options, allowCustomValues}`; `allowCustomValues` defaults to `false`.
- The product-type **sizing trait** is `{sizeChartRequired, sizeDimension, suggestedMeasurements[]}` in the type's
  `traits` document. Initial data: the fashion types are sized in the generated `V1_1__initial_data.sql` (dresses, tops, menwear and
  kids are sized; Size and Color of those types allow custom values, headwraps allow custom colours; nothing requires a
  chart yet, staff can switch that on).

The rules live in the domain without Spring (`domain/sizing`): `SizeChartRules` (the chart itself),
`SizeChartPolicy` / `DefaultSizeChartPolicy` (a product against its chart), `CustomValues` and `VariantOptions`
(seller-written labels).

## Rules

Chart: 2–80 character name; 1–12 measurements (label 1–40 characters, key derived from the label, `a–z 0–9 _`); 1–60
sizes; each measurement a number above 0 and at most 1000 with at most 2 decimals, or a range where min is not above
max; a value for **every** measurement of **every** size; no two sizes equal ignoring case and spacing; columns use the
chart's unit; `howToMeasure` at most 1000 characters. Conversion to the other unit (`alternateValues`) is one decimal
(`1 in = 2.54 cm`).

Custom size and colour labels (only where the dimension has `allowCustomValues`): at most 30 characters, letters,
digits, spaces and `. , / + ( ) ' & # : _ - –`, starting with a letter or digit, at most 40 different own values per
dimension on one product, and **no case-insensitive duplicates** ("XL" and "xl" are one size; the category's spelling is
used when there is one, otherwise the first spelling on the product).

Product against its chart:

1. Where the type is not sized, no chart can be linked.
2. Where `sizeChartRequired`, the product needs a chart.
3. The chart must belong to the product's organisation and not be retired (a product that already links a chart that
   was retired later keeps it).
4. **Every size the variants sell needs a row** in the chart (ignoring case); the message is attached to the first
   offending variant: `variants[i].options.Size`.
5. A chart edit cannot remove the row of a size that products still sell (422 on `rows`).

Errors are RFC 9457 problems with `errors` keyed by field, e.g. `name`, `columns[1].label`, `rows[2].size`,
`rows[2].values.bust`, `listing.sizeChartId`, `variants[3].options.Size`.

## API

Catalog (`contracts/openapi/catalog.yaml`, tag *Size charts*); authorisation follows the product pattern (the owner
organisation's `catalog:write` for writes; staff can read only):

| Method and path | Purpose |
|---|---|
| `GET /v1/size-charts?productTypeId=&productType=&q=&includeRetired=&page=&size=` | The organisation's charts, newest change first |
| `POST /v1/size-charts` | Write a chart (201) |
| `GET /v1/size-charts/{id}` | One chart |
| `PUT /v1/size-charts/{id}` | Replace the content; send the `version` you loaded (409 when stale) |
| `DELETE /v1/size-charts/{id}` | **Retire** (never deleted); answers the retired chart |
| `POST /v1/size-charts/{id}/duplicate` | Copy under `{name}` or "… (copy)" |
| `GET /v1/size-charts/suggestions?productTypeId=` | The category's suggested sizes, measurement columns, unit and whether a chart is required |
| `GET /v1/size-charts/import-template?productType=&format=xlsx\|csv` | The size chart upload template |
| `POST /v1/size-charts/import` | Upload charts (multipart `file`, `dryRun`, optional `productType`) |

Product endpoints accept `listing.sizeChartId` and `listing.fitNote` (both optional, so older clients keep working);
`GET /v1/products/{atin}` (public) returns `sizeChart` (name, units, columns, rows with `values` and `alternateValues`,
`fitNote`, `howToMeasure`) or `null`, and each variant's `options` (`{"Size": "UK 12"}`) so the page can highlight the
row of the shopper's size. The staff product-type endpoint takes `traits.sizing` (`{enabled:false}` switches it off;
fields left out keep their value) and `themes` (the whole list of variations).

The seller BFF exposes the same under `/v1/seller/size-charts` (see `contracts/openapi/gateway-bff-seller.yaml`).
Charts need the *listings* module (View to read, Edit to write); the template and upload also need bulk upload on the
plan, as the product upload does. No events are published: a chart is read with the product.

## Bulk upload

### Size charts

Template: `GET /v1/size-charts/import-template?productType=dress&format=csv|xlsx` (sheet **Size charts** plus
**Instructions**). **One row per size; rows with the same Size chart name make one chart.** The fixed columns are
`Size chart name *`, `Unit` (`cm` or `in`, default cm), `Size *`, then **one column per measurement**, then `Size fit`
(`Runs small`, `True to size`, `Runs large`) and `How to measure`. The measurement columns of the template are the
category's suggestions; keep, rename or delete them and add your own: any other column header is a measurement. The
unit, fit and how-to-measure can be written on a chart's first row only. Values are a number (`88`) or a range
(`86-90`). A file has one set of measurement columns, so charts with different measurements go in different files.

```csv
Size chart name *,Unit,Size *,Bust *,Waist *,Hip *,Size fit,How to measure
Adire dresses,cm,XS,80-83,62-65,88-91,True to size,"Measure over light clothing, keeping the tape level."
Adire dresses,,S,84-87,66-69,92-95,,
Adire dresses,,UK 12,89-93,71-75,97-101,,
Adire dresses,,Free size,88-110,70-96,96-120,,
```

Behaviour: every row is validated and reported with its column (`chart_name`, `unit`, `size`, `fit_note`,
`m:<measurement key>`). A chart is saved **whole or not at all** (if any row of a chart has an error, the chart fails
and the other rows say so). **Dry run** (`dryRun=true`) reports what would be created, updated or left unchanged without
saving. **Idempotent by chart name** (ignoring case): a new name creates, an existing name updates the chart (version
increases), the same file again changes nothing (`UNCHANGED`). A size that products still sell cannot be dropped by an
update. Limits: 10 MB and 5,000 rows per file.

### Products

The product template of a sized type gets two extra columns after the variation options: **`Size chart`** (the
*name* of one of the organisation's charts; required when the type requires one) and **`Size fit`**. The chart and fit named on the first row of a
product (same Parent SKU) apply to all its variants. The **Size** and **Color** columns are dropdowns that do not reject other
text: pick a suggestion or type your own ("UK 12", "Indigo adire"). Write charts first, then products. Rows report
`size_chart` ("No size chart called … in your account", 'Size "XXL" has no row in the size chart …'), `fit_note`, and the usual column
messages; the generic template has the same two columns. Updates (`mode=UPDATE`) change the link and fit note like
any other column.

```csv
Parent SKU,SKU,Category,Title,Brand,Price (USD),Quantity,No barcode,Variation,Options,Attributes,Size chart,Size fit
ADIRE-WRAP,ADIRE-WRAP-S-INDIGO,Fashion › Women › Dresses,Adire Wrap Dress in Indigo Cotton,Handmade,85.00,6,Yes,Size × Color,Size: S; Color: Indigo adire,material: Cotton; fit: Regular; length: Midi,Adire dresses,True to size
ADIRE-WRAP,ADIRE-WRAP-UK12-INDIGO,Fashion › Women › Dresses,Adire Wrap Dress in Indigo Cotton,Handmade,85.00,4,Yes,Size × Color,Size: UK 12; Color: Indigo adire,material: Cotton; fit: Regular; length: Midi,Adire dresses,True to size
```

Sample files: [`samples/size-charts-womens-dresses.csv`](samples/size-charts-womens-dresses.csv),
[`samples/size-charts-mens-tops.csv`](samples/size-charts-mens-tops.csv) and
[`samples/products-sized-dresses.csv`](samples/products-sized-dresses.csv). The two chart samples are validated by
`SizeChartFilesTest`.

## Shopper size guide (storefront and customer app)

The product page reads `sizeChart` and each variant's `options` from `GET /v1/products/{atin}` and does the rest on the
client with the shared helpers in `web/packages/api-client/src/size-charts.ts` (the web storefront and the customer app
import the same module, so the two cannot disagree):

- **Option chips** (`pickerDimensions`): one row per option name (Size, Color ...), each value once, in order of
  appearance. A chip is `selected`, `available` (changing only this value gives an in-stock variant), `elsewhere` (that
  exact combination is sold out or not made, but the value is in stock in another combination: the click goes to the
  closest in-stock variant and the chip is dashed and announced as such) or `unavailable` (nothing in stock has it;
  shown struck through, not clickable). Variants without `options` keep the old list of variant labels.
- **Size guide** (`SizeGuide` on the web, a bottom sheet in the app): opened from the "Size guide" link beside the Size
  chips (or on its own when the product has one variant). It shows the seller's chart in cm or inches (`guideUnits`; the
  chart's own unit first, the server's `alternateValues`, converted client-side only if missing), the fit note and
  how-to-measure text, and highlights the row of the selected variant's `Size` (`selectedRowIndex`, ignoring case and
  spacing). Every other row has **Select**, which goes to the in-stock variant of that size, closest to the current
  colour (`variantForSize`); a size that is sold out everywhere says so.
- The unit the shopper picks is remembered on the device (`localStorage` on the web, secure storage in the app) and used
  next time if the chart offers it.
- A product with no `sizeChart` shows no link; one with a chart but a size that has no row shows "Size X is not in this
  chart" instead of a highlighted row.
- Copy is in the five locales (`pdp.chip.*`, `sizeGuide.*` in the storefront catalogues, `cu.pdp.chip.*`,
  `cu.sizeGuide.*` in the app); non-English strings are machine translations marked for review.

Mock data: the web mock catalogue has "Adire Wrap Dress" (`AT000000P24`, 4 sizes x 2 colours, linked to the seeded dress
chart; the other variants are `unlisted` so search shows it once); the customer app mock has the same garment
(`ATADIRE000`, variants `ATADIRE0<colour><size>`).

## For sellers (plain words)

*Why a size chart?* Shoppers cannot try clothes on. A chart that says what "M" or "UK 12" means in centimetres or
inches helps them choose, and fewer items come back. **Listings › Size charts** is where you write them; you only write
a chart once and use it for many products.

1. Choose the category the chart is for. We suggest sizes and measurements (for dresses: bust, waist, hip); keep, rename
   or add your own.
2. Pick **cm** or **inches**; shoppers can switch.
3. Enter each size's measurement as one number (`88`) or a range (`86-90`).
4. Add how to measure and whether your sizes run small, true to size or large.
5. When you list a product, choose the chart; every size you sell needs a row in it. You can type your own sizes and
   colours ("UK 12", "Free size", "Indigo adire") if ours do not fit. "XL" and "xl" count as one size.

You can retire a chart you no longer use; products that already use it keep showing it. You cannot take a size out of a
chart while a product still sells it.

## Tests

`SizeChartRulesTest`, `DefaultSizeChartPolicyTest`, `CustomVariationValuesTest`, `TaxonomySizingRulesTest`,
`ImportSizingTest`, `ChartImportValidatorTest`, `SizeChartFilesTest`, `SizeChartServiceTest`, `ProductWriterSizingTest`,
`SizeChartDtosTest`, `JsonCodecSizingTest` and the ArchUnit `ArchitectureTest`; `SizeChartIT` (real HTTP, Flyway and
multipart; needs Docker or a local Postgres); gateway-bff `seller_size_charts_test.go` and the contract conformance tests;
api-client `size-charts.test.ts`, `size-guide.test.ts`, `staff-taxonomy.test.ts`; storefront `test/size-guide.test.tsx`
(RTL, mock BFF) and `e2e/size-guide.spec.ts` (Playwright); customer app `test/mock.test.ts` and the Maestro flow
`e2e/07-size-guide.yaml`.


## Under the category tax tree

The sized product types are `dress`, `top`, `menwear` and `footwear` (under *Adult clothing & footwear*) and `kids` (under *Children's clothing*). Their sizing trait, themes (Size and Color open to the seller's own values) and suggested measurements come from `tools/seed/catalog_taxonomy.py` and are loaded by the taxonomy importer ([taxonomy.md](./taxonomy.md)); `footwear` suggests *Foot length*. The chart-quality rules above are unchanged.
