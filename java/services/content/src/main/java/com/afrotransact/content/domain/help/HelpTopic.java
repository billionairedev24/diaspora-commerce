package com.afrotransact.content.domain.help;

/** The help center's topics, in display order. */
public enum HelpTopic {

    ORDERS_DELIVERY("Orders & delivery"),
    RETURNS_REFUNDS("Returns & refunds"),
    ACCOUNT_PAYMENTS("Your account & payments"),
    SELLING("Selling & advertising"),
    COMPANY("About AfroTransact");

    private final String title;

    HelpTopic(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
