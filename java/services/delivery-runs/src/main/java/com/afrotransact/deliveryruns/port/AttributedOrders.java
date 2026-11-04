package com.afrotransact.deliveryruns.port;

/**
 * The orders an advertorial brought (S263), counted on one basis for pre-order windows and delivery runs: an order
 * that came through the advertorial's link (its {@code cr=adv-...} attribution) is <em>live</em> while it still stands
 * and <em>cancelled</em> once it does not. A window's pre-order stands unless it is CANCELLED (paid and late adds
 * count); a run's order stands while it holds a slot on a run (RESERVED or CONFIRMED) and is cancelled when its slot
 * was released or its run was cancelled and refunded. An order moved to another run stands.
 *
 * @param live      orders that still stand
 * @param cancelled orders that were cancelled or refunded
 */
public record AttributedOrders(int live, int cancelled) {

    public static final AttributedOrders NONE = new AttributedOrders(0, 0);
}
