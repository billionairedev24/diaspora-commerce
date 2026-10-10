package com.afrotransact.content.domain.flag;

/** Evaluation reasons, named as in the OpenFeature specification. */
public enum FlagReason {
    STATIC,
    TARGETING_MATCH,
    SPLIT,
    DISABLED,
    DEFAULT
}
