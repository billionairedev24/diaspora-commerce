package com.afrotransact.identity.application;

import java.util.List;

import com.afrotransact.identity.domain.ModuleAccess;
import com.afrotransact.identity.domain.ModuleDefinition;
import com.afrotransact.identity.domain.Portal;
import com.afrotransact.identity.domain.Role;

/** Module access matrix for one portal: rows are modules, columns are roles, cells are access levels. */
public record ModuleMatrix(Portal portal, List<ModuleDefinition> modules, List<Role> roles,
                           List<ModuleAccess> cells) {
}
