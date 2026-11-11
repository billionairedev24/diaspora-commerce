package com.afrotransact.catalog.application;

import java.util.List;

import com.afrotransact.catalog.port.BakeryClaimRepository;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BakeryClaimServiceImpl implements BakeryClaimService {

    private final BakeryClaimRepository claims;
    private final PermissionChecker permissions;

    BakeryClaimServiceImpl(BakeryClaimRepository claims, PermissionChecker permissions) {
        this.claims = claims;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> organisations() {
        permissions.require(SERVICE_READ);
        return claims.organisations();
    }
}
