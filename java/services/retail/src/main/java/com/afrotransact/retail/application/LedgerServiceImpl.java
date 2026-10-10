package com.afrotransact.retail.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.retail.domain.GlAccount;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalSource;
import com.afrotransact.retail.port.JournalRepository;
import com.afrotransact.retail.port.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LedgerServiceImpl implements LedgerService {

    private final JournalRepository journal;
    private final PermissionChecker permissions;

    LedgerServiceImpl(JournalRepository journal, PermissionChecker permissions) {
        this.journal = journal;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<JournalEntry> journal(JournalSource source, String sourceRef, int page, int size) {
        permissions.require(RetailPermissions.FINANCE);
        return journal.page(source, sourceRef, Math.max(0, page), Math.clamp(size, 1, 200));
    }

    @Override
    @Transactional(readOnly = true)
    public JournalEntry entry(UUID id) {
        permissions.require(RetailPermissions.FINANCE);
        return journal.findById(id).orElseThrow(() -> new NotFoundException("retail.journal-not-found",
                "Journal entry not found"));
    }

    @Override
    public List<GlAccount> accounts() {
        permissions.require(RetailPermissions.READ);
        return List.of(GlAccount.values());
    }
}
