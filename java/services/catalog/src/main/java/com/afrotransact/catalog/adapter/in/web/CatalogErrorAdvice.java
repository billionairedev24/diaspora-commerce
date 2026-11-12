package com.afrotransact.catalog.adapter.in.web;

import java.util.Map;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.ChangeRequestInvalid;
import com.afrotransact.catalog.domain.GtinAlreadyUsed;
import com.afrotransact.catalog.domain.PageAlreadyExists;
import com.afrotransact.catalog.port.SellerOffers.OffersUnavailable;
import com.afrotransact.catalog.domain.tax.TaxNotReady;
import com.afrotransact.catalog.port.TaxResolver.TaxProfileNotFound;
import com.afrotransact.catalog.port.TaxResolver.TaxServiceUnavailable;
import com.afrotransact.catalog.domain.DepartmentNotAllowed;
import com.afrotransact.catalog.port.SellerScopes.ScopeUnavailable;
import com.afrotransact.catalog.domain.PreconditionRequiredException;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.ReviewStateConflict;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.PurchaseVerifier.PurchaseCheckUnavailable;
import com.afrotransact.platform.web.error.ProblemTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain rule violations to RFC 9457 problems with per-field errors for the product form and bulk upload. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class CatalogErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogErrorAdvice.class);

    @ExceptionHandler(CatalogRuleViolation.class)
    ProblemDetail ruleViolation(CatalogRuleViolation ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.rule-violation"));
        pd.setTitle("Catalog rule violated");
        pd.setProperty("code", "catalog.rule-violation");
        pd.setProperty("fieldErrors", ex.fieldErrors());
        return pd;
    }

    @ExceptionHandler(GtinAlreadyUsed.class)
    ProblemDetail gtinExists(GtinAlreadyUsed ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.gtin-exists"));
        pd.setTitle("This barcode already has a product page");
        pd.setProperty("code", "catalog.gtin-exists");
        pd.setProperty("existingAtin", ex.existingAtin());
        pd.setProperty("gtin14", ex.gtin14());
        return pd;
    }

    @ExceptionHandler(PageAlreadyExists.class)
    ProblemDetail pageExists(PageAlreadyExists ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.page-exists"));
        pd.setTitle("This product already has a page");
        pd.setProperty("code", "catalog.page-exists");
        pd.setProperty("existingAtin", ex.existingAtin());
        return pd;
    }

    @ExceptionHandler(ChangeRequestInvalid.class)
    ProblemDetail changeRequestInvalid(ChangeRequestInvalid ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.change-request-invalid"));
        pd.setTitle("The request does not fit its type");
        pd.setProperty("code", "catalog.change-request-invalid");
        pd.setProperty("errors", ex.errors());
        pd.setProperty("fieldErrors", ex.errors());
        return pd;
    }

    @ExceptionHandler(OffersUnavailable.class)
    ProblemDetail offersUnavailable(OffersUnavailable ex) {
        LOG.warn("offers unavailable: {}", ex.getMessage(), ex.getCause());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "We could not check your offers right now; try again in a minute");
        pd.setType(ProblemTypes.of("catalog.offers-unavailable"));
        pd.setTitle("Offers unavailable");
        pd.setProperty("code", "catalog.offers-unavailable");
        return pd;
    }

    @ExceptionHandler(TaxNotReady.class)
    ProblemDetail taxNotReady(TaxNotReady ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.tax-not-ready"));
        pd.setTitle("Tax details not complete");
        pd.setProperty("code", "catalog.tax-not-ready");
        pd.setProperty("fieldErrors", ex.fieldErrors());
        pd.setProperty("taxReadiness", ex.readiness());
        return pd;
    }

    @ExceptionHandler(TaxServiceUnavailable.class)
    ProblemDetail taxUnavailable(TaxServiceUnavailable ex) {
        LOG.warn("tax resolve unavailable: {}", ex.getMessage(), ex.getCause());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "We could not check the tax details of this product right now; try again in a minute. Your answers are not lost.");
        pd.setType(ProblemTypes.of("catalog.tax-unavailable"));
        pd.setTitle("Tax check unavailable");
        pd.setProperty("code", "catalog.tax-unavailable");
        return pd;
    }

    @ExceptionHandler(TaxProfileNotFound.class)
    ProblemDetail taxProfileMissing(TaxProfileNotFound ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "This category cannot be published into yet: it has no tax rules");
        pd.setType(ProblemTypes.of("catalog.tax-profile-missing"));
        pd.setTitle("Category not ready");
        pd.setProperty("code", "catalog.tax-profile-missing");
        pd.setProperty("fieldErrors", Map.of("category", "This category has no tax rules yet; please try later"));
        return pd;
    }

    @ExceptionHandler(DepartmentNotAllowed.class)
    ProblemDetail departmentNotAllowed(DepartmentNotAllowed ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        pd.setType(ProblemTypes.of(ex.code()));
        pd.setTitle("Department not available to your store");
        pd.setProperty("code", ex.code());
        return pd;
    }

    @ExceptionHandler(ScopeUnavailable.class)
    ProblemDetail scopeUnavailable(ScopeUnavailable ex) {
        // The real cause (a 401/403 from seller-service, a refused connection, a timeout) is for the operator, never the shopper.
        LOG.warn("catalog scope unavailable: {}", ex.getMessage(), ex.getCause());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "We could not check which departments your store sells in; try again in a minute");
        pd.setType(ProblemTypes.of("catalog.scope-unavailable"));
        pd.setTitle("Departments unavailable");
        pd.setProperty("code", "catalog.scope-unavailable");
        return pd;
    }

    @ExceptionHandler(SpreadsheetRejected.class)
    ProblemDetail uploadRejected(SpreadsheetRejected ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.upload-rejected"));
        pd.setTitle("The file cannot be imported");
        pd.setProperty("code", "catalog.upload-rejected");
        pd.setProperty("fieldErrors", Map.of("file", ex.getMessage()));
        return pd;
    }

    @ExceptionHandler(ImageServiceUnavailable.class)
    ProblemDetail imageServiceUnavailable(ImageServiceUnavailable ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The image library is unavailable; try again in a minute");
        pd.setType(ProblemTypes.of("catalog.media-unavailable"));
        pd.setTitle("Image library unavailable");
        pd.setProperty("code", "catalog.media-unavailable");
        return pd;
    }

    @ExceptionHandler(ReviewStateConflict.class)
    ProblemDetail reviewConflict(ReviewStateConflict ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(ProblemTypes.of(ex.code()));
        pd.setTitle("Review state conflict");
        pd.setProperty("code", ex.code());
        return pd;
    }

    @ExceptionHandler(PurchaseCheckUnavailable.class)
    ProblemDetail purchaseCheckUnavailable(PurchaseCheckUnavailable ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "We could not check your purchase right now; try again in a minute");
        pd.setType(ProblemTypes.of("catalog.purchase-check-unavailable"));
        pd.setTitle("Purchase check unavailable");
        pd.setProperty("code", "catalog.purchase-check-unavailable");
        return pd;
    }

    @ExceptionHandler(StaleVersionException.class)
    ProblemDetail staleVersion(StaleVersionException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.version-mismatch"));
        pd.setTitle("Changed by someone else");
        pd.setProperty("code", "catalog.version-mismatch");
        return pd;
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail preconditionRequired(PreconditionRequiredException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_REQUIRED, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.if-match-required"));
        pd.setTitle("Version required");
        pd.setProperty("code", "catalog.if-match-required");
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.invalid-transition"));
        pd.setProperty("code", "catalog.invalid-transition");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(ProblemTypes.of("catalog.invalid-input"));
        pd.setProperty("code", "catalog.invalid-input");
        return pd;
    }
}
