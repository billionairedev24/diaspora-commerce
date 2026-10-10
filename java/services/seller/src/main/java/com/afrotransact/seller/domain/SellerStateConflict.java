package com.afrotransact.seller.domain;

/** The aggregate is in a state that does not allow the change (HTTP 409), e.g. reviewing a draft application. */
public class SellerStateConflict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public SellerStateConflict(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
