package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.payments.domain.EarningsPool;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.domain.PlanFeeDeduction;
import com.afrotransact.payments.domain.PlanFeeWithholding;
import com.afrotransact.payments.domain.PlanFeeWithholdingStatus;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.port.DisputeRepository;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.PlanFeeWithholdingRepository;
import com.afrotransact.payments.port.SettlementRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Every step runs in one SERIALIZABLE transaction (retried on conflicts), so two invoices of the same organisation
 * can never both claim the same earnings, and the unique invoice id plus the per-key release rows make every replay a
 * no-op.
 */
@Service
class PlanFeeServiceImpl implements PlanFeeService {

    static final String KEY_PREFIX = "plan-fee-";
    static final String RELEASE_KEY_PREFIX = "plan-fee-release-";

    private static final Logger LOG = LoggerFactory.getLogger(PlanFeeServiceImpl.class);
    private static final String SYSTEM = "system:plan-fees";
    private static final int MAX_LIST = 200;

    private final PlanFeeWithholdingRepository withholdings;
    private final LedgerRepository ledgerReader;
    private final SettlementRepository settlements;
    private final DisputeRepository disputes;
    private final LedgerPoster ledger;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;
    private final Clock clock;

    PlanFeeServiceImpl(PlanFeeWithholdingRepository withholdings, LedgerRepository ledgerReader,
                       SettlementRepository settlements, DisputeRepository disputes, LedgerPoster ledger,
                       MoneyTransactions tx, PaymentsAccess access, PaymentsSettings settings, Clock clock) {
        this.withholdings = withholdings;
        this.ledgerReader = ledgerReader;
        this.settlements = settlements;
        this.disputes = disputes;
        this.ledger = ledger;
        this.tx = tx;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public PlanFeeWithholding withhold(String orgId, PlanFeeCommand cmd, String idempotencyKey) {
        access.planFeeCollector();
        String org = required("orgId", orgId);
        String invoice = required("invoiceId", cmd == null ? null : cmd.invoiceId());
        if (cmd.amountMinor() <= 0) {
            throw new PaymentsRuleViolation("payments.invalid-request", "amountMinor must be positive",
                    Map.of("amountMinor", "Positive"));
        }
        if (!(KEY_PREFIX + invoice).equals(idempotencyKey == null ? null : idempotencyKey.trim())) {
            throw new PaymentsRuleViolation("payments.idempotency-key-required", "Idempotency-Key must be "
                    + KEY_PREFIX + invoice, Map.of("Idempotency-Key", KEY_PREFIX + invoice));
        }
        Money requested = Money.of(cmd.amountMinor(), currency(cmd.currency()));
        return tx.serializable(() -> {
            Optional<PlanFeeWithholding> prior = withholdings.findByInvoice(invoice);
            if (prior.isPresent()) {
                return replay(prior.get(), org, requested);
            }
            Instant now = Instant.now(clock);
            UUID id = Fingerprint.id("plan-fee", invoice);
            UUID journal = Fingerprint.id("journal", "plan-fee", invoice);
            Money available = org.equals(settings.retailOrgId()) ? Money.zero(requested.currency())
                    : withholdable(org, requested.currency());
            PlanFeeWithholding w = PlanFeeWithholding.decide(id, org, invoice, blankToNull(cmd.invoiceNumber()),
                    requested, available, journal, now);
            if (w.status() == PlanFeeWithholdingStatus.WITHHELD) {
                ledger.post(Postings.planFeeWithheld(journal, now, "plan-fee:" + invoice, w), SYSTEM);
            }
            if (!withholdings.insert(w)) {
                throw new PaymentsStateConflict("payments.plan-fee-concurrent", "Invoice " + invoice
                        + " is being withheld concurrently; retry");
            }
            LOG.info("Plan fee of {} for invoice {}: {} of {} withheld", org, invoice, w.withheld(), requested);
            return w;
        });
    }

    /** The same invoice again: the first decision, unless the request differs. */
    private static PlanFeeWithholding replay(PlanFeeWithholding w, String org, Money requested) {
        if (!w.orgId().equals(org) || !w.requested().equals(requested)) {
            throw new PaymentsStateConflict("payments.idempotency-conflict", "Invoice " + w.invoiceId()
                    + " was already withheld with another organisation or amount");
        }
        return w;
    }

    /**
     * Delivered, undisputed and untransferred nets of the organisation, less what outstanding label charges and
     * earlier plan fees already claim on them.
     */
    private Money withholdable(String org, String currency) {
        Map<String, Long> payable = ledgerReader.payableByOrder(org, currency);
        List<String> positive = payable.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey)
                .toList();
        Set<String> delivered = settlements.delivered(positive);
        Set<String> disputed = disputes.openOrders(positive);
        long planOwed = withholdings.outstanding(org, currency);
        long labelsOwed = -(ledgerReader.unattributedPayable(org, currency) + planOwed);
        return EarningsPool.withholdable(payable, delivered, disputed, labelsOwed, planOwed, currency);
    }

    @Override
    public PlanFeeWithholding release(String orgId, String invoiceId, Long amountMinor, String reason,
                                      String idempotencyKey) {
        access.planFeeCollector();
        String org = required("orgId", orgId);
        String invoice = required("invoiceId", invoiceId);
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (!key.startsWith(RELEASE_KEY_PREFIX) || key.length() > 120) {
            throw new PaymentsRuleViolation("payments.idempotency-key-required", "Idempotency-Key must be "
                    + RELEASE_KEY_PREFIX + "<invoice or credit note> (at most 120 characters)",
                    Map.of("Idempotency-Key", RELEASE_KEY_PREFIX + invoice));
        }
        if (amountMinor != null && amountMinor <= 0) {
            throw new PaymentsRuleViolation("payments.invalid-request", "amountMinor must be positive",
                    Map.of("amountMinor", "Positive"));
        }
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        if (why != null && why.length() > 200) {
            throw new PaymentsRuleViolation("payments.invalid-request", "reason: at most 200 characters",
                    Map.of("reason", "At most 200 characters"));
        }
        return tx.serializable(() -> {
            PlanFeeWithholding w = withholdings.lockByInvoice(invoice).filter(x -> x.orgId().equals(org))
                    .orElseThrow(() -> new NotFoundException("payments.plan-fee-not-found", "No plan fee was "
                            + "withheld for invoice " + invoice + " of " + org));
            Optional<UUID> done = withholdings.releaseOf(key);
            if (done.isPresent()) {
                if (!done.get().equals(w.id())) {
                    throw new PaymentsStateConflict("payments.idempotency-conflict", "Key " + key
                            + " released another invoice");
                }
                return w;
            }
            if (w.status() == PlanFeeWithholdingStatus.DECLINED || !w.releasable().isPositive()) {
                return w;
            }
            Money amount = amountMinor == null ? w.releasable()
                    : Money.of(amountMinor, w.withheld().currency()).min(w.releasable());
            Instant now = Instant.now(clock);
            UUID journal = Fingerprint.id("journal", "plan-fee-release", key);
            ledger.post(Postings.planFeeReleased(journal, now, "plan-fee-release:" + key, w, amount, why), SYSTEM);
            withholdings.insertRelease(key, w.id(), amount, why, journal, now);
            LOG.info("Plan fee of {} for invoice {}: {} released ({})", org, invoice, amount, why);
            return withholdings.update(w.release(amount, now));
        });
    }

    @Override
    public List<View> ofOrg(String orgId, int limit) {
        String org = required("orgId", orgId);
        access.planFeeViewer(org);
        return tx.serializable(() -> {
            List<PlanFeeWithholding> list = withholdings.ofOrg(org, Math.max(1, Math.min(limit, MAX_LIST)));
            Map<UUID, List<PlanFeeDeduction>> deductions = withholdings.deductions(list.stream()
                    .map(PlanFeeWithholding::id).toList());
            return list.stream().map(w -> new View(w, deductions.getOrDefault(w.id(), List.of()))).toList();
        });
    }

    private String currency(String requested) {
        String c = requested == null || requested.isBlank() ? settings.currency()
                : requested.trim().toUpperCase(Locale.ROOT);
        if (!c.equals(settings.currency())) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Plan fees are collected in "
                    + settings.currency(), Map.of("currency", settings.currency()));
        }
        return c;
    }

    private static String required(String field, String value) {
        if (value == null || value.isBlank() || value.length() > 80) {
            throw new PaymentsRuleViolation("payments.invalid-request", field + " is required (at most 80 "
                    + "characters)", Map.of(field, "Required"));
        }
        return value.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
