package com.afrotransact.payments.domain;

import java.util.List;

/**
 * Turns "refund this much of these lines" into line-level refund amounts (items / shipping / tax), so service callers
 * (trust-ops claims) refund by order and line ids without knowing what each line still holds.
 */
public interface RefundAllocator {

    /**
     * @param lines  captured lines to refund from, in the order given
     * @param amount total to refund; null = everything still refundable on the lines
     * @return one share per line with a positive amount
     */
    List<Share> allocate(List<PaymentLine> lines, Money amount);

    /** What to refund of one line. */
    record Share(String lineId, Money items, Money shipping, Money tax) {

        public Money total() {
            return items.plus(shipping).plus(tax);
        }
    }
}
