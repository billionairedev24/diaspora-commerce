package com.afrotransact.seller.domain;

/** How a case that enters a stage is given to a person: nobody (a pool to claim from), in turn, or the least busy. */
public enum AutoAssignMode {
    NONE,
    ROUND_ROBIN,
    LEAST_LOADED
}
