package com.afrotransact.catalog.domain.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * Columns and templates of the offer upload (mode OFFER, S393) and the price and quantity feed (mode PRICE_QTY, S394).
 * Neither has an image, title or attribute column: an offer sells a page that already exists, and the page owns its content.
 * The templates are generated here, never per category.
 */
public final class OfferImportColumns {

    public static final String SELLER_SKU = "seller_sku";
    public static final String NAME = "product_name";
    public static final String SIZE = "size_pack";
    public static final String CATEGORY_HINT = "category_hint";
    public static final String CONDITION_NOTE = "condition_note";
    public static final String LIST_PRICE = "list_price";
    public static final String SHIPPING = "shipping";
    public static final String SHIP_CITY = "ship_from_city";
    public static final String SHIP_REGION = "ship_from_region";
    public static final String SHIP_COUNTRY = "ship_from_country";
    public static final String SHIP_ZIP = "ship_from_zip";

    private static final List<String> FULFILMENT_LABELS = List.of("Ships from you", "Fulfilled by AfroTransact");

    private OfferImportColumns() {
    }

    /** The columns of the offer template, in order. */
    public static List<ImportColumn> offer() {
        List<ImportColumn> c = new ArrayList<>();
        c.add(new ImportColumn(NAME, "Product name", "What the product is called, as a shopper would search for it, for "
                + "example Tilda basmati rice. We look for it on AfroTransact. A barcode or an ATIN, when you have one, "
                + "is the surest way.", false, ColumnKind.TEXT, null, "Tilda basmati rice", false));
        c.add(new ImportColumn(CATEGORY_HINT, "Category", "Optional. It helps us find the right product.", false,
                ColumnKind.TEXT, null, "", false));
        c.add(new ImportColumn(ImportColumns.BRAND, "Brand", "Optional.", false, ColumnKind.TEXT, null, "Tilda", false));
        c.add(new ImportColumn(SIZE, "Size or pack", "Optional but important: 5 kg, 500 ml, 6 pack. A different size is a "
                + "different product.", false, ColumnKind.TEXT, null, "5 kg", false));
        c.add(new ImportColumn(ImportColumns.GTIN, "Barcode (UPC, EAN, ISBN)", "Optional. If you have the barcode, it is "
                + "an exact match and the other columns above are not needed.", false, ColumnKind.GTIN, null, "", false));
        c.add(new ImportColumn(ImportColumns.ATIN, "ATIN", "Optional. The product's AfroTransact number (AT followed by 8 "
                + "letters or digits), if you already have it.", false, ColumnKind.TEXT, null, "", false));
        c.add(new ImportColumn(SELLER_SKU, "Seller SKU", "Your own code for this offer. Leave it blank and we make one "
                + "from the product's ATIN.", false, ColumnKind.TEXT, null, "MY-SKU-1", false));
        c.add(new ImportColumn(ImportColumns.CONDITION, "Condition", "New unless stated. Used and renewed only where the "
                + "category allows it.", false, ColumnKind.ENUM, ImportColumns.conditionLabels(), "New", false));
        c.add(new ImportColumn(CONDITION_NOTE, "Condition note", "Required for anything but New: what is different about "
                + "the item (at most 200 characters).", false, ColumnKind.TEXT, null, "", false));
        c.add(new ImportColumn(ImportColumns.PRICE, "Price", "Your price in US dollars, for example 24.99.", true,
                ColumnKind.MONEY, null, "24.99", false));
        c.add(new ImportColumn(LIST_PRICE, "List price", "The price you show crossed out. At least the price. Blank for none.",
                false, ColumnKind.MONEY, null, "", false));
        c.add(new ImportColumn(ImportColumns.QUANTITY, "Quantity", "How many you have. Needed when you ship it yourself.",
                false, ColumnKind.INTEGER, null, "10", false));
        c.add(new ImportColumn(SHIPPING, "Shipping charge", "What the shopper pays you for shipping, in US dollars. "
                + "Blank is free shipping.", false, ColumnKind.MONEY, null, "0", false));
        c.add(new ImportColumn(ImportColumns.FULFILMENT, "Fulfilment", "Ships from you, or Fulfilled by AfroTransact "
                + "(stock sent in with an inbound plan).", false, ColumnKind.ENUM, FULFILMENT_LABELS, "Ships from you",
                false));
        c.add(new ImportColumn(ImportColumns.HANDLING, "Handling days", "Days to hand the order to the carrier, 0 to 30.",
                false, ColumnKind.INTEGER, null, "2", false));
        c.add(new ImportColumn(SHIP_CITY, "Ship from city", "Where you ship from. Blank uses your country only.", false,
                ColumnKind.TEXT, null, "Houston", false));
        c.add(new ImportColumn(SHIP_REGION, "Ship from state", "Two-letter state or region.", false, ColumnKind.TEXT, null,
                "TX", false));
        c.add(new ImportColumn(SHIP_COUNTRY, "Ship from country", "Two-letter country code. Blank means US.", false,
                ColumnKind.TEXT, null, "US", false));
        c.add(new ImportColumn(SHIP_ZIP, "Ship from ZIP", "ZIP or postal code.", false, ColumnKind.TEXT, null, "77002",
                false));
        c.add(new ImportColumn(ImportColumns.LENGTH, "Package length (in)", "Only for Fulfilled by AfroTransact: the box "
                + "you ship it in. Give length, width, height and weight together.", false, ColumnKind.DECIMAL, null, "",
                false));
        c.add(new ImportColumn(ImportColumns.WIDTH, "Package width (in)", "Fulfilled by AfroTransact only.", false,
                ColumnKind.DECIMAL, null, "", false));
        c.add(new ImportColumn(ImportColumns.HEIGHT, "Package height (in)", "Fulfilled by AfroTransact only.", false,
                ColumnKind.DECIMAL, null, "", false));
        c.add(new ImportColumn(ImportColumns.WEIGHT, "Package weight (lb)", "Fulfilled by AfroTransact only.", false,
                ColumnKind.DECIMAL, null, "", false));
        return List.copyOf(c);
    }

    /** The columns of the price and quantity feed. */
    public static List<ImportColumn> priceQty() {
        return List.of(
                new ImportColumn(SELLER_SKU, "Seller SKU", "Your own code for the listing. This is the key we use. Give the "
                        + "SKU; the ATIN or the barcode is only for a row without one.", false, ColumnKind.TEXT, null,
                        "MY-SKU-1", false),
                new ImportColumn(ImportColumns.ATIN, "ATIN", "The product's AfroTransact number, when there is no SKU.",
                        false, ColumnKind.TEXT, null, "", false),
                new ImportColumn(ImportColumns.GTIN, "Barcode (UPC, EAN, ISBN)", "Only when there is no SKU or ATIN. Used "
                        + "only if it matches exactly one of your listings; otherwise use your SKU.", false,
                        ColumnKind.GTIN, null, "", false),
                new ImportColumn(ImportColumns.PRICE, "Price", "New price in US dollars. Blank leaves it as it is.", false,
                        ColumnKind.MONEY, null, "22.50", false),
                new ImportColumn(LIST_PRICE, "List price", "New crossed-out price. Blank leaves it; 0 removes it.", false,
                        ColumnKind.MONEY, null, "", false),
                new ImportColumn(ImportColumns.QUANTITY, "Quantity", "New stock of an item you ship yourself. Blank leaves "
                        + "it as it is.", false, ColumnKind.INTEGER, null, "8", false));
    }

    /** Columns read from a file of this mode: the template's, plus the names sellers also use. */
    public static List<ImportColumn> readable(ImportMode mode) {
        List<ImportColumn> all = new ArrayList<>(mode == ImportMode.PRICE_QTY ? priceQty() : offer());
        all.add(new ImportColumn(SELLER_SKU, "SKU", "", false, ColumnKind.TEXT, null, "", false));
        all.add(new ImportColumn(SELLER_SKU, "Seller SKU *", "", false, ColumnKind.TEXT, null, "", false));
        all.add(new ImportColumn(LIST_PRICE, "Compare at price", "", false, ColumnKind.MONEY, null, "", false));
        all.add(new ImportColumn(ImportColumns.GTIN, "Barcode", "", false, ColumnKind.GTIN, null, "", false));
        all.add(new ImportColumn(ImportColumns.GTIN, "GTIN", "", false, ColumnKind.GTIN, null, "", false));
        all.add(new ImportColumn(NAME, "Name", "", false, ColumnKind.TEXT, null, "", false));
        all.add(new ImportColumn(SIZE, "Size", "", false, ColumnKind.TEXT, null, "", false));
        all.add(new ImportColumn(SIZE, "Pack", "", false, ColumnKind.TEXT, null, "", false));
        // The product upload's own headers, so a CREATE row whose barcode matches a page is read as an offer (S393).
        all.add(new ImportColumn(ImportColumns.PRICE, "Price (USD)", "", false, ColumnKind.MONEY, null, "", false));
        all.add(new ImportColumn(LIST_PRICE, "Compare-at price (USD)", "", false, ColumnKind.MONEY, null, "", false));
        all.add(new ImportColumn(ImportColumns.HANDLING, "Handling time (days)", "", false, ColumnKind.INTEGER, null, "",
                false));
        all.add(new ImportColumn(ImportColumns.GTIN, "Barcode (GTIN/UPC/EAN)", "", false, ColumnKind.GTIN, null, "", false));
        all.add(new ImportColumn(ImportColumns.GTIN, "ISBN-13", "", false, ColumnKind.GTIN, null, "", false));
        return all;
    }

    public static TemplateSpec offerTemplate() {
        return new TemplateSpec("Sell products already on AfroTransact", "afrotransact-offers", offer(), List.of(
                "One row per product you sell. Type the product's name and its size or pack (a different size is a different "
                        + "product). We look for it on AfroTransact. If we find exactly the same product, your row is added "
                        + "to it. If we are not sure, the row is left out and the report shows the products we found, so "
                        + "nothing is matched on a guess. The product page already has the photos and details, so this file "
                        + "has no image or attribute columns: anything you add there is ignored.",
                "Price is required. Quantity is needed for items you ship yourself. Blank condition means New; anything "
                        + "else needs a condition note.",
                "A product that is not on AfroTransact yet is not found here: add it with the products template or Add a product.",
                "If you do not have the tax approval for an item yet, its row is saved and held. It goes on sale by itself "
                        + "when the tax details are done. Held rows are never for sale.",
                "Upload as .xlsx or .csv: at most 10 MB and 5,000 rows per file. Upload the same file again to change "
                        + "prices: rows that did not change are left alone and nothing is created twice.",
                "Keep the header row: columns are matched by their names, so you can hide or reorder them. Rows whose "
                        + "first cell starts with # are ignored."),
                List.of(example(offer())), "Offers");
    }

    public static TemplateSpec priceQtyTemplate() {
        return new TemplateSpec("Price and quantity feed", "afrotransact-price-quantity", priceQty(), List.of(
                "One row per listing. Name it by your Seller SKU. The ATIN is also accepted. A barcode is accepted only when it "
                        + "matches exactly one of your listings; if it matches more than one, the row is left out and "
                        + "says to use your SKU. Only your own listings can be changed.",
                "Blank cells leave the value as it is. List price 0 removes the crossed-out price. Quantity changes items "
                        + "you ship yourself; stock of Fulfilled by AfroTransact items comes from the fulfilment centre.",
                "A price change never puts an item on sale: an item waiting for its tax approval stays held.",
                "Upload as .xlsx or .csv: at most 25 MB and 50,000 rows per file. Uploading the same file again changes "
                        + "nothing.",
                "Keep the header row: columns are matched by their names, so you can hide or reorder them. Rows whose "
                        + "first cell starts with # are ignored."),
                List.of(example(priceQty())), "Feed");
    }

    private static List<String> example(List<ImportColumn> columns) {
        return columns.stream().map(ImportColumn::example).toList();
    }
}
