package com.afrotransact.retail.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.application.LedgerService;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "GL journal", description = "Journal entries produced by departures, receipts, invoice matches and COGS "
        + "(also published as retail.gl.journal.posted)")
@RestController
@RequestMapping("/v1/gl")
class LedgerController {

    private final LedgerService ledger;

    LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @Schema(name = "GlAccount")
    record AccountResponse(String code, String name) {
    }

    @Schema(name = "JournalLine")
    record JournalLineResponse(String account, String accountName, long debitMinor, long creditMinor) {
    }

    @Schema(name = "JournalEntry")
    record JournalEntryResponse(UUID id, String number, LocalDate entryDate, String source, String sourceRef,
                                String memo, String currency, long amountMinor, List<JournalLineResponse> lines,
                                String postedBy, Instant postedAt) {

        static JournalEntryResponse of(JournalEntry e) {
            return new JournalEntryResponse(e.id(), e.number(), e.entryDate(), e.source().name(), e.sourceRef(),
                    e.memo(), e.currency(), e.amountMinor(), e.lines().stream().map(l -> new JournalLineResponse(
                    l.account().code(), l.account().label(), l.debitMinor(), l.creditMinor())).toList(),
                    e.postedBy(), e.postedAt());
        }
    }

    @Operation(operationId = "listGlAccounts", summary = "Chart of accounts used by retail postings (retail:read)")
    @GetMapping("/accounts")
    List<AccountResponse> accounts() {
        return ledger.accounts().stream().map(a -> new AccountResponse(a.code(), a.label())).toList();
    }

    @Operation(operationId = "listJournalEntries", summary = "Journal entries, newest first (retail:finance)")
    @GetMapping("/journal")
    PageResponse<JournalEntryResponse> journal(@RequestParam(required = false) JournalSource source,
                                               @RequestParam(required = false) String sourceRef,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(ledger.journal(source, sourceRef, page, size), JournalEntryResponse::of);
    }

    @Operation(operationId = "getJournalEntry", summary = "One journal entry (retail:finance)")
    @GetMapping("/journal/{id}")
    JournalEntryResponse entry(@PathVariable UUID id) {
        return JournalEntryResponse.of(ledger.entry(id));
    }
}
