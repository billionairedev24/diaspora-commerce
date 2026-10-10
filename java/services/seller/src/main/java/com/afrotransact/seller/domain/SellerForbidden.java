package com.afrotransact.seller.domain;

/** A domain rule forbids this actor from taking the action (HTTP 403), e.g. the reviewer giving final approval. */
public class SellerForbidden extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public SellerForbidden(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
