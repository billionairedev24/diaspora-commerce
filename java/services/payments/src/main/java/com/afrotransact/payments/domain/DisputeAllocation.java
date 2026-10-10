package com.afrotransact.payments.domain;

/** Part of a disputed amount borne by one recipient ({@link PaymentSplitter#PLATFORM} for AfroTransact). */
public record DisputeAllocation(String recipientId, Money amount) {
}
