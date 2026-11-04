package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import com.afrotransact.bookings.domain.Activity;
import com.afrotransact.bookings.domain.BalanceCharge;
import com.afrotransact.bookings.domain.BalanceState;
import com.afrotransact.bookings.domain.AddOn;
import com.afrotransact.bookings.domain.BookedService;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingEarnings;
import com.afrotransact.bookings.domain.BookingStatus;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.BookingsStateConflict;
import com.afrotransact.bookings.domain.CancellationQuote;
import com.afrotransact.bookings.domain.MoneyMove;
import com.afrotransact.bookings.domain.Party;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.TakeHomeCalculator;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.domain.Transition;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.bookings.port.IdempotencyStore;
import com.afrotransact.bookings.port.OfferingRepository;
import com.afrotransact.bookings.port.OrgVisibilityRepository;
import com.afrotransact.bookings.port.PaymentsClient;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.bookings.port.TeamRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BookingServiceImpl implements BookingService {

    private final BookingRepository bookings;
    private final OfferingRepository offerings;
    private final ProviderRepository providers;
    private final TeamRepository team;
    private final IdempotencyStore idempotency;
    private final PaymentsClient payments;
    private final SlotFinder slots;
    private final BookingMoney money;
    private final BookingEventPublisher events;
    private final PaymentOutcomes outcomes;
    private final TakeHomeCalculator takeHome;
    private final ProviderFees fees;
    private final OrgVisibilityRepository visibility;
    private final BookingsAccess access;
    private final BookingsSettings settings;
    private final Clock clock;

    BookingServiceImpl(BookingRepository bookings, OfferingRepository offerings, ProviderRepository providers,
                       TeamRepository team, IdempotencyStore idempotency, PaymentsClient payments, SlotFinder slots,
                       BookingMoney money, BookingEventPublisher events, PaymentOutcomes outcomes,
                       TakeHomeCalculator takeHome, ProviderFees fees, OrgVisibilityRepository visibility,
                       BookingsAccess access, BookingsSettings settings, Clock clock) {
        this.fees = fees;
        this.visibility = visibility;
        this.bookings = bookings;
        this.offerings = offerings;
        this.providers = providers;
        this.team = team;
        this.idempotency = idempotency;
        this.payments = payments;
        this.slots = slots;
        this.money = money;
        this.events = events;
        this.outcomes = outcomes;
        this.takeHome = takeHome;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ customer: book

    @Override
    @Transactional
    public BookingResult create(CreateBooking cmd, String idempotencyKey) {
        Actor customer = access.customer();
        requireKey(idempotencyKey);
        Optional<UUID> prior = idempotency.claim("create", idempotencyKey,
                Fingerprint.of(customer.subject() + "|" + cmd));
        if (prior.isPresent()) {
            return new BookingResult(require(prior.get()), true);
        }
        ServiceOffering offering = offerings.find(cmd.serviceId()).filter(ServiceOffering::active)
                .filter(o -> !visibility.isHidden(o.orgId()))
                .orElseThrow(() -> new NotFoundException("bookings.service-not-found",
                        "This service is no longer available."));
        Provider provider = providers.find(offering.orgId())
                .orElseThrow(() -> ProviderSetupServiceImpl.notFound(offering.orgId()));
        List<AddOn> addOns = offering.pick(cmd.addOnIds());
        int duration = offering.durationMinutes() + addOns.stream().mapToInt(AddOn::extraMinutes).sum();
        Instant now = Instant.now(clock);

        TeamMember member = null;
        for (TeamMember candidate : slots.candidates(offering, cmd.memberId())) {
            TeamMember locked = team.lock(candidate.id()).orElseThrow();
            if (locked.takesBookings() && slots.isFree(provider, locked, cmd.startAt(), duration, null, now)) {
                member = locked;
                break;
            }
        }
        if (member == null) {
            throw new BookingsStateConflict("bookings.slot-taken", "That time was just taken. Pick another slot.");
        }
        BookedService snapshot = BookedService.of(offering, addOns, cmd.mode(), provider.displayName(),
                member.displayName());
        Booking hold = Booking.hold(UUID.randomUUID(), bookings.nextReference(), provider.orgId(), member.id(),
                customer.subject(), cmd.customer(), snapshot, cmd.location(), cmd.notes(), cmd.channel(),
                cmd.startAt(), provider.rules(), provider.policy(), now.plus(settings.holdTtl()), now);
        Booking stored = bookings.insert(hold);

        PaymentsClient.Intent intent = payments.createIntent(new PaymentsClient.CreateIntent(stored.reference(),
                provider.orgId(), snapshot.priceMinor(), snapshot.currency(), intentLines(snapshot),
                Map.of("bookingId", stored.id().toString(), "kind", "booking", "serviceId",
                        offering.id().toString()), now, customer.subject(), true,
                        fees.paymentCategory(provider.orgId(), snapshot.category())),
                stored.reference() + ":create-intent");
        stored.attachPayment(intent.id(), intent.clientSecret());
        Booking saved = bookings.update(stored);
        if ("AUTHORIZED".equals(intent.status())) {
            saved = outcomes.apply(saved.id(), intent.status(), null);
        }
        idempotency.complete("create", idempotencyKey, saved.id());
        return new BookingResult(saved, false);
    }

    private static List<PaymentsClient.Line> intentLines(BookedService s) {
        List<PaymentsClient.Line> lines = new ArrayList<>();
        if (s.depositMinor() > 0) {
            lines.add(new PaymentsClient.Line(MoneyMove.DEPOSIT, s.depositMinor()));
        }
        if (s.balanceMinor() > 0) {
            lines.add(new PaymentsClient.Line(MoneyMove.BALANCE, s.balanceMinor()));
        }
        return lines;
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public Booking get(UUID id) {
        Booking b = require(id);
        access.partyFor(b, BookingsPermissions.READ, assignee(b));
        return b;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Activity> history(UUID id) {
        get(id);
        return bookings.history(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Booking> mine() {
        Actor customer = access.customer();
        return bookings.forCustomer(customer.subject()).stream()
                .sorted(Comparator.comparing((Booking b) -> !b.status().isActive()).thenComparing(Booking::startAt))
                .toList();
    }

    @Override
    @Transactional
    public Booking syncPayment(UUID id) {
        Booking b = require(id);
        access.partyFor(b, BookingsPermissions.READ, assignee(b));
        if (b.paymentId() == null) {
            return b;
        }
        PaymentsClient.Intent intent = payments.get(b.paymentId(), true);
        Booking synced = outcomes.apply(id, intent.status(), intent.failureMessage());
        BalanceCharge balance = synced.balance();
        if (balance.paymentId() != null && (balance.state() == BalanceState.ACTION_REQUIRED
                || balance.state() == BalanceState.PROCESSING)) {
            PaymentsClient.Intent charge = payments.get(balance.paymentId(), true);
            synced = outcomes.applyBalance(id, balance.paymentId(), charge.status(), charge.clientSecret(),
                    charge.failureMessage());
        }
        return synced;
    }

    @Override
    @Transactional(readOnly = true)
    public BalancePayment balancePayment(UUID id) {
        Booking b = require(id);
        Party party = access.partyFor(b, BookingsPermissions.READ, assignee(b));
        BalanceCharge balance = b.balance();
        String secret = null;
        if (party == Party.CUSTOMER && balance.state() == BalanceState.ACTION_REQUIRED) {
            secret = balance.clientSecret() != null ? balance.clientSecret()
                    : payments.get(balance.paymentId(), false).clientSecret();
        }
        return new BalancePayment(b.id(), b.reference(), balance.state(), balance.paymentId(),
                b.service().balanceMinor(), b.service().currency(), secret, balance.failure(), balance.attempts(),
                balance.nextAttemptAt());
    }

    @Override
    @Transactional(readOnly = true)
    public CancellationQuote cancellationQuote(UUID id) {
        Booking b = require(id);
        Party party = access.partyFor(b, BookingsPermissions.MANAGE, assignee(b));
        return b.quoteCancellation(party, Instant.now(clock), settings.lateProviderFeeFor(b.orgId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Booking> forProvider(String orgId, Instant from, Instant to, UUID memberId, BookingStatus status) {
        ProviderScope scope = access.provider(orgId, BookingsPermissions.READ);
        return scoped(scope, bookings.forProvider(orgId, from, to, memberId, status));
    }

    @Override
    @Transactional(readOnly = true)
    public EarningsView earnings(String orgId, Instant from, Instant to) {
        ProviderScope scope = access.provider(orgId, BookingsPermissions.READ);
        Provider p = providers.find(orgId).orElseThrow(() -> ProviderSetupServiceImpl.notFound(orgId));
        List<EarningsView.Row> rows = scoped(scope, bookings.forProvider(orgId, from, to, null, null)).stream()
                .filter(b -> b.status() != BookingStatus.PENDING_PAYMENT)
                .map(b -> new EarningsView.Row(b, takeHome.earnings(fees.forOrg(orgId, b.service().category()), b)))
                .toList();
        long collected = 0;
        long commission = 0;
        long card = 0;
        long fees = 0;
        long net = 0;
        long held = 0;
        for (EarningsView.Row r : rows) {
            BookingEarnings e = r.earnings();
            collected += e.collectedMinor();
            commission += e.commissionMinor();
            card += e.cardFeesMinor();
            fees += e.providerFeeMinor();
            net += e.netMinor();
            held += e.heldMinor();
        }
        return new EarningsView(orgId, p.currency(), rows,
                new EarningsView.Totals(collected, commission, card, fees, net, held));
    }

    private List<Booking> scoped(ProviderScope scope, List<Booking> list) {
        if (!scope.ownOnly()) {
            return list;
        }
        Optional<TeamMember> own = team.byUser(scope.orgId(), scope.actor().subject());
        return own.map(m -> list.stream().filter(b -> b.memberId().equals(m.id())).toList()).orElse(List.of());
    }

    // ------------------------------------------------------------------ cancellation & new times

    @Override
    @Transactional
    public Cancelled cancel(UUID id, String reason, String idempotencyKey) {
        Booking.Cancellation[] applied = new Booking.Cancellation[1];
        Booking saved = act(id, "cancel", idempotencyKey, reason, null, (b, party) -> {
            applied[0] = b.cancel(party, reason, Instant.now(clock), settings.lateProviderFeeFor(b.orgId()));
            if (party == Party.PROVIDER && applied[0].quote().providerFeeMinor() > 0 && !settings.isRetail(b.orgId())) {
                providers.lock(b.orgId()).ifPresent(p -> providers.update(p.withStrike(Instant.now(clock))));
            }
            return applied[0].transition();
        });
        CancellationQuote quote = applied[0] == null ? null : applied[0].quote();
        return new Cancelled(saved, quote);
    }

    @Override
    @Transactional
    public Booking reschedule(UUID id, Instant newStart, String idempotencyKey) {
        return act(id, "reschedule", idempotencyKey, newStart, Party.CUSTOMER, (b, party) -> {
            Provider p = providers.find(b.orgId()).orElseThrow();
            requireFree(p, b, newStart);
            return b.requestReschedule(newStart, p.rules().autoAcceptReschedule(), Instant.now(clock));
        });
    }

    @Override
    @Transactional
    public Booking acceptNewTime(UUID id, String idempotencyKey) {
        return act(id, "accept-time", idempotencyKey, null, null, (b, party) -> {
            if (b.proposedStartAt() != null) {
                requireFree(providers.find(b.orgId()).orElseThrow(), b, b.proposedStartAt());
            }
            return b.acceptNewTime(party, Instant.now(clock));
        });
    }

    @Override
    @Transactional
    public Booking declineNewTime(UUID id, String idempotencyKey) {
        return act(id, "decline-time", idempotencyKey, null, null,
                (b, party) -> b.declineNewTime(party, Instant.now(clock)));
    }

    // ------------------------------------------------------------------ provider actions

    @Override
    @Transactional
    public Booking confirm(UUID id, String idempotencyKey) {
        return act(id, "confirm", idempotencyKey, null, Party.PROVIDER, (b, party) -> b.confirm(Instant.now(clock)));
    }

    @Override
    @Transactional
    public Booking decline(UUID id, String reason, String idempotencyKey) {
        return act(id, "decline", idempotencyKey, reason, Party.PROVIDER,
                (b, party) -> b.decline(reason, Instant.now(clock)));
    }

    @Override
    @Transactional
    public Booking proposeTime(UUID id, Instant newStart, String idempotencyKey) {
        return act(id, "propose", idempotencyKey, newStart, Party.PROVIDER, (b, party) -> {
            requireFree(providers.find(b.orgId()).orElseThrow(), b, newStart);
            return b.proposeTime(newStart, Instant.now(clock));
        });
    }

    @Override
    @Transactional
    public Booking complete(UUID id, String idempotencyKey) {
        return act(id, "complete", idempotencyKey, null, Party.PROVIDER,
                (b, party) -> b.complete(Party.PROVIDER, Instant.now(clock)));
    }

    @Override
    @Transactional
    public Booking noShow(UUID id, String idempotencyKey) {
        return act(id, "no-show", idempotencyKey, null, Party.PROVIDER, (b, party) -> b.markNoShow(Instant.now(clock)));
    }

    /**
     * Runs one transition: idempotency claim, row lock, authorisation (customer or provider side), the domain
     * change, its money instructions against payments, persistence (time blocks re-checked by the exclusion
     * constraint) and the outbox event — all in the caller's transaction.
     */
    private Booking act(UUID id, String scope, String key, Object detail, Party requiredParty,
                        BiFunction<Booking, Party, Transition> change) {
        requireKey(key);
        Optional<UUID> prior = idempotency.claim(scope, key, Fingerprint.of(id + "|" + detail));
        if (prior.isPresent()) {
            Booking replay = require(prior.get());
            access.partyFor(replay, BookingsPermissions.READ, assignee(replay));
            return replay;
        }
        Booking b = bookings.lock(id).orElseThrow(() -> notFound(id));
        TeamMember assignee = assignee(b);
        Party party = access.partyFor(b, BookingsPermissions.MANAGE, assignee);
        if (requiredParty != null && party != requiredParty) {
            throw new ForbiddenActionException("bookings.wrong-party", requiredParty == Party.PROVIDER
                    ? "Only the provider can do this" : "Only the customer can do this");
        }
        if (assignee != null) {
            team.lock(assignee.id());
        }
        Instant now = Instant.now(clock);
        Transition t = money.apply(b, change.apply(b, party), now);
        Booking saved = bookings.update(b);
        events.publish(t, saved, providers.find(saved.orgId()).orElseThrow());
        idempotency.complete(scope, key, id);
        return saved;
    }

    private void requireFree(Provider p, Booking b, Instant start) {
        TeamMember m = team.find(b.memberId()).orElseThrow();
        if (!slots.isFree(p, m, start, b.service().durationMinutes(), b.id(), Instant.now(clock))) {
            throw new BookingsRuleViolation("bookings.slot-unavailable", "That time isn’t free any more.");
        }
    }

    private TeamMember assignee(Booking b) {
        return team.find(b.memberId()).orElse(null);
    }

    private Booking require(UUID id) {
        return bookings.find(id).orElseThrow(() -> notFound(id));
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BookingsRuleViolation("bookings.idempotency-key-required", "Writes need an Idempotency-Key");
        }
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("bookings.booking-not-found", "Booking " + id + " not found");
    }
}
