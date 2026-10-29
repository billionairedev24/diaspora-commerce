package com.afrotransact.payments.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.port.ConnectedAccountRepository;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.ConnectedBalanceSnapshot;
import com.afrotransact.payments.port.StripeGateway.ObjectSnapshot;
import com.afrotransact.payments.port.StripeGatewayException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StripeLookupServiceImpl implements StripeLookupService {

    private static final Pattern REF = Pattern.compile("^[a-z]{2,6}_[A-Za-z0-9_]{6,100}$");

    private final StripeGateway stripe;
    private final LedgerRepository ledger;
    private final ConnectedAccountRepository accounts;
    private final PaymentsAccess access;

    StripeLookupServiceImpl(StripeGateway stripe, LedgerRepository ledger, ConnectedAccountRepository accounts,
                            PaymentsAccess access) {
        this.stripe = stripe;
        this.ledger = ledger;
        this.accounts = accounts;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public StripeLookup lookup(String ref, String orgId) {
        access.reader();
        String id = ref == null ? "" : ref.trim();
        if (!REF.matcher(id).matches()) {
            throw new PaymentsRuleViolation("payments.invalid-stripe-ref",
                    "That does not look like a Stripe id (po_…, tr_…, pi_…, ch_…, re_…, txn_…, dp_…, acct_…)",
                    Map.of("ref", "Invalid"));
        }
        String account = id.startsWith("acct_") ? id
                : orgId == null || orgId.isBlank() ? null : accounts.find(orgId.trim()).orElse(null);
        ObjectSnapshot object = null;
        String problem = null;
        try {
            Optional<ObjectSnapshot> found = stripe.lookup(id, account);
            if (found.isPresent()) {
                object = found.get();
            } else {
                problem = id.startsWith("po_") && account == null
                        ? "Stripe has no payout with that id on the platform account; give the seller whose connected "
                            + "account holds it"
                        : "Stripe has no object with that id (or this kind of id is not looked up here)";
            }
        } catch (StripeGatewayException ex) {
            problem = "Stripe could not be asked: " + ex.getMessage();
        }
        ConnectedBalanceSnapshot balance = null;
        if (account != null) {
            try {
                balance = stripe.connectedBalance(account);
            } catch (StripeGatewayException ex) {
                balance = null;
            }
        }
        List<Journal> journals = ledger.journalsByStripeRef(id);
        return new StripeLookup(id, object, problem, journals, account, balance);
    }
}
