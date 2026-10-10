package com.afrotransact.bookings.domain;

/**
 * Inputs of a Custom cancellation policy (Provider Hub defaults: free 48 h, 50 % from 24 h, deposit kept on no-show,
 * reschedule free up to 24 h, max 2 reschedules).
 *
 * @param freeHours          free cancellation up to this many hours before
 * @param partialRefund      whether a partial refund window exists
 * @param partialFromHours   start of the partial window (must be below freeHours)
 * @param partialPercent     percentage of the deposit refunded in the partial window
 * @param noShow             what a no-show costs
 * @param rescheduleHours    free reschedule up to this many hours before
 * @param maxReschedules     how many times a customer may reschedule
 */
public record CustomPolicySettings(int freeHours, boolean partialRefund, int partialFromHours, int partialPercent,
                                   NoShowRule noShow, int rescheduleHours, int maxReschedules) {

    public static CustomPolicySettings defaults() {
        return new CustomPolicySettings(48, true, 24, 50, NoShowRule.DEPOSIT, 24, 2);
    }
}
