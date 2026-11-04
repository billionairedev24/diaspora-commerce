package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.Optional;

import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;

/**
 * Tells the customers of a pre-order window that their delivery is on its way: the window's OUT_FOR_DELIVERY message
 * ({@code preorder.comms.due}), sent when the run the window's orders were handed to leaves with them. The driver
 * events (or staff) move the run, so "out for delivery" is true when the customer hears it; each window says it once
 * ({@code PreorderStepLog}, step {@code COMMS_OUT_FOR_DELIVERY}).
 */
public interface PreorderDeliveryDayService {

    /** The reason marker run events of a window's run carry ({@code PREORDER <code>}); notifications keys on it. */
    String REASON_PREFIX = "PREORDER ";

    /**
     * The run left with {@code orders}. When it was handed over from a pre-order window the window's customers are
     * told once (unless the window is paused or has switched the message off).
     *
     * @return the reason marker for the run's own {@code out_for_delivery} event ({@code PREORDER <code>}, so the
     *         generic run message is not sent on top of the window's), empty for a run staff scheduled
     */
    Optional<String> onRunOutForDelivery(Run run, List<RunOrder> orders);
}
