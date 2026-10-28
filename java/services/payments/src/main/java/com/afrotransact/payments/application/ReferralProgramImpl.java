package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.payments.domain.Referral;
import com.afrotransact.payments.domain.ReferralCodes;
import com.afrotransact.payments.domain.ReferralFlag;
import com.afrotransact.payments.domain.ReferralReward;
import com.afrotransact.payments.domain.ReferralRewardGrant;
import com.afrotransact.payments.domain.ReferralSettings;
import com.afrotransact.payments.domain.ReferralSide;
import com.afrotransact.payments.domain.ReferralStatus;
import com.afrotransact.payments.domain.SignalKind;
import com.afrotransact.payments.port.ReferralRepository;
import com.afrotransact.payments.port.ReferralSettingsRepository;
import com.afrotransact.payments.port.ReferralTrackingRepository;
import com.afrotransact.payments.port.SignalHasher;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class ReferralProgramImpl implements ReferralProgram {

    private static final Logger LOG = LoggerFactory.getLogger(ReferralProgramImpl.class);
    private static final Pattern HEX_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String STAFF_INTENT = "STAFF";

    private final ReferralRepository referrals;
    private final ReferralTrackingRepository tracking;
    private final ReferralSettingsRepository settings;
    private final ReferralRewarder rewarder;
    private final SignalHasher hasher;
    private final MoneyTransactions tx;
    private final DomainEventPublisher events;
    private final PaymentsSettings payments;
    private final Clock clock;

    ReferralProgramImpl(ReferralRepository referrals, ReferralTrackingRepository tracking,
                        ReferralSettingsRepository settings, ReferralRewarder rewarder, SignalHasher hasher,
                        MoneyTransactions tx, DomainEventPublisher events, PaymentsSettings payments, Clock clock) {
        this.referrals = referrals;
        this.tracking = tracking;
        this.settings = settings;
        this.rewarder = rewarder;
        this.hasher = hasher;
        this.tx = tx;
        this.events = events;
        this.payments = payments;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ sign-up

    @Override
    public void signedUp(SignUp s) {
        if (s.userId() == null || STAFF_INTENT.equalsIgnoreCase(s.intent())) {
            return;
        }
        Instant now = Instant.now(clock);
        tx.serializable(() -> {
            addSignal(s.userId(), SignalKind.DEVICE, s.deviceHash(), now);
            addSignal(s.userId(), SignalKind.NETWORK, s.networkHash(), now);
            attach(s, now);
            return null;
        });
    }

    private void attach(SignUp s, Instant now) {
        String code = ReferralCodes.normalise(s.referralCode());
        if (code == null) {
            if (s.referralCode() != null && !s.referralCode().isBlank()) {
                LOG.info("Ignoring a malformed referral code at sign-up of {}", s.userId());
            }
            return;
        }
        ReferralSettings program = settings.get();
        if (!program.enabled()) {
            return;
        }
        Optional<String> owner = referrals.customerOfCode(code);
        if (owner.isEmpty() || owner.get().equals(s.userId())) {
            LOG.info("Ignoring referral code {} at sign-up of {}: {}", code, s.userId(),
                    owner.isEmpty() ? "unknown code" : "self-referral");
            return;
        }
        List<ReferralFlag> flags = new ArrayList<>();
        addIf(flags, ReferralFlag.SAME_DEVICE, tracking.sharedSignal(owner.get(), s.userId(), SignalKind.DEVICE));
        addIf(flags, ReferralFlag.SAME_NETWORK, tracking.sharedSignal(owner.get(), s.userId(), SignalKind.NETWORK));
        Instant signedUpAt = s.registeredAt() == null ? now : s.registeredAt();
        referrals.insert(Referral.signedUp(UUID.randomUUID(), owner.get(), s.userId(), code, friendName(s), flags,
                signedUpAt, program.validityDays(), now));
    }

    // ------------------------------------------------------------------ orders

    @Override
    public void orderPlaced(OrderPlaced o) {
        if (o.customerId() == null || o.orderId() == null) {
            return;
        }
        Instant now = Instant.now(clock);
        tx.serializable(() -> {
            String address = normalisedAddress(o);
            if (address != null) {
                tracking.addSignal(o.customerId(), SignalKind.ADDRESS, hasher.hash(address), now);
            }
            Optional<Referral> referral = referrals.findByReferred(o.customerId());
            if (referral.isPresent() && referral.get().status() == ReferralStatus.PENDING) {
                tracking.trackOrder(referral.get().id(), o.orderId(), o.placedAt() == null ? now : o.placedAt(),
                        o.totalMinor(), o.shipments().stream()
                                .map(s -> new ReferralTrackingRepository.Shipment(s.shipmentId(), s.totalMinor()))
                                .toList());
            }
            return null;
        });
    }

    @Override
    public void shipmentEnded(ShipmentEnded e) {
        if (e.shipmentId() == null) {
            return;
        }
        Instant now = Instant.now(clock);
        tx.serializable(() -> {
            Optional<String> order = tracking.orderOfShipment(e.shipmentId());
            if (order.isEmpty()) {
                return null;
            }
            tracking.endShipment(e.shipmentId(), e.delivered(), e.at() == null ? now : e.at());
            tracking.orderState(order.get()).ifPresent(state -> qualify(order.get(), state, now));
            return null;
        });
    }

    /** The order's shipments have all ended: if it was delivered and big enough, the referral qualifies. */
    private void qualify(String orderId, ReferralTrackingRepository.OrderState state, Instant now) {
        if (state.open() > 0 || state.delivered() == 0) {
            return;
        }
        Optional<Referral> locked = referrals.lock(state.referralId());
        if (locked.isEmpty() || locked.get().status() != ReferralStatus.PENDING) {
            return;
        }
        Referral r = locked.get();
        ReferralSettings program = settings.get();
        if (!program.qualifies(state.deliveredMinor()) || state.deliveredAt().isAfter(r.expiresAt())) {
            return;
        }
        Referral qualified = referrals.update(r.qualified(orderId, state.deliveredMinor(), state.deliveredAt(),
                program.returnWindowDays(), abuse(r), now));
        tracking.markQualified(orderId);
        String currency = payments.currency();
        List<ReferralRewardGrant> rewards = new ArrayList<>();
        for (ReferralSide side : ReferralSide.values()) {
            ReferralReward reward = program.rewardOf(side);
            if (!reward.isEmpty()) {
                rewards.add(ReferralRewardGrant.pending(qualified.id(), side,
                        side == ReferralSide.REFERRER ? qualified.referrerId() : qualified.referredId(), reward,
                        currency));
            }
        }
        referrals.saveRewards(rewards);
        if (qualified.status() == ReferralStatus.IN_REVIEW) {
            events.publish(PaymentsEvents.REFERRAL_FLAGGED, PaymentsEvents.AGGREGATE_PAYMENT,
                    qualified.id().toString(), new PaymentsEventPayloads.ReferralFlaggedPayload(
                            qualified.id().toString(), qualified.referrerId(), qualified.referredId(),
                            qualified.flags().stream().map(Enum::name).toList()));
        } else if (qualified.due(now)) {
            rewarder.settle(qualified, now);
        }
    }

    /** What the referrer and the friend share: the sign-up device and network, a delivery address, a card. */
    private List<ReferralFlag> abuse(Referral r) {
        List<ReferralFlag> found = new ArrayList<>();
        addIf(found, ReferralFlag.SAME_DEVICE, tracking.sharedSignal(r.referrerId(), r.referredId(),
                SignalKind.DEVICE));
        addIf(found, ReferralFlag.SAME_NETWORK, tracking.sharedSignal(r.referrerId(), r.referredId(),
                SignalKind.NETWORK));
        addIf(found, ReferralFlag.SAME_ADDRESS, tracking.sharedSignal(r.referrerId(), r.referredId(),
                SignalKind.ADDRESS));
        addIf(found, ReferralFlag.SAME_PAYMENT_METHOD, tracking.sharedPaymentMethod(r.referrerId(), r.referredId()));
        return found;
    }

    // ------------------------------------------------------------------ jobs

    @Override
    public int grantDue(int batch) {
        Instant now = Instant.now(clock);
        int settled = 0;
        for (UUID id : referrals.due(now, batch)) {
            settled += tx.serializable(() -> referrals.lock(id).filter(r -> r.due(now))
                    .map(r -> {
                        rewarder.settle(r, now);
                        return 1;
                    }).orElse(0));
        }
        return settled;
    }

    @Override
    public int expireStale(int batch) {
        Instant now = Instant.now(clock);
        int expired = 0;
        for (UUID id : referrals.expiring(now, batch)) {
            expired += tx.serializable(() -> referrals.lock(id)
                    .filter(r -> r.status() == ReferralStatus.PENDING && !r.expiresAt().isAfter(now))
                    .map(r -> {
                        referrals.update(r.expired(now));
                        return 1;
                    }).orElse(0));
        }
        return expired;
    }

    // ------------------------------------------------------------------ helpers

    private void addSignal(String customerId, SignalKind kind, String hash, Instant now) {
        if (hash != null && HEX_SHA256.matcher(hash).matches()) {
            tracking.addSignal(customerId, kind, hash, now);
        }
    }

    private static void addIf(List<ReferralFlag> flags, ReferralFlag flag, boolean condition) {
        if (condition) {
            flags.add(flag);
        }
    }

    /** First name and last initial, the most the referrer learns about a friend. */
    static String friendName(SignUp s) {
        String first = s.firstName() == null ? "" : s.firstName().trim();
        String last = s.lastName() == null ? "" : s.lastName().trim();
        String name = last.isEmpty() ? first : first + " " + last.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
        return name.isBlank() ? "A friend" : name;
    }

    /** Street, unit, postal code and country in one comparable string; null without a street and postal code. */
    static String normalisedAddress(OrderPlaced o) {
        String line1 = alphanumeric(o.shipToLine1());
        String postal = alphanumeric(o.shipToPostalCode());
        if (line1.isEmpty() || postal.isEmpty()) {
            return null;
        }
        return line1 + "|" + alphanumeric(o.shipToLine2()) + "|" + postal + "|" + alphanumeric(o.shipToCountry());
    }

    private static String alphanumeric(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
