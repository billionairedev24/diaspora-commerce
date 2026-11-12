package com.afrotransact.inventory.domain;

/**
 * A problem found in a row of a stock file.
 *
 * @param column   the column key (seller_sku, location, on_hand, lot, expiry); null for the whole row
 * @param severity ERROR (the row is not applied) or WARNING (applied, the seller should know)
 */
public record StockRowMessage(String column, String severity, String message) {

    public static final String ERROR = "ERROR";
    public static final String WARNING = "WARNING";

    public static StockRowMessage error(String column, String message) {
        return new StockRowMessage(column, ERROR, message);
    }

    public static StockRowMessage warning(String column, String message) {
        return new StockRowMessage(column, WARNING, message);
    }

    public boolean isError() {
        return ERROR.equals(severity);
    }
}
