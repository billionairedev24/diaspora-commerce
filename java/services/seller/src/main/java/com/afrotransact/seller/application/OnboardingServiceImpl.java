package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.afrotransact.seller.domain.Address;
import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationData;
import com.afrotransact.seller.domain.ApplicationRules;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.BusinessDetailsInput;
import com.afrotransact.seller.domain.BusinessDetailsSection;
import com.afrotransact.seller.domain.BusinessSection;
import com.afrotransact.seller.domain.EntityType;
import com.afrotransact.seller.domain.LaunchListEntry;
import com.afrotransact.seller.domain.OnboardingStep;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.Owner;
import com.afrotransact.seller.domain.PayoutInput;
import com.afrotransact.seller.domain.PayoutSection;
import com.afrotransact.seller.domain.PhoneNumbers;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.RuleContext;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.TaxIdCheck;
import com.afrotransact.seller.domain.TaxIdType;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.domain.Validated;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.BankAccountDetails;
import com.afrotransact.seller.port.CallerProfile;
import com.afrotransact.seller.port.CallerProfileProvider;
import com.afrotransact.seller.port.FieldCipher;
import com.afrotransact.seller.port.LaunchListRepository;
import com.afrotransact.seller.port.LinkedBankAccount;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.StripeConnectGateway;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OnboardingServiceImpl implements OnboardingService {

    static final String PREFILL_EMAIL = "business.contactEmail";
    static final String PREFILL_PHONE = "business.phone";
    static final String PREFILL_FIRST = "businessDetails.legalFirstName";
    static final String PREFILL_LAST = "businessDetails.legalLastName";
    static final String PREFILL_HOLDER = "payouts.accountHolderName";
    static final String PREFILL_STORE = "business.storeName";
    static final String PREFILL_ADDRESS = "businessDetails.address";

    private static final int MAX_STORE_NAME = 120;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Map<String, Function<ApplicationData, String>> PREFILLABLE = Map.of(
            PREFILL_EMAIL, d -> d.business() == null ? null : d.business().contactEmail(),
            PREFILL_PHONE, d -> d.business() == null ? null : d.business().phone(),
            PREFILL_STORE, d -> d.business() == null ? null : d.business().storeName(),
            PREFILL_FIRST, d -> d.businessDetails() == null ? null : d.businessDetails().legalFirstName(),
            PREFILL_LAST, d -> d.businessDetails() == null ? null : d.businessDetails().legalLastName(),
            PREFILL_ADDRESS, d -> d.businessDetails() == null || d.businessDetails().address() == null ? null
                    : d.businessDetails().address().toString(),
            PREFILL_HOLDER, d -> d.payouts() == null ? null : d.payouts().accountHolderName());

    private final ApplicationRepository apps;
    private final OrganisationRepository orgs;
    private final LaunchListRepository launchList;
    private final ApplicationRules rules;
    private final ApplicationViews views;
    private final FieldCipher cipher;
    private final CallerProfileProvider profiles;
    private final PhoneNumbers phones;
    private final StripeConnectGateway stripe;
    private final AccessGuard access;
    private final DomainEventPublisher events;
    private final SellerSettings settings;
    private final Clock clock;

    OnboardingServiceImpl(ApplicationRepository apps, OrganisationRepository orgs, LaunchListRepository launchList,
                          ApplicationRules rules, ApplicationViews views, FieldCipher cipher,
                          CallerProfileProvider profiles, PhoneNumbers phones, StripeConnectGateway stripe,
                          AccessGuard access, DomainEventPublisher events, SellerSettings settings, Clock clock) {
        this.apps = apps;
        this.orgs = orgs;
        this.launchList = launchList;
        this.rules = rules;
        this.views = views;
        this.cipher = cipher;
        this.profiles = profiles;
        this.phones = phones;
        this.stripe = stripe;
        this.access = access;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public StartResult start(OrgKind kind, ApplicationPrefill prefill) {
        Actor actor = access.applicant();
        Known known = known(profiles.current(), prefill == null ? ApplicationPrefill.none() : prefill);
        Optional<Application> existing = apps.lockByApplicant(actor.subject());
        if (existing.isPresent()) {
            Application app = existing.get();
            if (app.kind() != kind) {
                throw new SellerStateConflict("seller.application-exists",
                        "You already have a " + app.kind().name().toLowerCase(Locale.ROOT)
                                + " application; continue that one");
            }
            return new StartResult(applicantView(resumeFilled(app, known)), false);
        }
        Instant now = Instant.now(clock);
        Set<String> prefilled = new HashSet<>();
        ApplicationData data = fillBlanks(ApplicationData.empty(), known, prefilled);
        Organisation org = orgs.insert(Organisation.draft(Ids.org(), kind, null, now));
        orgs.addMember(new OrgMember(org.id(), actor.subject(), kind.ownerRole(), now));
        Application app = Application.start(Ids.application(), apps.nextReference(), org.id(), actor.subject(), kind,
                data, prefilled, now);
        if (!apps.insert(app)) {
            throw new SellerStateConflict("seller.application-in-flight",
                    "Your application is being created; reload to continue");
        }
        apps.appendTimeline(new TimelineEntry(UUID.randomUUID(), app.id(), ReviewAction.STARTED, null,
                ApplicationStatus.DRAFT, actor.subject(), null, prefilled.isEmpty() ? null
                : "Prefilled from the customer account: " + String.join(", ", prefilled.stream().sorted().toList()),
                List.of(), now));
        return new StartResult(applicantView(app), true);
    }

    /** What the account knows: the token's claims first, then the prefill gateway-bff read from identity. */
    private record Known(String email, String phone, String first, String last, String full, String store,
                         Address address) {
    }

    private Known known(CallerProfile profile, ApplicationPrefill extra) {
        String email = clean(profile.email()) != null ? clean(profile.email()) : clean(extra.email());
        if (email != null && !EMAIL.matcher(email).matches()) {
            email = null;
        }
        String phone = e164(profile.phone());
        if (phone == null) {
            phone = e164(extra.phone());
        }
        String store = clean(extra.storeName());
        if (store != null && store.length() > MAX_STORE_NAME) {
            store = null;
        }
        String first = clean(profile.givenName()) != null ? clean(profile.givenName()) : clean(extra.givenName());
        String last = clean(profile.familyName()) != null ? clean(profile.familyName()) : clean(extra.familyName());
        String full = clean(profile.fullName()) != null ? clean(profile.fullName())
                : first != null && last != null ? first + " " + last : null;
        return new Known(email == null ? null : email.toLowerCase(Locale.ROOT), phone, first, last, full, store,
                usable(extra.address()));
    }

    /**
     * A resumed draft gets what the account knows in answers that are still empty (for example an application
     * started before the address was saved); answers the applicant gave are never replaced.
     */
    private Application resumeFilled(Application app, Known known) {
        if (!app.status().editableByApplicant()) {
            return app;
        }
        Set<String> prefilled = new HashSet<>(app.prefilledFields());
        ApplicationData next = fillBlanks(app.data(), known, prefilled);
        if (next.equals(app.data())) {
            return app;
        }
        return apps.update(app.edit(next, app.currentStep(), prefilled, Instant.now(clock)));
    }

    /** Fills the empty prefillable answers from {@code known}, marking each one filled in {@code prefilled}. */
    private static ApplicationData fillBlanks(ApplicationData data, Known k, Set<String> prefilled) {
        BusinessSection b = data.business() == null ? new BusinessSection(null, null, null, null, null, null, null)
                : data.business();
        String email = fill(prefilled, PREFILL_EMAIL, b.contactEmail(), k.email());
        String phone = fill(prefilled, PREFILL_PHONE, b.phone(), k.phone());
        String store = fill(prefilled, PREFILL_STORE, b.storeName(), k.store());
        BusinessSection nb = new BusinessSection(b.offering(), b.entityType(), b.country(), store, email, phone,
                b.website());
        ApplicationData next = data;
        if (!nb.equals(b) || data.business() != null) {
            next = next.withBusiness(nb);
        }
        BusinessDetailsSection d = data.businessDetails();
        String first = fill(prefilled, PREFILL_FIRST, d == null ? null : d.legalFirstName(), k.first());
        String last = fill(prefilled, PREFILL_LAST, d == null ? null : d.legalLastName(), k.last());
        Address address = d != null && d.address() != null ? d.address() : k.address();
        if (address != null && (d == null || d.address() == null)) {
            prefilled.add(PREFILL_ADDRESS);
        }
        if (d == null) {
            if (first != null || last != null || address != null) {
                next = next.withBusinessDetails(new BusinessDetailsSection(first, last, null, null, null, null, null,
                        null, null, null, address, null, null));
            }
        } else {
            next = next.withBusinessDetails(new BusinessDetailsSection(first, last, d.legalName(), d.dba(),
                    d.taxIdType(), d.taxIdLast4(), d.taxIdCipher(), d.dateOfBirth(), d.stateOfFormation(),
                    d.dateOfFormation(), address, d.formationDocumentAssetId(), d.irsDeterminationLetterAssetId()));
        }
        PayoutSection p = data.payouts();
        String holder = fill(prefilled, PREFILL_HOLDER, p == null ? null : p.accountHolderName(), k.full());
        if (p == null) {
            if (holder != null) {
                next = next.withPayouts(new PayoutSection(holder, null, null, null, null, false));
            }
        } else {
            next = next.withPayouts(new PayoutSection(holder, p.accountType(), p.bankName(), p.routingLast4(),
                    p.accountLast4(), p.externalAccountLinked()));
        }
        return next;
    }

    /** The current answer, or the known value (marked as prefilled) when there is none. */
    private static String fill(Set<String> prefilled, String field, String current, String known) {
        if (clean(current) != null || known == null) {
            return current;
        }
        prefilled.add(field);
        return known;
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationView mine() {
        return applicantView(current(access.applicant()));
    }

    @Override
    @Transactional
    public ApplicationView saveStep(OnboardingStep step, StepInput in) {
        Actor actor = access.applicant();
        Application app = apps.lockByApplicant(actor.subject()).orElseThrow(OnboardingServiceImpl::noApplication);
        if (!app.status().editableByApplicant()) {
            throw new SellerStateConflict("seller.application-locked",
                    "Application " + app.reference() + " is " + app.status() + " and can't be edited");
        }
        RuleContext ctx = views.context(app);
        ApplicationData data = app.data();
        ApplicationData next = switch (step) {
            case BUSINESS -> saveBusiness(data, in, ctx);
            case BUSINESS_DETAILS -> saveDetails(data, in, ctx);
            case OWNERS -> saveOwners(data, in, ctx);
            case STRIPE_VERIFICATION -> throw new SellerRuleViolation("seller.step-managed-by-stripe",
                    "Stripe verification happens in the embedded Stripe component; create an account session");
            case PAYOUTS -> savePayouts(data, in);
            case PLAN -> {
                if (in.plan() == null) {
                    throw missing("plan");
                }
                views.checkPlanChoice(app, in.plan());
                Optional<Plan> plan = in.plan().planCode() == null ? Optional.empty()
                        : views.plan(data.withPlan(in.plan()));
                yield data.withPlan(valid(rules.plan(in.plan(), plan, data, ctx, false)));
            }
            case CATALOG_COMPLIANCE -> {
                if (in.compliance() == null) {
                    throw missing("compliance");
                }
                yield data.withCompliance(valid(rules.compliance(in.compliance(),
                        data.business() == null ? null : data.business().offering(), ctx, false)));
            }
            case REVIEW -> data;
        };
        Application saved = apps.update(app.edit(next, step, prefilled(app.prefilledFields(), data, next),
                Instant.now(clock)));
        return applicantView(saved);
    }

    @Override
    @Transactional
    public ApplicationView submit() {
        Actor actor = access.applicant();
        Application app = apps.lockByApplicant(actor.subject()).orElseThrow(OnboardingServiceImpl::noApplication);
        if (!app.status().editableByApplicant()) {
            throw new SellerStateConflict("seller.application-locked",
                    "Application " + app.reference() + " is already " + app.status());
        }
        Map<String, String> errors = rules.submission(app.data(), views.plan(app.data()), views.context(app));
        if (!errors.isEmpty()) {
            throw new SellerRuleViolation("seller.application-incomplete",
                    "Some steps are incomplete or invalid; fix the highlighted fields and submit again", errors);
        }
        Instant now = Instant.now(clock);
        boolean resubmission = app.status() == ApplicationStatus.INFO_REQUESTED;
        Application submitted = apps.update(app.submit(now));
        orgs.findById(app.orgId()).ifPresent(o -> orgs.update(o.withStatus(OrgStatus.PENDING_REVIEW, now)));
        TimelineEntry entry = new TimelineEntry(UUID.randomUUID(), app.id(),
                resubmission ? ReviewAction.RESUBMITTED : ReviewAction.SUBMITTED, app.status(),
                submitted.status(), actor.subject(), null, null, List.of(), now);
        apps.appendTimeline(entry);
        events.publish(SellerEvents.APPLICATION_SUBMITTED, SellerEvents.AGGREGATE_APPLICATION, app.id(),
                ApplicationEventPayload.of(submitted, entry));
        return applicantView(submitted);
    }

    @Override
    @Transactional
    public boolean joinLaunchList(String country, String email, boolean shoppingUpdates) {
        String c = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!c.matches("[A-Z]{2}")) {
            throw new SellerRuleViolation("seller.invalid-input", "Use a two-letter ISO country code",
                    Map.of("country", "Invalid"));
        }
        if (!EMAIL.matcher(e).matches()) {
            throw new SellerRuleViolation("seller.invalid-input", "Enter a valid email address",
                    Map.of("email", "Invalid"));
        }
        if (settings.liveCountries().contains(c)) {
            throw new SellerRuleViolation("seller.country-live", c + " is already open for sellers; start selling",
                    Map.of("country", "Already live"));
        }
        return launchList.upsert(new LaunchListEntry(UUID.randomUUID(), c, e, shoppingUpdates, Instant.now(clock)));
    }

    @Override
    public List<String> liveCountries() {
        return settings.liveCountries().stream().sorted().toList();
    }

    private ApplicationData saveBusiness(ApplicationData data, StepInput in, RuleContext ctx) {
        if (in.business() == null) {
            throw missing("business");
        }
        BusinessSection b = valid(rules.business(in.business(), ctx, false));
        ApplicationData next = data.withBusiness(b);
        BusinessDetailsSection d = data.businessDetails();
        if (d != null && d.hasTaxId() && b.entityType() != null
                && (b.entityType().taxIdType() == TaxIdType.EIN) != (d.taxIdType() == TaxIdType.EIN)) {
            next = next.withBusinessDetails(d.withTaxId(null, null, null));
        }
        if (b.entityType() != null && !b.entityType().needsOwners() && data.owners() != null) {
            next = next.withOwners(null);
        }
        return next;
    }

    private ApplicationData saveDetails(ApplicationData data, StepInput in, RuleContext ctx) {
        BusinessDetailsInput d = in.businessDetails();
        if (d == null) {
            throw missing("businessDetails");
        }
        EntityType et = data.entityType();
        if (et == null) {
            throw new SellerRuleViolation("seller.business-step-required",
                    "Choose your business type on the first step before entering business details",
                    Map.of("business.entityType", "Required"));
        }
        BusinessDetailsSection stored = data.businessDetails();
        valid(rules.businessDetails(d, et, stored != null && stored.hasTaxId(), ctx, false));
        TaxIdType type = stored == null ? null : stored.taxIdType();
        String last4 = stored == null ? null : stored.taxIdLast4();
        String sealed = stored == null ? null : stored.taxIdCipher();
        if (d.taxId() != null) {
            TaxIdCheck check = valid(rules.taxId(et, d.taxId()));
            type = check.type();
            last4 = check.last4();
            sealed = cipher.encrypt(check.formatted());
        }
        String state = d.stateOfFormation() == null ? null : d.stateOfFormation().trim().toUpperCase(Locale.ROOT);
        return data.withBusinessDetails(new BusinessDetailsSection(clean(d.legalFirstName()), clean(d.legalLastName()),
                clean(d.legalName()), clean(d.dba()), type, last4, sealed, d.dateOfBirth(), state,
                d.dateOfFormation(), d.address(), clean(d.formationDocumentAssetId()),
                clean(d.irsDeterminationLetterAssetId())));
    }

    private ApplicationData saveOwners(ApplicationData data, StepInput in, RuleContext ctx) {
        EntityType et = data.entityType();
        if (et != null && !et.needsOwners()) {
            throw new SellerRuleViolation("seller.step-not-applicable", "Sole proprietors don't list owners");
        }
        List<Owner> owners = valid(rules.owners(in.owners(), et, ctx, false));
        return data.withOwners(owners);
    }

    /**
     * Payouts: the bank account goes to Stripe as the connected account's payout account (a client-side token, or the
     * numbers the applicant typed, sent once and never stored). The step is complete only once Stripe accepted it.
     */
    private ApplicationData savePayouts(ApplicationData data, StepInput in) {
        if (in.payouts() == null) {
            throw missing("payouts");
        }
        PayoutInput raw = in.payouts();
        PayoutSection p = valid(rules.payouts(raw, data.payouts(), false));
        String token = clean(raw.externalAccountToken());
        String routing = clean(raw.routingNumber());
        String account = clean(raw.accountNumber());
        if (token == null && (routing == null || account == null)) {
            return data.withPayouts(p);
        }
        if (data.stripe() == null || data.stripe().accountId() == null) {
            throw new SellerRuleViolation("seller.stripe-account-required",
                    "Finish Stripe verification before adding your payout bank account",
                    Map.of("payouts.externalAccount", "Finish Stripe verification first"));
        }
        if (p.accountHolderName() == null) {
            throw new SellerRuleViolation("seller.invalid-application", "Some fields are invalid",
                    Map.of("payouts.accountHolderName", "Required"));
        }
        String bankName = p.bankName();
        String last4 = p.accountLast4();
        if (token != null) {
            stripe.attachExternalAccount(data.stripe().accountId(), token);
        } else {
            EntityType et = data.entityType();
            String holderType = et == null || et == EntityType.SOLE_PROPRIETOR ? "individual" : "company";
            LinkedBankAccount linked = stripe.attachBankAccount(data.stripe().accountId(),
                    new BankAccountDetails(p.accountHolderName(), holderType, routing, account));
            bankName = clean(linked.bankName()) != null ? linked.bankName() : bankName;
            last4 = clean(linked.last4()) != null ? linked.last4() : last4;
        }
        return data.withPayouts(new PayoutSection(p.accountHolderName(), p.accountType(), bankName, p.routingLast4(),
                last4, true));
    }

    private ApplicationView applicantView(Application app) {
        return views.view(app, PiiLevel.APPLICANT, apps.timeline(app.id()), List.of());
    }

    private Application current(Actor actor) {
        return apps.findByApplicant(actor.subject()).orElseThrow(OnboardingServiceImpl::noApplication);
    }

    /** Keeps a field marked as prefilled only while its value is unchanged. */
    private static Set<String> prefilled(Set<String> marked, ApplicationData before, ApplicationData after) {
        Set<String> out = new HashSet<>();
        for (String field : marked) {
            Function<ApplicationData, String> f = PREFILLABLE.get(field);
            if (f != null && f.apply(after) != null && Objects.equals(f.apply(before), f.apply(after))) {
                out.add(field);
            }
        }
        return out;
    }

    private static <T> T valid(Validated<T> v) {
        if (!v.ok()) {
            throw new SellerRuleViolation("seller.invalid-application", "Some fields are invalid", v.errors());
        }
        return v.value();
    }

    private String e164(String raw) {
        return clean(raw) == null ? null : phones.toE164Mobile(raw, "US").orElse(null);
    }

    /** An address-book address usable as a business address (street, city, state and ZIP present). */
    private static Address usable(Address a) {
        if (a == null || clean(a.line1()) == null || clean(a.city()) == null || clean(a.state()) == null
                || clean(a.postalCode()) == null) {
            return null;
        }
        String country = clean(a.country()) == null ? "US" : clean(a.country()).toUpperCase(Locale.ROOT);
        return new Address(clean(a.line1()), clean(a.line2()), clean(a.city()),
                clean(a.state()).toUpperCase(Locale.ROOT), clean(a.postalCode()), country);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static SellerRuleViolation missing(String section) {
        return new SellerRuleViolation("seller.invalid-application", "Send the " + section + " step's data",
                Map.of(section, "Required"));
    }

    private static NotFoundException noApplication() {
        return new NotFoundException("seller.no-application", "You haven't started an application yet");
    }
}
