package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.afrotransact.payments.domain.NegotiatedRate;
import com.afrotransact.payments.domain.NegotiatedRateAudit;
import com.afrotransact.payments.domain.NegotiatedRateStatus;
import com.afrotransact.payments.domain.NegotiatedRateType;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.port.NegotiatedRateRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class NegotiatedRateServiceImpl implements NegotiatedRateService {

    static final String EXPIRY_ACTOR = "system:negotiated-rate-expiry";
    private static final Logger AUDIT = LoggerFactory.getLogger("audit.payments.negotiated-rates");
    private static final int MAX_LIST = 500;
    private static final int EXPIRY_BATCH = 200;

    private final NegotiatedRateRepository rates;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;
    private final Clock clock;

    NegotiatedRateServiceImpl(NegotiatedRateRepository rates, MoneyTransactions tx, PaymentsAccess access,
                              PaymentsSettings settings, Clock clock) {
        this.rates = rates;
        this.tx = tx;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public NegotiatedRateView create(NewNegotiatedRateCommand cmd) {
        Actor actor = access.configure();
        Objects.requireNonNull(cmd.type(), "type");
        if (cmd.orgId() != null && cmd.orgId().trim().equals(settings.retailOrgId())) {
            throw new PaymentsRuleViolation("payments.retail-no-fees", "AfroTransact Retail is the merchant of record "
                    + "and never pays a commission or connection fee", Map.of("orgId", "Not AfroTransact Retail"));
        }
        return tx.serializable(() -> {
            Instant now = Instant.now(clock);
            NegotiatedRate rate = NegotiatedRate.create(UUID.randomUUID(), cmd.orgId(), cmd.type(), cmd.category(),
                    cmd.rateBps(), cmd.amountMinor(), cmd.startsAt(), cmd.endsAt(), cmd.reason(), cmd.negotiatedBy(),
                    cmd.reference(), actor.subject(), now);
            for (NegotiatedRate other : rates.lockForOrg(rate.orgId(), rate.type())) {
                if (other.overlaps(rate)) {
                    throw new PaymentsStateConflict("payments.negotiated-rate-overlap", "A negotiated "
                            + label(rate.type()) + " for " + categoryLabel(rate.category()) + " already applies "
                            + "from " + other.startsAt() + (other.effectiveEnd() == null ? ""
                            : " to " + other.effectiveEnd()) + "; end it first");
                }
            }
            rates.insert(rate);
            NegotiatedRateAudit entry = new NegotiatedRateAudit(UUID.randomUUID(), rate.id(),
                    NegotiatedRateAudit.CREATED, actor.subject(), rate.reason(), now);
            rates.insertAudit(entry);
            AUDIT.info("negotiated {} {} bps for {} ({}) from {} to {} created by {} (negotiated by {}, ref {}): {}",
                    rate.type(), rate.rateBps(), rate.orgId(), rate.category(), rate.startsAt(), rate.endsAt(),
                    actor.subject(), rate.negotiatedBy(), rate.reference(), rate.reason());
            return new NegotiatedRateView(rate, rate.status(now), List.of(entry));
        });
    }

    @Override
    public NegotiatedRateView end(UUID id, String reason) {
        Actor actor = access.configure();
        return tx.serializable(() -> {
            Instant now = Instant.now(clock);
            NegotiatedRate rate = rates.findById(id).orElseThrow(() -> notFound(id));
            NegotiatedRate ended = rates.update(rate.end(now, actor.subject(), reason));
            rates.insertAudit(new NegotiatedRateAudit(UUID.randomUUID(), id, NegotiatedRateAudit.ENDED,
                    actor.subject(), ended.endReason(), now));
            AUDIT.info("negotiated {} {} for {} ({}) ended by {}: {}", ended.type(), id, ended.orgId(),
                    ended.category(), actor.subject(), ended.endReason());
            return new NegotiatedRateView(ended, ended.status(now), rates.audit(id));
        });
    }

    @Override
    public NegotiatedRateView get(UUID id) {
        NegotiatedRate rate = rates.findById(id).orElseThrow(() -> notFound(id));
        viewer(rate.orgId());
        return new NegotiatedRateView(rate, rate.status(Instant.now(clock)), rates.audit(id));
    }

    @Override
    public List<NegotiatedRateView> list(String orgId, NegotiatedRateType type, NegotiatedRateStatus status,
                                         int limit) {
        String org = orgId == null || orgId.isBlank() ? null : orgId.trim();
        Actor actor = access.current();
        if (PaymentsAccess.isStaff(actor) && org == null) {
            access.feeReader();
        } else {
            org = access.orgOf(org);
            viewer(org);
        }
        Instant now = Instant.now(clock);
        int max = Math.max(1, Math.min(limit, MAX_LIST));
        return rates.search(org, type, status == null ? max : MAX_LIST).stream()
                .map(r -> new NegotiatedRateView(r, r.status(now), List.of()))
                .filter(v -> status == null || v.status() == status)
                .limit(max)
                .toList();
    }

    @Override
    public int expireDue() {
        return tx.serializable(() -> {
            Instant now = Instant.now(clock);
            int n = 0;
            for (NegotiatedRate r : rates.dueForExpiry(now, EXPIRY_BATCH)) {
                if (!r.dueForExpiry(now)) {
                    continue;
                }
                rates.update(r.expire(now));
                rates.insertAudit(new NegotiatedRateAudit(UUID.randomUUID(), r.id(), NegotiatedRateAudit.EXPIRED,
                        EXPIRY_ACTOR, "End date " + r.endsAt() + " reached", now));
                AUDIT.info("negotiated {} {} for {} ({}) expired at its end date {}", r.type(), r.id(), r.orgId(),
                        r.category(), r.endsAt());
                n++;
            }
            return n;
        });
    }

    /** Staff fee readers (and services reading fees), or members of the organisation who see its payouts. */
    private void viewer(String orgId) {
        Actor actor = access.current();
        if (PaymentsAccess.isStaff(actor)) {
            access.feeReader();
            return;
        }
        access.balanceViewer(orgId);
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("payments.negotiated-rate-not-found", "No negotiated rate " + id);
    }

    private static String label(NegotiatedRateType type) {
        return type == NegotiatedRateType.CONNECTION_FEE ? "connection fee" : "commission";
    }

    private static String categoryLabel(String category) {
        return NegotiatedRate.ANY.equals(category) ? "every category" : category;
    }
}
