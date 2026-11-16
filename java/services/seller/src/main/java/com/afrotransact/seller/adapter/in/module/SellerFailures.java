package com.afrotransact.seller.adapter.in.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.module.ModuleApiException;
import com.afrotransact.seller.domain.ApplicationAlreadyDecided;
import com.afrotransact.seller.domain.SellerForbidden;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.port.DepartmentDirectory;
import com.afrotransact.seller.port.GatewayException;
import com.afrotransact.seller.port.GeoUnavailableException;

/** The mapping {@code SellerErrorAdvice} applies over HTTP, as the status an in-process call for a person fails with. */
final class SellerFailures {

    private SellerFailures() {
    }

    static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof SellerRuleViolation rule) {
            int status = "seller.invalid-signature".equals(rule.code()) ? 400 : 422;
            List<String> lines = rule.fieldErrors() == null ? List.of()
                    : rule.fieldErrors().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList();
            return Optional.of(new ModuleApiException(status, rule.code(), ex.getMessage(), lines, ex));
        }
        if (ex instanceof SellerStateConflict state) {
            return of(409, state.code(), ex);
        }
        if (ex instanceof ApplicationAlreadyDecided decided) {
            return of(409, decided.code(), ex);
        }
        if (ex instanceof SellerForbidden forbidden) {
            return of(403, forbidden.code(), ex);
        }
        if (ex instanceof DepartmentDirectory.DirectoryUnavailable) {
            return of(503, "seller.departments-unavailable", ex);
        }
        if (ex instanceof GatewayException gateway) {
            return of(gateway.rejected() ? 422 : 502, gateway.code(), ex);
        }
        if (ex instanceof GeoUnavailableException) {
            return of(503, "seller.geo-unavailable", ex);
        }
        if (ex instanceof IllegalArgumentException) {
            return of(400, "seller.invalid-input", ex);
        }
        return Optional.empty();
    }

    private static Optional<ModuleApiException> of(int status, String code, RuntimeException ex) {
        return Optional.of(new ModuleApiException(status, code, ex.getMessage(), List.of(), ex));
    }
}
