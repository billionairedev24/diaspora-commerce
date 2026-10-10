package com.afrotransact.retail.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.domain.GlAccount;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalSource;
import com.afrotransact.retail.port.Page;

/** GL journal entries produced by purchasing (read side; posting happens inside each workflow step). */
public interface LedgerService {

    Page<JournalEntry> journal(JournalSource source, String sourceRef, int page, int size);

    JournalEntry entry(UUID id);

    List<GlAccount> accounts();
}
