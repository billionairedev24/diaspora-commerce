package com.afrotransact.bookings.domain;

/** {@link TakeHomeCalculator} over a {@link FeeSchedule} (the design's {@code settle}). */
public class DefaultTakeHomeCalculator implements TakeHomeCalculator {

    @Override
    public TakeHome takeHome(FeeSchedule fees, long priceMinor, long depositMinor, String currency) {
        long balance = priceMinor - depositMinor;
        long commission = fees.commissionOn(priceMinor);
        long card = fees.cardFeeOn(depositMinor) + fees.cardFeeOn(balance);
        return new TakeHome(priceMinor, depositMinor, balance, fees.commissionBps(), commission, card,
                priceMinor - commission - card, currency, fees.source(), fees.version());
    }

    @Override
    public BookingEarnings earnings(FeeSchedule fees, Booking b) {
        long deposit = b.depositCapturedMinor();
        long balance = b.balanceCapturedMinor();
        long refunded = b.refundedMinor();
        long collected = deposit + balance - refunded;
        long commission = fees.commissionOn(collected);
        long card = fees.cardFeeOn(deposit) + fees.cardFeeOn(balance);
        long held = b.paymentState() == PaymentState.AUTHORIZED ? b.service().depositMinor() : 0;
        long due = b.status().isActive() ? b.service().priceMinor() - b.service().depositMinor() : 0;
        long net = collected - commission - card - b.providerFeeMinor();
        return new BookingEarnings(held, deposit, balance, refunded, collected, fees.commissionBps(), commission, card,
                b.providerFeeMinor(), net, due, b.service().currency());
    }
}
