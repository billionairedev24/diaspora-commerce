package com.afrotransact.deliveryruns.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.CreativeRef;
import com.afrotransact.deliveryruns.domain.CreativeSlot;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.LocalAdvertorial;
import com.afrotransact.deliveryruns.domain.PreorderStatus;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Resolution;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.AdvertorialRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdvertorialServingImpl implements AdvertorialServing {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US);
    private static final int MAX_SERVED = 10;

    /** A window or run that serves the visitor's place and takes orders now. */
    private record Candidate(AdvertorialSource kind, UUID id, String code, String place, Instant closes,
                             String delivery, String fee, String image, Function<String, String> link) {
    }

    private final AdvertorialRepository advertorials;
    private final PublicRunService publicRuns;
    private final PreorderPublicService preorders;
    private final RunLinks links;
    private final Clock clock;

    AdvertorialServingImpl(AdvertorialRepository advertorials, PublicRunService publicRuns,
                           PreorderPublicService preorders, RunLinks links, Clock clock) {
        this.advertorials = advertorials;
        this.publicRuns = publicRuns;
        this.preorders = preorders;
        this.links = links;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServedAdvertorial> serve(AdvertorialQuery q) {
        Instant now = Instant.now(clock);
        int limit = Math.min(Math.max(q.limit(), 1), MAX_SERVED);
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        if (hasText(q.zip()) || q.lat() != null && q.lng() != null) {
            for (AvailableRun a : publicRuns.available(q.zip(), q.lat(), q.lng())) {
                Advertorial data = publicRuns.advertorial(a.run().id());
                candidates.put(a.run().id(), new Candidate(AdvertorialSource.RUN, a.run().id(), null, data.town(),
                        data.cutoffAt(), data.deliveryDay(), data.feeDisplay(), null,
                        creative -> links.runAdvertorialUrl(a.location(), a.run(), creative)));
            }
        }
        if (hasText(q.country()) && hasText(q.region())) {
            windowCandidate(q).ifPresent(c -> candidates.put(c.id(), c));
        }
        List<ServedAdvertorial> served = new ArrayList<>();
        Map<UUID, List<LocalAdvertorial>> bySource = advertorials.findLive(candidates.keySet()).stream()
                .filter(a -> a.servesAt(now)).collect(Collectors.groupingBy(LocalAdvertorial::sourceId));
        Map<UUID, Integer> priority = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<LocalAdvertorial>> e : bySource.entrySet()) {
            Candidate c = candidates.get(e.getKey());
            LocalAdvertorial chosen = pick(e.getValue(), q.visitor(), e.getKey());
            ServedAdvertorial s = served(chosen, c);
            served.add(s);
            priority.put(s.id(), chosen.priority());
        }
        served.sort(Comparator.<ServedAdvertorial>comparingInt(s -> -priority.get(s.id()))
                .thenComparing(ServedAdvertorial::countdownTarget));
        return served.stream().limit(limit).toList();
    }

    @Override
    @Transactional
    public void record(UUID advertorialId, Event event) {
        advertorials.findById(advertorialId).orElseThrow(() -> new NotFoundException(
                "delivery-runs.advertorial-not-found", "Advertorial not found"));
        advertorials.count(advertorialId, Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate(),
                event == Event.VIEW ? 1 : 0, event == Event.CLICK ? 1 : 0);
    }

    private Optional<Candidate> windowCandidate(AdvertorialQuery q) {
        Resolution r;
        try {
            r = preorders.resolve(q.country(), q.region(), q.city());
        } catch (RuleViolation e) {
            return Optional.empty();
        }
        PreorderWindow w = r.window();
        if (!r.on() || w == null || r.stage() != PreorderStatus.OPEN) {
            return Optional.empty();
        }
        FeeSnapshot door = r.doorFee();
        String fee = door == null ? null : RunLinks.money(door.amountMinor(), door.currency());
        CreativeRef hero = w.creatives().get(CreativeSlot.HERO);
        String place = w.details().targets().isEmpty() ? "your area" : w.details().targets().get(0).label();
        return Optional.of(new Candidate(AdvertorialSource.WINDOW, w.id(), w.code(), place, w.details().closesAt(),
                DAY.format(w.details().deliversOn()), fee, hero == null ? null : hero.url(),
                creative -> links.windowAdvertorialUrl(w.code(), creative)));
    }

    private ServedAdvertorial served(LocalAdvertorial a, Candidate c) {
        String url = c.link().apply(a.creative());
        Instant expires = a.endsAt() != null && a.endsAt().isBefore(c.closes()) ? a.endsAt() : c.closes();
        return new ServedAdvertorial(a.id(), a.source(), a.sourceId(), a.variant(), c.code(), c.place(),
                a.copy().headline(), a.copy().body(), a.copy().ctaLabel(), url, c.image(), a.featured(), c.closes(),
                expires,
                c.delivery(), c.fee());
    }

    /** One variant per visitor and source: the same visitor always gets the same one; no visitor id, a random one. */
    static LocalAdvertorial pick(List<LocalAdvertorial> variants, String visitor, UUID sourceId) {
        List<LocalAdvertorial> sorted = variants.stream().sorted(Comparator.comparing(LocalAdvertorial::variant))
                .toList();
        int total = sorted.stream().mapToInt(LocalAdvertorial::weight).sum();
        int bucket = hasText(visitor)
                ? Math.floorMod((visitor.trim() + "|" + sourceId).hashCode(), total)
                : ThreadLocalRandom.current().nextInt(total);
        int upTo = 0;
        for (LocalAdvertorial v : sorted) {
            upTo += v.weight();
            if (bucket < upTo) {
                return v;
            }
        }
        return sorted.get(sorted.size() - 1);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
