package com.afrotransact.identity.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.Passkey;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.PasskeyStore;
import com.afrotransact.identity.port.UserAccountRepository;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PasskeyServiceImpl implements PasskeyService {

    private final CurrentUser current;
    private final UserAccountRepository users;
    private final PasskeyStore passkeys;
    private final SignInMethodsReader methods;
    private final AccessChangeLog changeLog;

    PasskeyServiceImpl(CurrentUser current, UserAccountRepository users, PasskeyStore passkeys,
                       SignInMethodsReader methods, AccessChangeLog changeLog) {
        this.current = current;
        this.users = users;
        this.passkeys = passkeys;
        this.methods = methods;
        this.changeLog = changeLog;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Passkey> list() {
        return passkeys.list(current.id());
    }

    @Override
    @Transactional
    public void delete(String credentialId) {
        UUID id = current.id();
        UserAccount u = users.findById(id).orElseThrow(() -> new NotFoundException("identity.account-not-found",
                "Account not found"));
        boolean owned = passkeys.list(id).stream().anyMatch(p -> p.credentialId().equals(credentialId));
        if (!owned) {
            throw new NotFoundException("identity.passkey-not-found", "Passkey not found");
        }
        if (!methods.read(u).canRemovePasskey()) {
            throw new ConflictException("identity.last-sign-in-method",
                    "Add a password or another passkey before removing this one");
        }
        passkeys.delete(id, credentialId);
        changeLog.append(id.toString(), "passkey.removed", id, null, credentialId, null);
    }
}
