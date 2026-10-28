package com.afrotransact.seller.adapter.out.stripe;

import java.time.Instant;
import java.util.Set;

import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.port.AccountSession;
import com.afrotransact.seller.port.CreateConnectedAccount;
import com.afrotransact.seller.port.StripeConnectGateway;
import com.stripe.StripeClient;
import com.stripe.model.Account;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountSessionCreateParams;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * stripe-java adapter for Connect. Sellers onboard on AfroTransact through Stripe's embedded components (no redirect
 * to Stripe, no Stripe dashboard), and Stripe keeps the liability, as with Standard accounts: Stripe collects and
 * verifies requirements and is liable for the connected account's negative balances, and the account pays its own
 * Stripe account fees. Payouts are manual (released by payments-service after delivery). Disputes and refunds on
 * the platform's own charges (separate charges and transfers) stay with the platform whatever these settings say.
 * Because Stripe collects requirements, the platform doesn't update identity, tax or bank details through the API:
 * Stripe's embedded components collect them (the payout bank account included) and this adapter only reads them.
 */
public class StripeConnectAdapter implements StripeConnectGateway {

    private final StripeClient client;
    private final StripeJson json;

    public StripeConnectAdapter(StripeClient client, JsonMapper mapper) {
        this.client = client;
        this.json = new StripeJson(mapper);
    }

    @Override
    public String createAccount(CreateConnectedAccount req) {
        AccountCreateParams params = AccountCreateParams.builder()
                .setCountry(req.country())
                .setEmail(req.email())
                .setBusinessType(switch (req.businessType()) {
                    case "individual" -> AccountCreateParams.BusinessType.INDIVIDUAL;
                    case "non_profit" -> AccountCreateParams.BusinessType.NON_PROFIT;
                    default -> AccountCreateParams.BusinessType.COMPANY;
                })
                .setController(AccountCreateParams.Controller.builder()
                        .setFees(AccountCreateParams.Controller.Fees.builder()
                                .setPayer(AccountCreateParams.Controller.Fees.Payer.ACCOUNT).build())
                        .setLosses(AccountCreateParams.Controller.Losses.builder()
                                .setPayments(AccountCreateParams.Controller.Losses.Payments.STRIPE).build())
                        .setRequirementCollection(AccountCreateParams.Controller.RequirementCollection.STRIPE)
                        .setStripeDashboard(AccountCreateParams.Controller.StripeDashboard.builder()
                                .setType(AccountCreateParams.Controller.StripeDashboard.Type.NONE).build())
                        .build())
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setCardPayments(AccountCreateParams.Capabilities.CardPayments.builder().setRequested(true)
                                .build())
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build())
                        .build())
                .setBusinessProfile(businessProfile(req))
                .setSettings(AccountCreateParams.Settings.builder()
                        .setPayouts(AccountCreateParams.Settings.Payouts.builder()
                                .setSchedule(AccountCreateParams.Settings.Payouts.Schedule.builder()
                                        .setInterval(AccountCreateParams.Settings.Payouts.Schedule.Interval.MANUAL)
                                        .build())
                                .build())
                        .build())
                .putMetadata("org_id", req.orgId())
                .putMetadata("application_id", req.applicationId())
                .build();
        RequestOptions options = RequestOptions.builder().setIdempotencyKey(req.idempotencyKey()).build();
        Account account = StripeCalls.call("account creation", () -> client.v1().accounts().create(params, options));
        return account.getId();
    }

    private static AccountCreateParams.BusinessProfile businessProfile(CreateConnectedAccount req) {
        AccountCreateParams.BusinessProfile.Builder profile = AccountCreateParams.BusinessProfile.builder();
        if (req.website() != null && !req.website().isBlank()) {
            profile.setUrl(req.website());
        }
        if (req.productDescription() != null && !req.productDescription().isBlank()) {
            profile.setProductDescription(req.productDescription());
        }
        return profile.build();
    }

    @Override
    public AccountSession createAccountSession(String accountId, Set<String> components) {
        // No feature overrides: on a Stripe-collected account external_account_collection can only be true (Stripe
        // collects the payout bank account), and Stripe requires the components of one session to agree on it.
        AccountSessionCreateParams.Components.Builder c = AccountSessionCreateParams.Components.builder();
        if (components.contains("account_onboarding")) {
            c.setAccountOnboarding(AccountSessionCreateParams.Components.AccountOnboarding.builder().setEnabled(true)
                    .build());
        }
        if (components.contains("account_management")) {
            c.setAccountManagement(AccountSessionCreateParams.Components.AccountManagement.builder().setEnabled(true)
                    .build());
        }
        if (components.contains("notification_banner")) {
            c.setNotificationBanner(AccountSessionCreateParams.Components.NotificationBanner.builder()
                    .setEnabled(true).build());
        }
        AccountSessionCreateParams params = AccountSessionCreateParams.builder().setAccount(accountId)
                .setComponents(c.build()).build();
        var session = StripeCalls.call("account session",
                () -> client.v1().accountSessions().create(params));
        return new AccountSession(accountId, session.getClientSecret(),
                session.getExpiresAt() == null ? null : Instant.ofEpochSecond(session.getExpiresAt()), false);
    }

    @Override
    public RequirementsSnapshot retrieveAccount(String accountId) {
        Account account = StripeCalls.call("account retrieval", () -> client.v1().accounts().retrieve(accountId));
        JsonNode tree = json.tree(account.getLastResponse().body());
        return json.account(tree).withPayoutBank(StripeAccountJson.payoutBank(tree));
    }
}
