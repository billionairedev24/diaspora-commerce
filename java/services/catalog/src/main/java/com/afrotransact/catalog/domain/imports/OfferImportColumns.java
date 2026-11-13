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
    public static final String MPN = "mpn";
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
        c.add(new ImportColumn(ImportColumns.GTIN, "Barcode (UPC, EAN, ISBN)", "The product's barcode. Give the barcode, "
                + "or the ATIN, or the brand and part number: one of them names the product you sell.", false,
                ColumnKind.GTIN, null, "4006381333931", false));
        c.add(new ImportColumn(ImportColumns.ATIN, "ATIN", "The product's AfroTransact number (AT followed by 8 letters or "
                + "digits), when you do not have the barcode.", false, ColumnKind.TEXT, null, "", false));
        c.add(new ImportColumn(ImportColumns.BRAND, "Brand", "With Part number, when the product has no barcode.", false,
                ColumnKind.TEXT, null, "", false));
        c.add(new ImportColumn(MPN, "Part number (MPN)", "The manufacturer's part number, with Brand.", false,
                ColumnKind.TEXT, null, "", false));
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
                new ImportColumn(SELLER_SKU, "Seller SKU", "Your code for the offer. Give the SKU, or the ATIN.", false,
                        ColumnKind.TEXT, null, "MY-SKU-1", false),
                new ImportColumn(ImportColumns.ATIN, "ATIN", "The product's AfroTransact number, when there is no SKU.",
                        false, ColumnKind.TEXT, null, "", false),
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
        all.add(new ImportColumn(MPN, "MPN", "", false, ColumnKind.TEXT, null, "", false));
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
                "One row per offer. Name the product by its barcode, or its ATIN, or its brand and part number. The product "
                        + "page already has the photos, title and details, so this file has no image, title or attribute "
                        + "columns: anything you add there is ignored.",
                "Price is required. Quantity is needed for items you ship yourself. Blank condition means New; anything "
                        + "else needs a condition note.",
                "A product that is not on AfroTransact yet is not found here: add it with the products template.",
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
                "One row per offer. Name it by your Seller SKU, or by its ATIN. Only your own offers can be changed.",
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
