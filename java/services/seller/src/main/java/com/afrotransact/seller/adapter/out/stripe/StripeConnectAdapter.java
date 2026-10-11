package com.afrotransact.seller.adapter.out.stripe;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.afrotransact.seller.domain.RequirementAnswer;
import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.domain.TaxIds;
import com.afrotransact.seller.port.AccountSession;
import com.afrotransact.seller.port.BankAccountDetails;
import com.afrotransact.seller.port.CreateConnectedAccount;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.LinkedBankAccount;
import com.afrotransact.seller.port.MediaAsset;
import com.afrotransact.seller.port.RequirementSubmission;
import com.afrotransact.seller.port.StripeConnectGateway;
import com.stripe.StripeClient;
import com.stripe.model.Account;
import com.stripe.model.BankAccount;
import com.stripe.model.ExternalAccount;
import com.stripe.model.Person;
import com.stripe.model.StripeCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountExternalAccountCreateParams;
import com.stripe.param.AccountPersonListParams;
import com.stripe.param.AccountPersonUpdateParams;
import com.stripe.param.AccountSessionCreateParams;
import com.stripe.param.AccountUpdateParams;
import com.stripe.param.FileCreateParams;
import tools.jackson.databind.json.JsonMapper;

/**
 * stripe-java adapter for Connect. Accounts are platform-controlled: the platform collects requirements (through
 * Stripe's embedded onboarding and account management components), pays Stripe fees and owns negative balances,
 * there is no Stripe dashboard and payouts are manual (released by payments-service). Stripe refuses
 * requirement_collection=stripe with no dashboard unless Stripe is liable for losses, which a marketplace that
 * charges on the platform account cannot choose; application collection needs the platform profile completed in
 * the Stripe Dashboard (Connect → Settings → Platform profile).
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
                                .setPayer(AccountCreateParams.Controller.Fees.Payer.APPLICATION).build())
                        .setLosses(AccountCreateParams.Controller.Losses.builder()
                                .setPayments(AccountCreateParams.Controller.Losses.Payments.APPLICATION).build())
                        .setRequirementCollection(AccountCreateParams.Controller.RequirementCollection.APPLICATION)
                        .setStripeDashboard(AccountCreateParams.Controller.StripeDashboard.builder()
                                .setType(AccountCreateParams.Controller.StripeDashboard.Type.NONE).build())
                        .build())
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setCardPayments(AccountCreateParams.Capabilities.CardPayments.builder().setRequested(true)
                                .build())
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build())
                        .build())
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

    @Override
    public AccountSession createAccountSession(String accountId, Set<String> components) {
        AccountSessionCreateParams.Components.Builder c = AccountSessionCreateParams.Components.builder();
        if (components.contains("account_onboarding")) {
            // The payout bank account is the wizard's own "Payouts" step, so onboarding does not ask for it twice.
            c.setAccountOnboarding(AccountSessionCreateParams.Components.AccountOnboarding.builder().setEnabled(true)
                    .setFeatures(AccountSessionCreateParams.Components.AccountOnboarding.Features.builder()
                            .setExternalAccountCollection(false).build())
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
        return json.account(json.tree(account.getLastResponse().body()));
    }

    @Override
    public void attachExternalAccount(String accountId, String bankAccountToken) {
        AccountExternalAccountCreateParams params = AccountExternalAccountCreateParams.builder()
                .setExternalAccount(bankAccountToken).setDefaultForCurrency(true).build();
        StripeCalls.call("bank account", () -> client.v1().accounts().externalAccounts().create(accountId, params));
    }

    @Override
    public LinkedBankAccount attachBankAccount(String accountId, BankAccountDetails bank) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("object", "bank_account");
        account.put("country", "US");
        account.put("currency", "usd");
        account.put("account_holder_name", bank.holderName());
        account.put("account_holder_type", bank.holderType());
        account.put("routing_number", bank.routingNumber());
        account.put("account_number", bank.accountNumber());
        AccountExternalAccountCreateParams params = AccountExternalAccountCreateParams.builder()
                .putExtraParam("external_account", account).setDefaultForCurrency(true).build();
        ExternalAccount created = StripeCalls.call("bank account",
                () -> client.v1().accounts().externalAccounts().create(accountId, params));
        if (created instanceof BankAccount b) {
            return new LinkedBankAccount(b.getBankName(), b.getLast4(), b.getStatus());
        }
        return new LinkedBankAccount(null, TaxIds.last4(bank.accountNumber()), "new");
    }

    @Override
    public String uploadRequirementFile(String accountId, MediaAsset asset) {
        FileCreateParams params = FileCreateParams.builder()
                .setPurpose(FileCreateParams.Purpose.ACCOUNT_REQUIREMENT)
                .setFile(new ByteArrayInputStream(asset.bytes()))
                .build();
        RequestOptions options = RequestOptions.builder().setStripeAccount(accountId).build();
        return StripeCalls.call("document upload", () -> client.v1().files().create(params, options)).getId();
    }

    @Override
    public void submitRequirement(String accountId, RequirementSubmission s) {
        RequirementAnswer a = s.answer();
        String subject = a.subject();
        boolean person = subject.equals("representative") || subject.startsWith("person_");
        Map<String, Object> fields = new LinkedHashMap<>();
        long at = s.at().getEpochSecond();
        switch (a.kind()) {
            case TAX_ID -> fields.put("tax_id", a.ein());
            case ID_NUMBER -> fields.put("id_number", a.idNumberToken());
            case VERIFICATION_DOCUMENT -> {
                Map<String, Object> doc = new HashMap<>();
                doc.put("front", s.frontFileId());
                if (s.backFileId() != null) {
                    doc.put("back", s.backFileId());
                }
                String which = a.requirement().contains("additional_document") ? "additional_document" : "document";
                fields.put("verification", Map.of(which, doc));
            }
            case OWNERSHIP_DECLARATION -> fields.put("ownership_declaration", acceptance(at, s));
            case REPRESENTATIVE_DOB -> fields.put("dob", Map.of("day", a.dateOfBirth().getDayOfMonth(),
                    "month", a.dateOfBirth().getMonthValue(), "year", a.dateOfBirth().getYear()));
            case REPRESENTATIVE_TITLE -> fields.put("relationship", Map.of("title", a.title()));
            case TOS_ACCEPTANCE -> {
                updateAccount(accountId, Map.of("tos_acceptance", acceptance(at, s)));
                return;
            }
            case BANK_ACCOUNT -> {
                attachExternalAccount(accountId, a.bankAccountToken());
                return;
            }
            case BUSINESS_URL -> {
                updateAccount(accountId, Map.of("business_profile", Map.of("url", a.url())));
                return;
            }
            default -> throw new GatewayException("seller.requirement-unsupported",
                    "This requirement can't be answered online", true, null);
        }
        if (person) {
            String personId = subject.startsWith("person_") ? subject : representative(accountId);
            AccountPersonUpdateParams.Builder b = AccountPersonUpdateParams.builder();
            fields.forEach(b::putExtraParam);
            AccountPersonUpdateParams params = b.build();
            StripeCalls.call("person update", () -> client.v1().accounts().persons().update(accountId, personId,
                    params));
        } else {
            updateAccount(accountId, Map.of(subject.equals("company") ? "company" : "individual", fields));
        }
    }

    private void updateAccount(String accountId, Map<String, Object> fields) {
        AccountUpdateParams.Builder b = AccountUpdateParams.builder();
        fields.forEach(b::putExtraParam);
        AccountUpdateParams params = b.build();
        StripeCalls.call("account update", () -> client.v1().accounts().update(accountId, params));
    }

    private String representative(String accountId) {
        AccountPersonListParams params = AccountPersonListParams.builder()
                .setRelationship(AccountPersonListParams.Relationship.builder().setRepresentative(true).build())
                .setLimit(1L).build();
        StripeCollection<Person> persons = StripeCalls.call("person lookup",
                () -> client.v1().accounts().persons().list(accountId, params));
        if (persons.getData() == null || persons.getData().isEmpty()) {
            throw new GatewayException("seller.no-representative", "The account has no representative yet", true,
                    null);
        }
        return persons.getData().getFirst().getId();
    }

    private static Map<String, Object> acceptance(long at, RequirementSubmission s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", at);
        if (s.ip() != null) {
            m.put("ip", s.ip());
        }
        if (s.userAgent() != null) {
            m.put("user_agent", s.userAgent());
        }
        return m;
    }
}
