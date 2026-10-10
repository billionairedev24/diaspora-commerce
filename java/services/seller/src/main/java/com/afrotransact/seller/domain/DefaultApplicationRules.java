package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Default onboarding rules (architecture §7, chat specs for S090–S096). */
public class DefaultApplicationRules implements ApplicationRules {

    public static final Set<String> FOOD_CATEGORIES = Set.of("groceries", "perishables", "prepared_food");
    public static final Set<String> BEAUTY_CATEGORIES = Set.of("beauty", "cosmetics");
    public static final Set<String> CARD_BRANDS = Set.of("visa", "mastercard", "amex", "discover");
    public static final Set<String> US_STATES = Set.of("AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL",
            "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE",
            "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT",
            "VA", "WA", "WV", "WI", "WY", "PR", "GU", "VI", "AS", "MP");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern ZIP = Pattern.compile("^\\d{5}(-\\d{4})?$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
    private static final BigDecimal QUARTER = BigDecimal.valueOf(25);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MIN_AGE = 18;

    private final PhoneNumbers phones;

    public DefaultApplicationRules(PhoneNumbers phones) {
        this.phones = phones;
    }

    @Override
    public Validated<BusinessSection> business(BusinessSection in, RuleContext ctx, boolean complete) {
        Errors e = new Errors("business");
        if (in == null) {
            e.required(complete, "offering");
            return new Validated<>(null, e.map());
        }
        String country = in.country() == null ? null : in.country().trim().toUpperCase(Locale.ROOT);
        if (country != null) {
            if (!COUNTRY.matcher(country).matches()) {
                e.put("country", "Use a two-letter ISO country code");
            } else if (!ctx.liveCountries().contains(country)) {
                throw new CountryNotLiveException(country);
            }
        }
        e.required(complete && in.offering() == null, "offering");
        e.required(complete && in.entityType() == null, "entityType");
        e.required(complete && country == null, "country");
        if (in.offering() != null) {
            if (ctx.kind() == OrgKind.SELLER && !in.offering().includesProducts()) {
                e.put("offering", "Seller accounts sell products (choose products or both)");
            }
            if (ctx.kind() == OrgKind.PROVIDER && !in.offering().includesServices()) {
                e.put("offering", "Service-provider accounts offer services (choose services or both)");
            }
        }
        String store = trim(in.storeName());
        if (store != null && (store.length() < 2 || store.length() > 80)) {
            e.put("storeName", "2 to 80 characters");
        }
        e.required(complete && store == null, "storeName");
        String email = trim(in.contactEmail());
        if (email != null && !EMAIL.matcher(email).matches()) {
            e.put("contactEmail", "Enter a valid email address");
        }
        e.required(complete && email == null, "contactEmail");
        String phone = trim(in.phone());
        String e164 = null;
        if (phone != null) {
            Optional<String> parsed = phones.toE164Mobile(phone, country == null ? "US" : country);
            if (parsed.isEmpty()) {
                e.put("phone", "Enter a valid mobile number for the selected country");
            } else {
                e164 = parsed.get();
            }
        }
        e.required(complete && phone == null, "phone");
        String website = trim(in.website());
        if (website != null && !website.matches("^https?://\\S+\\.\\S+$")) {
            e.put("website", "Enter a full URL starting with https://");
        }
        return new Validated<>(new BusinessSection(in.offering(), in.entityType(), country, store,
                email == null ? null : email.toLowerCase(Locale.ROOT), e164 != null ? e164 : phone, website), e.map());
    }

    @Override
    public Validated<BusinessDetailsInput> businessDetails(BusinessDetailsInput in, EntityType entityType,
                                                           boolean hasStoredTaxId, RuleContext ctx,
                                                           boolean complete) {
        Errors e = new Errors("businessDetails");
        if (in == null) {
            e.required(complete, "legalName");
            return new Validated<>(null, e.map());
        }
        if (entityType == null) {
            e.put("entityType", "Choose your business type on the first step");
            return new Validated<>(in, e.map());
        }
        if (in.taxId() != null) {
            taxId(entityType, in.taxId()).errors().forEach((k, v) -> e.put("taxId", v));
        }
        e.required(complete && in.taxId() == null && !hasStoredTaxId, "taxId");
        if (entityType.isIndividual()) {
            nameField(e, "legalFirstName", in.legalFirstName(), complete);
            nameField(e, "legalLastName", in.legalLastName(), complete);
            if (in.dateOfBirth() != null) {
                if (in.dateOfBirth().isAfter(ctx.today().minusYears(MIN_AGE))) {
                    e.put("dateOfBirth", "You must be 18 or older");
                } else if (in.dateOfBirth().isBefore(ctx.today().minusYears(120))) {
                    e.put("dateOfBirth", "Enter a real date of birth");
                }
            }
            e.required(complete && in.dateOfBirth() == null, "dateOfBirth");
        } else {
            nameField(e, "legalName", in.legalName(), complete);
            String state = trim(in.stateOfFormation());
            if (state != null && !US_STATES.contains(state.toUpperCase(Locale.ROOT))) {
                e.put("stateOfFormation", "Use the two-letter state code");
            }
            e.required(complete && state == null, "stateOfFormation");
            if (in.dateOfFormation() != null && in.dateOfFormation().isAfter(ctx.today())) {
                e.put("dateOfFormation", "Formation date can't be in the future");
            }
            e.required(complete && in.dateOfFormation() == null, "dateOfFormation");
            e.required(complete && blank(in.formationDocumentAssetId()), "formationDocumentAssetId");
            if (entityType == EntityType.NONPROFIT) {
                e.required(complete && blank(in.irsDeterminationLetterAssetId()), "irsDeterminationLetterAssetId");
            }
        }
        address(e, in.address(), complete);
        return new Validated<>(in, e.map());
    }

    @Override
    public Validated<TaxIdCheck> taxId(EntityType entityType, String raw) {
        Errors e = new Errors("businessDetails");
        if (entityType.taxIdType() == TaxIdType.EIN) {
            if (!TaxIds.isValidEin(raw)) {
                e.put("taxId", "Enter the 9-digit EIN, e.g. 12-3456789");
                return new Validated<>(null, e.map());
            }
            return new Validated<>(new TaxIdCheck(TaxIdType.EIN, TaxIds.format(TaxIdType.EIN, raw)), e.map());
        }
        Optional<TaxIdType> type = TaxIds.individualType(raw);
        if (type.isEmpty()) {
            e.put("taxId", "Enter a valid 9-digit SSN or ITIN, e.g. 123-45-6789");
            return new Validated<>(null, e.map());
        }
        return new Validated<>(new TaxIdCheck(type.get(), TaxIds.format(type.get(), raw)), e.map());
    }

    @Override
    public Validated<List<Owner>> owners(List<Owner> owners, EntityType entityType, RuleContext ctx,
                                         boolean complete) {
        Errors e = new Errors("owners");
        if (entityType != null && !entityType.needsOwners()) {
            if (owners != null && !owners.isEmpty()) {
                e.put("owners", "Sole proprietors don't list owners");
            }
            return new Validated<>(List.of(), e.map());
        }
        List<Owner> list = owners == null ? List.of() : owners;
        BigDecimal total = BigDecimal.ZERO;
        boolean control = false;
        for (int i = 0; i < list.size(); i++) {
            Owner o = list.get(i);
            String p = "[" + i + "].";
            nameField(e, p + "firstName", o.firstName(), true);
            nameField(e, p + "lastName", o.lastName(), true);
            BigDecimal pct = o.ownershipPercent() == null ? BigDecimal.ZERO : o.ownershipPercent();
            if (pct.signum() < 0 || pct.compareTo(HUNDRED) > 0) {
                e.put(p + "ownershipPercent", "Between 0 and 100");
            } else if (pct.compareTo(QUARTER) < 0 && !o.significantControl()) {
                e.put(p + "ownershipPercent", "List everyone who owns 25% or more; people below 25% are listed "
                        + "only when they have significant control");
            }
            if (o.email() != null && !EMAIL.matcher(o.email().trim()).matches()) {
                e.put(p + "email", "Enter a valid email address");
            }
            if (o.dateOfBirth() != null && o.dateOfBirth().isAfter(ctx.today().minusYears(MIN_AGE))) {
                e.put(p + "dateOfBirth", "Owners must be 18 or older");
            }
            total = total.add(pct);
            control |= o.significantControl();
        }
        if (total.compareTo(HUNDRED) > 0) {
            e.put("ownershipTotal", "Ownership can't exceed 100% (currently " + total.stripTrailingZeros()
                    .toPlainString() + "%)");
        }
        if (complete) {
            if (list.isEmpty()) {
                e.put("owners", "Add everyone who owns 25% or more");
            } else if (!control) {
                e.put("significantControl", "At least one person must have significant control");
            }
            if (entityType != null && list.size() < entityType.minimumOwners()) {
                e.put("owners", "Multi-member LLCs and partnerships need two or more people");
            }
        }
        return new Validated<>(List.copyOf(list), e.map());
    }

    @Override
    public Validated<PayoutSection> payouts(PayoutInput in, PayoutSection existing, boolean complete) {
        Errors e = new Errors("payouts");
        if (in == null) {
            e.required(complete && existing == null, "accountNumber");
            return new Validated<>(existing, e.map());
        }
        String routing = trim(in.routingNumber());
        if (routing != null && !RoutingNumbers.isValid(routing)) {
            e.put("routingNumber", "This routing number isn't valid");
        }
        String account = trim(in.accountNumber());
        String confirm = trim(in.accountNumberConfirmation());
        if (account != null && !account.matches("\\d{4,17}")) {
            e.put("accountNumber", "4 to 17 digits");
        }
        if (account != null && !account.equals(confirm)) {
            e.put("accountNumberConfirmation", "Account numbers don't match");
        }
        String type = trim(in.accountType());
        if (type != null && !Set.of("checking", "savings").contains(type.toLowerCase(Locale.ROOT))) {
            e.put("accountType", "Checking or savings");
        }
        String holder = trim(in.accountHolderName());
        String bank = trim(in.bankName());
        String routingLast4 = routing != null ? TaxIds.last4(routing) : existing == null ? null : existing.routingLast4();
        String accountLast4 = account != null ? TaxIds.last4(account) : existing == null ? null : existing.accountLast4();
        e.required(complete && holder == null, "accountHolderName");
        e.required(complete && bank == null, "bankName");
        e.required(complete && routingLast4 == null, "routingNumber");
        e.required(complete && accountLast4 == null, "accountNumber");
        boolean linked = existing != null && existing.externalAccountLinked() && account == null;
        return new Validated<>(new PayoutSection(holder, type == null ? null : type.toLowerCase(Locale.ROOT), bank,
                routingLast4, accountLast4, linked), e.map());
    }

    @Override
    public Validated<PlanSection> plan(PlanSection in, Optional<Plan> plan, ApplicationData data, RuleContext ctx,
                                       boolean complete) {
        Errors e = new Errors("plan");
        if (in == null) {
            e.required(complete, "planCode");
            return new Validated<>(null, e.map());
        }
        if (in.planCode() != null) {
            if (plan.isEmpty()) {
                e.put("planCode", "Unknown plan");
            } else if (plan.get().kind() != ctx.kind()) {
                e.put("planCode", "This plan is for " + plan.get().kind().name().toLowerCase(Locale.ROOT) + "s");
            }
        }
        e.required(complete && in.planCode() == null, "planCode");
        e.required(complete && in.cadence() == null, "cadence");
        boolean paid = plan.map(p -> !p.isFree()).orElse(false);
        if (in.paymentMethodKind() == PaymentMethodKind.CARD) {
            if (in.cardBrand() != null && !CARD_BRANDS.contains(in.cardBrand().toLowerCase(Locale.ROOT))) {
                e.put("cardBrand", "Visa, Mastercard, American Express or Discover");
            }
            if (in.cardLast4() != null && !in.cardLast4().matches("\\d{4}")) {
                e.put("cardLast4", "Last 4 digits");
            }
            if (in.cardExpMonth() != null && (in.cardExpMonth() < 1 || in.cardExpMonth() > 12)) {
                e.put("cardExpMonth", "1 to 12");
            } else if (in.cardExpMonth() != null && in.cardExpYear() != null
                    && YearMonth.of(in.cardExpYear(), in.cardExpMonth()).isBefore(YearMonth.from(ctx.today()))) {
                e.put("cardExpYear", "This card has expired");
            }
            if (complete && paid) {
                e.required(in.cardBrand() == null, "cardBrand");
                e.required(in.cardLast4() == null, "cardLast4");
                e.required(in.cardExpMonth() == null || in.cardExpYear() == null, "cardExpiry");
            }
        }
        if (in.paymentMethodKind() == PaymentMethodKind.US_BANK_ACCOUNT && complete && data.payouts() == null) {
            e.put("paymentMethodKind", "Add your payout bank account first");
        }
        if (in.stripePaymentMethodId() != null && !in.stripePaymentMethodId().startsWith("pm_")) {
            e.put("stripePaymentMethodId", "Expected a Stripe payment method id (pm_…)");
        }
        if (complete && paid) {
            e.required(in.paymentMethodKind() == null, "paymentMethodKind");
            e.required(blank(in.stripePaymentMethodId()), "stripePaymentMethodId");
        }
        return new Validated<>(in, e.map());
    }

    @Override
    public Validated<ComplianceSection> compliance(ComplianceSection in, Offering offering, RuleContext ctx,
                                                   boolean complete) {
        Errors e = new Errors("compliance");
        if (in == null) {
            e.required(complete, "agreementsAccepted");
            return new Validated<>(null, e.map());
        }
        List<String> categories = in.categories().stream().map(c -> c.trim().toLowerCase(Locale.ROOT)).distinct()
                .toList();
        boolean food = categories.stream().anyMatch(FOOD_CATEGORIES::contains);
        boolean beauty = categories.stream().anyMatch(BEAUTY_CATEGORIES::contains);
        ComplianceSection.FoodPermit permit = in.foodPermit();
        if (permit != null && permit.expiresOn() != null && permit.expiresOn().isBefore(ctx.today())) {
            e.put("foodPermit.expiresOn", "This permit has expired");
        }
        if (complete && food) {
            if (permit == null) {
                e.put("foodPermit", "Food sellers need a food establishment or cottage food permit");
            } else {
                e.required(blank(permit.permitNumber()), "foodPermit.permitNumber");
                e.required(permit.expiresOn() == null, "foodPermit.expiresOn");
                e.required(blank(permit.documentAssetId()), "foodPermit.documentAssetId");
            }
        }
        if (complete && beauty && !Boolean.TRUE.equals(in.beautyLabellingConfirmed())) {
            e.put("beautyLabellingConfirmed", "Confirm your beauty products carry full ingredient labelling");
        }
        boolean regulated = false;
        for (int i = 0; i < in.services().size(); i++) {
            ComplianceSection.ServiceLicence s = in.services().get(i);
            String p = "services[" + i + "].";
            if (blank(s.serviceName())) {
                e.put(p + "serviceName", "Required");
            }
            if (s.expiresOn() != null && s.expiresOn().isBefore(ctx.today())) {
                e.put(p + "expiresOn", "This licence has expired");
            }
            if (s.issuingState() != null && !US_STATES.contains(s.issuingState().trim().toUpperCase(Locale.ROOT))) {
                e.put(p + "issuingState", "Use the two-letter state code");
            }
            if (s.regulated()) {
                regulated = true;
                if (complete) {
                    e.required(blank(s.licenceNumber()), p + "licenceNumber");
                    e.required(blank(s.issuingState()), p + "issuingState");
                    e.required(s.expiresOn() == null, p + "expiresOn");
                    e.required(blank(s.documentAssetId()), p + "documentAssetId");
                }
            }
        }
        if (complete) {
            if (offering != null && offering.includesProducts() && categories.isEmpty()) {
                e.put("categories", "Choose what you'll sell");
            }
            if (offering != null && offering.includesServices() && in.services().isEmpty()) {
                e.put("services", "Add the services you'll offer");
            }
            if (regulated && blank(in.insuranceDocumentAssetId())) {
                e.put("insuranceDocumentAssetId", "Regulated services need proof of insurance");
            }
            if (!Boolean.TRUE.equals(in.agreementsAccepted())) {
                e.put("agreementsAccepted", "Accept the Seller & Provider Terms");
            }
            if (!Boolean.TRUE.equals(in.w9Certified())) {
                e.put("w9Certified", "Certify your taxpayer details (W-9)");
            }
        }
        return new Validated<>(new ComplianceSection(categories, permit, in.beautyLabellingConfirmed(), in.services(),
                in.insuranceDocumentAssetId(), in.agreementsAccepted(), in.w9Certified(), trim(in.w9SignerName())),
                e.map());
    }

    @Override
    public Map<String, String> submission(ApplicationData data, Optional<Plan> plan, RuleContext ctx) {
        Map<String, String> all = new LinkedHashMap<>();
        stepErrors(data, plan, ctx).values().forEach(all::putAll);
        return all;
    }

    @Override
    public List<OnboardingStep> completedSteps(ApplicationData data, Optional<Plan> plan, RuleContext ctx) {
        List<OnboardingStep> done = new ArrayList<>();
        stepErrors(data, plan, ctx).forEach((step, errors) -> {
            if (errors.isEmpty()) {
                done.add(step);
            }
        });
        return done;
    }

    private Map<OnboardingStep, Map<String, String>> stepErrors(ApplicationData data, Optional<Plan> plan,
                                                               RuleContext ctx) {
        Map<OnboardingStep, Map<String, String>> out = new LinkedHashMap<>();
        Map<String, String> business;
        try {
            business = business(data.business(), ctx, true).errors();
        } catch (CountryNotLiveException ex) {
            business = ex.fieldErrors();
        }
        out.put(OnboardingStep.BUSINESS, business);
        EntityType et = data.entityType();
        BusinessDetailsSection d = data.businessDetails();
        BusinessDetailsInput details = d == null ? null : new BusinessDetailsInput(d.legalFirstName(),
                d.legalLastName(), d.legalName(), d.dba(), null, d.dateOfBirth(), d.stateOfFormation(),
                d.dateOfFormation(), d.address(), d.formationDocumentAssetId(), d.irsDeterminationLetterAssetId());
        out.put(OnboardingStep.BUSINESS_DETAILS,
                businessDetails(details, et, d != null && d.hasTaxId(), ctx, true).errors());
        if (et == null || et.needsOwners()) {
            out.put(OnboardingStep.OWNERS, owners(data.owners(), et, ctx, true).errors());
        }
        Errors stripe = new Errors("stripe");
        if (data.stripe() == null || data.stripe().accountId() == null) {
            stripe.put("account", "Start Stripe verification");
        } else if (!data.stripe().detailsSubmitted()) {
            stripe.put("detailsSubmitted", "Finish Stripe verification");
        }
        out.put(OnboardingStep.STRIPE_VERIFICATION, stripe.map());
        out.put(OnboardingStep.PAYOUTS, payouts(null, data.payouts(), true).errors());
        out.put(OnboardingStep.PLAN, plan(data.plan(), plan, data, ctx, true).errors());
        Offering offering = data.business() == null ? null : data.business().offering();
        out.put(OnboardingStep.CATALOG_COMPLIANCE, compliance(data.compliance(), offering, ctx, true).errors());
        return out;
    }

    private static void address(Errors e, Address a, boolean complete) {
        if (a == null) {
            e.required(complete, "address");
            return;
        }
        e.required(complete && blank(a.line1()), "address.line1");
        e.required(complete && blank(a.city()), "address.city");
        if (a.state() != null && !US_STATES.contains(a.state().trim().toUpperCase(Locale.ROOT))) {
            e.put("address.state", "Use the two-letter state code");
        }
        e.required(complete && blank(a.state()), "address.state");
        if (a.postalCode() != null && !ZIP.matcher(a.postalCode().trim()).matches()) {
            e.put("address.postalCode", "Enter a 5-digit ZIP code");
        }
        e.required(complete && blank(a.postalCode()), "address.postalCode");
    }

    private static void nameField(Errors e, String field, String value, boolean complete) {
        String v = trim(value);
        if (v != null && (v.length() < 2 || v.length() > 120)) {
            e.put(field, "2 to 120 characters");
        }
        e.required(complete && v == null, field);
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Collects field errors under a section prefix; the first error per field wins. */
    private static final class Errors {

        private final String section;
        private final Map<String, String> errors = new LinkedHashMap<>();

        Errors(String section) {
            this.section = section;
        }

        void put(String field, String message) {
            String key = field.startsWith("[") ? section + field : section + "." + field;
            errors.putIfAbsent(key, message);
        }

        void required(boolean missing, String field) {
            if (missing) {
                put(field, "Required");
            }
        }

        Map<String, String> map() {
            return errors;
        }
    }
}
