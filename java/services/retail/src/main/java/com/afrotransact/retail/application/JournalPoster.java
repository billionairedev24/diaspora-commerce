package com.afrotransact.retail.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalLine;
import com.afrotransact.retail.domain.JournalSource;
import com.afrotransact.retail.port.JournalRepository;
import com.afrotransact.retail.port.ReferenceIssuer;
import org.springframework.stereotype.Component;

/** Stores a balanced journal entry and publishes {@code retail.gl.journal.posted} in the caller's transaction. */
@Component
class JournalPoster {

    private final JournalRepository journal;
    private final ReferenceIssuer refs;
    private final DomainEventPublisher events;
    private final RetailSettings settings;
    private final Clock clock;

    JournalPoster(JournalRepository journal, ReferenceIssuer refs, DomainEventPublisher events, RetailSettings settings,
                  Clock clock) {
        this.journal = journal;
        this.refs = refs;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    JournalEntry post(JournalSource source, String sourceRef, String memo, List<JournalLine> lines, String by) {
        Instant now = Instant.now(clock);
        JournalEntry entry = journal.insert(new JournalEntry(UUID.randomUUID(), refs.nextJournalNumber(),
                LocalDate.ofInstant(now, ZoneOffset.UTC), source, sourceRef, memo, settings.baseCurrency(), lines, by,
                now));
        events.publish(RetailEvents.GL_JOURNAL_POSTED, RetailEvents.AGGREGATE, entry.id().toString(),
                RetailEventPayloads.JournalPosted.of(entry));
        return entry;
    }
}
