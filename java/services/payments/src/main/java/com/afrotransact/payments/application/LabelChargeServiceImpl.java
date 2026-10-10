package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.LabelCharge;
import com.afrotransact.payments.domain.LabelChargeStatus;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.port.LabelChargeRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Event over API: carrier-gateway writes the label event to its outbox in the purchase (or void) transaction, so
 * buying a label gets no new synchronous dependency on payments, and a replay is safe (inbox on event-id plus a
 * unique label id). The charge posts whatever the seller's balance: the payable may go negative, and the payout
 * balance reserves the outstanding charge until a transfer recovers it.
 */
@Service
class LabelChargeServiceImpl implements LabelChargeService {

    static final String CONSUMER = "payments.carrier-shipment-events";
    static final String BILL_TO_SELLER = "SELLER";
    static final String SELLER_SHIPPED = "SELLER_SHIPPED";
    static final String OUTBOUND = "outbound";

    private static final Logger LOG = LoggerFactory.getLogger(LabelChargeServiceImpl.class);
    private static final String SYSTEM = "system:label-charges";
    private static final int MAX_LIST = 200;

    private final LabelChargeRepository charges;
    private final LedgerPoster ledger;
    private final InboxDeduplicator inbox;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;
    private final Clock clock;

    LabelChargeServiceImpl(LabelChargeRepository charges, LedgerPoster ledger, InboxDeduplicator inbox,
                           MoneyTransactions tx, PaymentsAccess access, PaymentsSettings settings, Clock clock) {
        this.charges = charges;
        this.ledger = ledger;
        this.inbox = inbox;
        this.tx = tx;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public boolean purchased(LabelChargeNotice n) {
        Optional<String> exempt = exemption(n);
        if (exempt.isPresent()) {
            LOG.info("Label {} is not charged to a seller: {}", n.labelId(), exempt.get());
            return false;
        }
        return tx.serializable(() -> {
            if (!inbox.firstDelivery(CONSUMER, n.eventId())) {
                return false;
            }
            Instant now = Instant.now(clock);
            UUID journal = Fingerprint.id("journal", "label-charge", n.labelId());
            LabelCharge charge = LabelCharge.charge(Fingerprint.id("label-charge", n.labelId()), n.labelId(),
                    n.orgId().trim(), n.shipmentId(), n.orderId(), n.carrier(), n.service(), n.trackingNumber(),
                    Money.of(n.costMinor(), n.currency()), Money.of(n.handlingMinor(), n.currency()), journal,
                    n.eventId(), n.purchasedAt(), now);
            if (!charges.insert(charge)) {
                LOG.info("Label {} is already charged or voided; event {} ignored", n.labelId(), n.eventId());
                return false;
            }
            ledger.post(Postings.labelCharge(journal, now, "label-charge:" + n.labelId(), charge), SYSTEM);
            return true;
        });
    }

    @Override
    public boolean voided(LabelChargeNotice n) {
        if (blank(n.labelId())) {
            LOG.warn("Ignoring label void event {}: no label id", n.eventId());
            return false;
        }
        return tx.serializable(() -> {
            if (!inbox.firstDelivery(CONSUMER, n.eventId())) {
                return false;
            }
            Instant now = Instant.now(clock);
            Optional<LabelCharge> existing = charges.lockByLabel(n.labelId());
            if (existing.isEmpty()) {
                if (exemption(n).isEmpty()) {
                    // The void overtook its purchase (e.g. a purchase record that failed and was skipped): remember
                    // it so the late purchase charges nothing.
                    charges.insert(LabelCharge.voidedFirst(Fingerprint.id("label-charge", n.labelId()),
                            n.labelId(), n.orgId().trim(), n.shipmentId(), n.orderId(), n.carrier(), n.service(),
                            n.trackingNumber(), Money.of(n.costMinor(), n.currency()),
                            Money.of(n.handlingMinor(), n.currency()), n.eventId(), n.purchasedAt(), now));
                }
                return false;
            }
            LabelCharge charge = existing.get();
            if (charge.status() != LabelChargeStatus.CHARGED) {
                LOG.info("Label {} is {}; void event {} ignored", n.labelId(), charge.status(), n.eventId());
                return false;
            }
            UUID journal = Fingerprint.id("journal", "label-credit", n.labelId());
            ledger.post(Postings.labelCredit(journal, now, "label-credit:" + n.labelId(), charge), SYSTEM);
            charges.update(charge.credited(journal, now));
            return true;
        });
    }

    @Override
    public List<LabelCharge> ofOrg(String orgId) {
        String org = access.orgOf(orgId);
        access.balanceViewer(org);
        return charges.ofOrg(org, MAX_LIST);
    }

    /**
     * Why a label must not be charged to a seller, if it must not. carrier-gateway already decides billTo; the rules
     * are re-checked here so that a misconfigured gateway can never charge AfroTransact Retail (merchant of record:
     * it pays Stripe fees only) or a seller for an FBA / AfroTransact Delivery shipment (AfroTransact's own cost).
     */
    private Optional<String> exemption(LabelChargeNotice n) {
        String org = n.orgId() == null ? "" : n.orgId().trim();
        if (!BILL_TO_SELLER.equals(upper(n.billTo()))) {
            return Optional.of("billed to " + n.billTo());
        }
        if (org.equals(settings.retailOrgId())) {
            return Optional.of("AfroTransact Retail is merchant of record");
        }
        if (n.fulfilment() != null && !n.fulfilment().isBlank() && !SELLER_SHIPPED.equals(upper(n.fulfilment()))) {
            return Optional.of(n.fulfilment() + " shipping is AfroTransact's cost");
        }
        if (n.kind() != null && !n.kind().isBlank() && !OUTBOUND.equalsIgnoreCase(n.kind().trim())) {
            return Optional.of("return labels are not seller charges");
        }
        if (org.isEmpty() || blank(n.labelId()) || blank(n.currency()) || n.costMinor() < 0 || n.handlingMinor() < 0
                || n.costMinor() + n.handlingMinor() <= 0) {
            return Optional.of("incomplete event " + n.eventId());
        }
        return Optional.empty();
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
