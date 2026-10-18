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

    private static final int MAX_PAGE_DESCRIPTION = 500;

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
        BusinessSection.Branding branding = branding(in.branding(), e);
        return new Validated<>(new BusinessSection(in.offering(), in.entityType(), country, store,
                email == null ? null : email.toLowerCase(Locale.ROOT), e164 != null ? e164 : phone, null, branding),
                e.map());
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
            if (o.email() != null && !o.email().isBlank() && !EMAIL.matcher(o.email().trim()).matches()) {
                e.put(p + "email", "Enter a valid email address");
            }
            if (o.dateOfBirth() != null && o.dateOfBirth().isAfter(ctx.today().minusYears(MIN_AGE))) {
                e.put(p + "dateOfBirth", "Owners must be 18 or older");
            }
            if (complete) {
                e.required(blank(o.title()), p + "title");
                e.required(blank(o.email()), p + "email");
                e.required(o.dateOfBirth() == null, p + "dateOfBirth");
            }
            personAddress(e, p + "address", o.address(), complete);
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
        if (in.paymentMethodKind() == PaymentMethodKind.US_BANK_ACCOUNT && complete
                && (data.payouts() == null || !data.payouts().externalAccountLinked())) {
            e.put("paymentMethodKind", "Add your payout bank account in Stripe verification first");
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
        if (permit != null && !blank(permit.permitType())
                && !ComplianceSection.PERMIT_TYPES.contains(permit.permitType().trim().toUpperCase(Locale.ROOT))) {
            e.put("foodPermit.permitType", "Choose the kind of permit");
        }
        if (complete && food) {
            if (permit == null) {
                e.put("foodPermit", "Food sellers need a food establishment or cottage food permit");
            } else {
                e.required(blank(permit.permitType()), "foodPermit.permitType");
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
        out.put(OnboardingStep.STRIPE_VERIFICATION, stripeErrors(data.stripe()));
        out.put(OnboardingStep.PAYOUTS, payoutErrors(data.payouts(), data.stripe()));
        out.put(OnboardingStep.PLAN, plan(data.plan(), plan, data, ctx, true).errors());
        Offering offering = data.business() == null ? null : data.business().offering();
        out.put(OnboardingStep.CATALOG_COMPLIANCE, compliance(data.compliance(), offering, ctx, true).errors());
        return out;
    }

    /**
     * Stripe verification is complete when Stripe says so: the details were submitted, nothing is currently due and
     * the account was not rejected. Pending verification (Stripe still checking) does not hold the applicant up.
     */
    static Map<String, String> stripeErrors(StripeVerification s) {
        Errors e = new Errors("stripe");
        if (s == null || s.accountId() == null) {
            e.put("account", "Start Stripe verification");
        } else if (s.disabledReason() != null && s.disabledReason().startsWith("rejected")) {
            e.put("account", "Stripe could not verify this business");
        } else if (!s.detailsSubmitted()) {
            e.put("detailsSubmitted", "Finish Stripe verification");
        } else if (!s.currentlyDue().isEmpty()) {
            e.put("currentlyDue", "Stripe needs more information: " + String.join(", ", s.currentlyDue()));
        }
        return e.map();
    }

    /**
     * Payouts are complete once Stripe holds a payout bank account it accepted (not failed) and payouts are on, or
     * only waiting for Stripe's checks: Stripe collects the bank in its embedded onboarding, and payouts stay on
     * AfroTransact's manual schedule either way. A prefilled holder name or numbers typed into AfroTransact are not
     * a payout account.
     */
    static Map<String, String> payoutErrors(PayoutSection p, StripeVerification s) {
        Errors e = new Errors("payouts");
        if (s == null || s.accountId() == null) {
            e.put("externalAccount", "Start Stripe verification: Stripe collects your payout bank account there");
        } else if (p == null || p.accountLast4() == null) {
            e.put("externalAccount", "Add your payout bank account in Stripe verification");
        } else if (p.failed() || !p.externalAccountLinked()) {
            e.put("externalAccount", "Stripe couldn't use this bank account; change it");
        } else if (s.currentlyDue().stream().anyMatch(k -> k.startsWith("external_account"))) {
            e.put("externalAccount", "Stripe needs your payout bank account");
        } else if (!s.payoutsEnabled() && s.disabledReason() != null
                && !s.disabledReason().equals("requirements.pending_verification")) {
            e.put("payoutsEnabled", "Stripe hasn't enabled payouts for this account yet");
        }
        return e.map();
    }

    /** An owner's home address: optional while drafting, every line but line 2 when complete. */
    private static void personAddress(Errors e, String field, Address a, boolean complete) {
        if (a == null) {
            e.required(complete, field);
            return;
        }
        e.required(complete && blank(a.line1()), field + ".line1");
        e.required(complete && blank(a.city()), field + ".city");
        if (a.state() != null && !a.state().isBlank() && !US_STATES.contains(a.state().trim().toUpperCase(Locale.ROOT))) {
            e.put(field + ".state", "Use the two-letter state code");
        }
        e.required(complete && blank(a.state()), field + ".state");
        if (a.postalCode() != null && !a.postalCode().isBlank() && !ZIP.matcher(a.postalCode().trim()).matches()) {
            e.put(field + ".postalCode", "Enter a 5-digit ZIP code");
        }
        e.required(complete && blank(a.postalCode()), field + ".postalCode");
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

    private static BusinessSection.Branding branding(BusinessSection.Branding in, Errors e) {
        if (in == null) {
            return null;
        }
        String description = trim(in.description());
        if (description != null && description.length() > MAX_PAGE_DESCRIPTION) {
            e.put("branding.description", "At most " + MAX_PAGE_DESCRIPTION + " characters");
        }
        String color = trim(in.brandColor());
        if (color != null && !color.matches("^#[0-9a-fA-F]{6}$")) {
            e.put("branding.brandColor", "Use a colour like #1a7f4b");
        }
        String logo = trim(in.logoUrl());
        if (logo != null && !logo.matches("^https://\\S+$")) {
            e.put("branding.logoUrl", "Upload your logo again");
        }
        if (description == null && color == null && logo == null) {
            return null;
        }
        return new BusinessSection.Branding(description, color == null ? null : color.toLowerCase(Locale.ROOT), logo);
    }
}
