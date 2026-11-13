package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.home.CommunityEntry;
import com.afrotransact.content.port.CommunityRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CommunityRegistryServiceImpl implements CommunityRegistryService {

    private final CommunityRepository communities;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final Clock clock;

    CommunityRegistryServiceImpl(CommunityRepository communities, ContentAccess access, AuditTrail audit, Clock clock) {
        this.communities = communities;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommunityEntry> list() {
        return communities.findAll();
    }

    @Override
    @Transactional
    public CommunityEntry put(String key, CommunityEntry entry, String reason) {
        Actor actor = access.require(ContentPermissions.HOME_EDIT);
        HomeSettingsServiceImpl.requireReason(reason);
        CommunityEntry next = new CommunityEntry(key, entry.labels(), entry.aliases(), entry.imageUrl(), Instant.now(clock));
        next.validate();
        requireNoAliasClash(next);
        CommunityEntry before = communities.find(key).orElse(null);
        communities.upsert(next, actor.subject());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason.trim());
        detail.put("before", before == null ? null : snapshot(before));
        detail.put("after", snapshot(next));
        audit.record(actor.subject(), before == null ? "community.created" : "community.updated", "community", key, detail);
        return next;
    }

    @Override
    @Transactional
    public void delete(String key, String reason) {
        Actor actor = access.require(ContentPermissions.HOME_EDIT);
        HomeSettingsServiceImpl.requireReason(reason);
        CommunityEntry before = communities.find(key)
                .orElseThrow(() -> new NotFoundException("content.community-not-found", "No such community"));
        communities.delete(key);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason.trim());
        detail.put("before", snapshot(before));
        audit.record(actor.subject(), "community.deleted", "community", key, detail);
    }

    /** An alias or label may not belong to another community: two entries must never claim the same spelling. */
    private void requireNoAliasClash(CommunityEntry next) {
        Map<String, String> bad = new LinkedHashMap<>();
        for (CommunityEntry other : communities.findAll()) {
            if (other.key().equals(next.key())) {
                continue;
            }
            for (String a : next.aliases()) {
                String n = CommunityEntry.normalise(a);
                if (n.equals(other.key()) || other.aliases().stream().anyMatch(x -> CommunityEntry.normalise(x).equals(n))) {
                    bad.put("aliases", "\"" + a + "\" already belongs to " + other.labels().getOrDefault("en", other.key()));
                }
            }
            if (next.aliases().stream().anyMatch(a -> CommunityEntry.normalise(a).equals(other.key()))) {
                bad.put("aliases", "An alias cannot be another community's key");
            }
        }
        if (next.aliases().stream().anyMatch(a -> CommunityEntry.normalise(a).isEmpty())) {
            bad.put("aliases", "An alias needs letters or digits");
        }
        if (!bad.isEmpty()) {
            throw new ContentRuleViolation("content.community-alias-clash", "An alias is already used", bad);
        }
    }

    private static Map<String, Object> snapshot(CommunityEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labels", e.labels());
        m.put("aliases", e.aliases());
        m.put("imageUrl", e.imageUrl());
        return m;
    }
}
