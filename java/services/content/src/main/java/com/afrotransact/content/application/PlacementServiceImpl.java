package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.placement.Placement;
import com.afrotransact.content.domain.placement.Placement.PlacementContent;
import com.afrotransact.content.domain.placement.PlacementStatus;
import com.afrotransact.content.domain.placement.Slot;
import com.afrotransact.content.domain.placement.SlotKey;
import com.afrotransact.content.port.PayloadSchemaValidator;
import com.afrotransact.content.port.PlacementRepository;
import com.afrotransact.content.port.SlotRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PlacementServiceImpl implements PlacementService {

    private static final String ENTITY = "placement";

    private final PlacementRepository placements;
    private final SlotRepository slots;
    private final PayloadSchemaValidator schemas;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final PlacementEvents events;
    private final PlacementCache cache;
    private final Clock clock;

    PlacementServiceImpl(PlacementRepository placements, SlotRepository slots, PayloadSchemaValidator schemas,
                         ContentAccess access, AuditTrail audit, PlacementEvents events, PlacementCache cache,
                         Clock clock) {
        this.placements = placements;
        this.slots = slots;
        this.schemas = schemas;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.cache = cache;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Slot> slots() {
        viewer();
        return slots.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Placement> list(PlacementStatus status, SlotKey slot) {
        viewer();
        return placements.find(status, slot, 500);
    }

    @Override
    @Transactional(readOnly = true)
    public Placement get(String id) {
        viewer();
        return load(id);
    }

    @Override
    @Transactional
    public Placement create(SlotKey slot, PlacementContent content) {
        Actor actor = access.require(ContentPermissions.PLACEMENTS_EDIT);
        Slot def = slots.find(slot).orElseThrow(() -> new NotFoundException("content.slot-not-found", "No slot " + slot.key()));
        checkPayload(def, content.payload());
        String id = "plc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Placement p = Placement.draft(id, slot, content, actor.subject(), now());
        placements.insert(p);
        audit.record(actor.subject(), "placement.created", ENTITY, id, Map.of("slot", slot.key(), "name", p.name()));
        return load(id);
    }

    @Override
    @Transactional
    public Placement update(String id, PlacementContent content, long expectedVersion) {
        Actor actor = access.require(ContentPermissions.PLACEMENTS_EDIT);
        Placement current = load(id);
        if (current.version() != expectedVersion) {
            throw stale(id);
        }
        Slot def = slots.find(current.slot()).orElseThrow();
        checkPayload(def, content.payload());
        save(current.edit(content, now()));
        audit.record(actor.subject(), "placement.updated", ENTITY, id, Map.of("name", content.name()));
        return load(id);
    }

    @Override
    @Transactional
    public Placement submit(String id) {
        Actor actor = access.require(ContentPermissions.PLACEMENTS_EDIT);
        Placement p = load(id).submit(actor.subject(), now());
        save(p);
        audit.record(actor.subject(), "placement.submitted", ENTITY, id, Map.of());
        return load(id);
    }

    @Override
    @Transactional
    public Placement approve(String id) {
        Actor actor = access.require(ContentPermissions.PLACEMENTS_APPROVE);
        Placement p = load(id).approve(actor.subject(), now());
        save(p);
        audit.record(actor.subject(), "placement.approved", ENTITY, id,
                Map.of("status", p.status().name(), "paid", p.sponsorship().paid()));
        events.published(p, p.status().name());
        cache.invalidate();
        return load(id);
    }

    @Override
    @Transactional
    public Placement reject(String id, String note) {
        Actor actor = access.require(ContentPermissions.PLACEMENTS_APPROVE);
        Placement p = load(id).reject(actor.subject(), note, now());
        save(p);
        audit.record(actor.subject(), "placement.rejected", ENTITY, id, Map.of("note", p.reviewNote()));
        return load(id);
    }

    @Override
    @Transactional
    public Placement end(String id) {
        Actor actor = access.requireAny(ContentPermissions.PLACEMENTS_EDIT, ContentPermissions.PLACEMENTS_APPROVE);
        Placement p = load(id).end(now());
        save(p);
        audit.record(actor.subject(), "placement.ended", ENTITY, id, Map.of());
        events.published(p, "ENDED");
        cache.invalidate();
        return load(id);
    }

    private void checkPayload(Slot slot, String payload) {
        List<String> problems = schemas.validate(slot.payloadSchema(), payload);
        if (!problems.isEmpty()) {
            Map<String, String> errors = new LinkedHashMap<>();
            for (String problem : problems) {
                int sep = problem.indexOf(": ");
                String path = sep > 0 ? problem.substring(0, sep) : "$";
                String field = "payload" + (path.startsWith("$") ? path.substring(1) : "." + path);
                errors.merge(field, sep > 0 ? problem.substring(sep + 2) : problem, (a, b) -> a + "; " + b);
            }
            throw new ContentRuleViolation("content.invalid-payload",
                    "The payload does not match the " + slot.name() + " schema", errors);
        }
    }

    private void save(Placement p) {
        if (!placements.update(p)) {
            throw stale(p.id());
        }
    }

    private Placement load(String id) {
        return placements.findById(id)
                .orElseThrow(() -> new NotFoundException("content.placement-not-found", "No placement " + id));
    }

    private void viewer() {
        access.requireAny(ContentPermissions.PLACEMENTS_VIEW, ContentPermissions.PLACEMENTS_EDIT,
                ContentPermissions.PLACEMENTS_APPROVE);
    }

    private static ConflictException stale(String id) {
        return new ConflictException("content.concurrent-change", "Placement " + id + " was changed by someone else; reload it");
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
