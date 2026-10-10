package com.afrotransact.retail.domain;

/** Where good units of a receipt line go: on sale, or to quarantine (short-dated or QA failed). */
public enum Disposition {
    SELLABLE,
    QUARANTINE
}
