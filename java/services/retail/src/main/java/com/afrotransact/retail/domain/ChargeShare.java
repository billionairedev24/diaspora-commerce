package com.afrotransact.retail.domain;

/** The part of one charge allocated to one line. */
public record ChargeShare(ChargeType type, AllocationBasis basis, long amountMinor) {
}
