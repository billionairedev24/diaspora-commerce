package com.afrotransact.bookings.domain;

/**
 * What a provider receives for a service at its listed price ("You receive" on Services &amp; prices): the deposit
 * and the balance are charged separately, each paying the card fee.
 */
public record TakeHome(long priceMinor, long depositMinor, long balanceMinor, int commissionBps, long commissionMinor,
                       long cardFeesMinor, long takeHomeMinor, String currency) {
}
