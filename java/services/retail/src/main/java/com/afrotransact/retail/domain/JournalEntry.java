package com.afrotransact.retail.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A balanced GL journal entry in base currency, produced by a purchasing step and published as an event. */
public record JournalEntry(UUID id, String number, LocalDate entryDate, JournalSource source, String sourceRef,
                           String memo, String currency, List<JournalLine> lines, String postedBy,
                           Instant postedAt) {

    public JournalEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(source, "source");
        currency = Currencies.require(currency);
        lines = lines == null ? List.of() : lines.stream().filter(l -> !l.zero()).toList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A journal entry needs at least one non-zero line");
        }
        long debits = lines.stream().mapToLong(JournalLine::debitMinor).sum();
        long credits = lines.stream().mapToLong(JournalLine::creditMinor).sum();
        if (debits != credits) {
            throw new IllegalArgumentException("Unbalanced journal " + number + ": debits " + debits
                    + " ≠ credits " + credits);
        }
    }

    public long amountMinor() {
        return lines.stream().mapToLong(JournalLine::debitMinor).sum();
    }
}
