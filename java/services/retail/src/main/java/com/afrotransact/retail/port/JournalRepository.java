package com.afrotransact.retail.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalSource;

/** Append-only store of the GL journal entries retail produced. */
public interface JournalRepository {

    JournalEntry insert(JournalEntry entry);

    Optional<JournalEntry> findById(UUID id);

    List<JournalEntry> findBySourceRef(String sourceRef);

    Page<JournalEntry> page(JournalSource source, String sourceRef, int page, int size);
}
