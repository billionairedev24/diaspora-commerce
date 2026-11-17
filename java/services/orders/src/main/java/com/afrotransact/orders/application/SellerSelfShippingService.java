package com.afrotransact.orders.application;

import java.util.Collection;
import java.util.List;

/**
 * A seller's own shipping charge (behind the staff switch, off by default). The minimum rule is one flat charge per order
 * package of the seller, set once here (docs/engineering/seller-self-shipping-options.md). Every write is refused while the
 * switch is off (409 {@code orders.self-shipping-disabled}) and every read says the option is not available, so no screen or
 * checkout can offer it by accident.
 */
public interface SellerSelfShippingService {

    /**
     * A seller's own setting. {@code available} is the staff switch; {@code enabled} and {@code chargeMinor} are the
     * seller's own (false and 0 when never set).
     */
    record Setting(String sellerOrgId, String currency, boolean available, boolean enabled, long chargeMinor,
                   long version) {
    }

    /** The calling seller's setting (staff name the organisation). */
    Setting mine(String sellerOrgId, String currency);

    /**
     * Sets the calling seller's own charge. 409 {@code orders.self-shipping-disabled} while the staff switch is off;
     * 422 {@code orders.invalid-self-shipping-charge} outside 0 to 100.00.
     */
    Setting replace(String sellerOrgId, String currency, boolean enabled, long chargeMinor);

    /**
     * What checkout may offer: the sellers among {@code sellerOrgIds} that opted in, with their charge. Empty while the
     * switch is off. Public to the platform: the customer sees the charge at checkout.
     */
    List<Setting> offered(Collection<String> sellerOrgIds, String currency);
}
