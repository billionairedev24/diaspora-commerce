package com.afrotransact.payments.application;

import java.time.Duration;
import java.time.ZoneId;

/**
 * Runtime settings of the payments use cases.
 *
 * @param retailOrgId         AfroTransact Retail (1P seller of record, pays no commission)
 * @param settlementHold      time after delivery / service completion before sellers' net is transferred (7 days)
 * @param payoutZone          zone of the "one payout request per day" rule
 * @param currency            default currency of balances
 * @param webhookMaxAttempts  attempts before a failing Stripe event is parked as FAILED
 * @param retryRecheck        delay before a settlement that could not be fully transferred is checked again
 */
public record PaymentsSettings(String retailOrgId, Duration settlementHold, ZoneId payoutZone, String currency,
                               int webhookMaxAttempts, Duration retryRecheck) {
}
