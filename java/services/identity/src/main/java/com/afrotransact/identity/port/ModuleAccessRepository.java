package com.afrotransact.identity.port;

import java.util.List;

import com.afrotransact.identity.domain.AccessLevel;
import com.afrotransact.identity.domain.ModuleAccess;
import com.afrotransact.identity.domain.ModuleDefinition;
import com.afrotransact.identity.domain.Portal;

/** Module access matrix (module × role → level). */
public interface ModuleAccessRepository {

    List<ModuleDefinition> modules(Portal portal);

    List<ModuleAccess> matrix(Portal portal);

    /** @return the previous level */
    AccessLevel set(Portal portal, String moduleKey, String roleKey, AccessLevel level);
}
