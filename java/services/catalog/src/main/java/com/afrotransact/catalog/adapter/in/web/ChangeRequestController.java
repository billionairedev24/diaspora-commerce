package com.afrotransact.catalog.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.security.StaffOrServiceOnly;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ChangeRequestInput;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ChangeRequestPageResponse;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ChangeRequestResponse;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.DecisionInput;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ExemptionDecisionInput;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ExemptionPageResponse;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ExemptionResponse;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.QueuePageResponse;
import com.afrotransact.catalog.application.ChangeRequestService;
import com.afrotransact.catalog.application.GtinExemptionService;
import com.afrotransact.catalog.application.ReviewQueueService;
import com.afrotransact.catalog.domain.ChangeRequestStatus;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.ExemptionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Change requests", description = "Suggest an edit, report a wrong match or a duplicate page (S385). Staff "
        + "decide; the page is never changed by the request itself.")
@RestController
class ChangeRequestController {

    private final ChangeRequestService changeRequests;
    private final GtinExemptionService exemptions;
    private final ReviewQueueService queue;

    ChangeRequestController(ChangeRequestService changeRequests, GtinExemptionService exemptions,
                            ReviewQueueService queue) {
        this.changeRequests = changeRequests;
        this.exemptions = exemptions;
        this.queue = queue;
    }

    @Operation(operationId = "listPageChangeRequests",
            summary = "The change requests on a product page (the page owner sees all, other sellers their own, staff all)")
    @GetMapping("/v1/products/{atin}/change-requests")
    List<ChangeRequestResponse> forPage(@PathVariable String atin) {
        return changeRequests.forPage(atin).stream().map(ChangeRequestResponse::of).toList();
    }

    @Operation(operationId = "fileChangeRequest",
            summary = "Suggest an edit, report a wrong match or a duplicate page (S385)",
            description = "The page is never changed by this call. 403 catalog.not-your-offer without an offer on the "
                    + "page; 409 catalog.change-request-exists for a second open request of the same kind; 422 "
                    + "catalog.change-request-invalid with errors.")
    @PostMapping("/v1/products/{atin}/change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    ChangeRequestResponse file(@PathVariable String atin, @Valid @RequestBody ChangeRequestInput body) {
        return ChangeRequestResponse.of(changeRequests.file(atin, body.toCommand()));
    }

    @Operation(operationId = "listMyChangeRequests",
            summary = "The organisation's own change requests, with the decision and its reason")
    @GetMapping("/v1/change-requests/mine")
    ChangeRequestPageResponse mine(@RequestParam(required = false) ChangeRequestStatus status,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ChangeRequestPageResponse.of(changeRequests.mine(status, page, size));
    }

    @Operation(operationId = "getChangeRequest", summary = "One change request (the requester, the page owner or staff)")
    @GetMapping("/v1/change-requests/{id}")
    ChangeRequestResponse get(@PathVariable UUID id) {
        return ChangeRequestResponse.of(changeRequests.get(id));
    }

    @Operation(operationId = "withdrawChangeRequest", summary = "The requester withdraws a request that is still waiting")
    @PostMapping("/v1/change-requests/{id}/withdraw")
    ChangeRequestResponse withdraw(@PathVariable UUID id) {
        return ChangeRequestResponse.of(changeRequests.withdraw(id));
    }

    @StaffOrServiceOnly
    @Tag(name = "Review queue")
    @Operation(operationId = "listChangeRequests", summary = "Staff queue of change requests (catalog:moderate)",
            description = "Oldest first by default (the SLA clock), server-side paging.")
    @GetMapping("/v1/change-requests")
    ChangeRequestPageResponse list(@RequestParam(required = false) ChangeRequestStatus status,
                                   @RequestParam(required = false) ChangeRequestType type,
                                   @RequestParam(defaultValue = "false") boolean contentOnly,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
                                   @RequestParam(defaultValue = "OLDEST") String sort) {
        return ChangeRequestPageResponse.of(changeRequests.queue(status, type, contentOnly,
                !"NEWEST".equalsIgnoreCase(sort), page, size));
    }

    @StaffOrServiceOnly
    @Tag(name = "Review queue")
    @Operation(operationId = "decideChangeRequest",
            summary = "Approve (apply) or reject a change request with a reason (catalog:moderate)",
            description = "The reason is required (3 or more characters). Approving applies the change, increases the "
                    + "page version and never changes the product status. Deciding twice is 409.")
    @PostMapping("/v1/change-requests/{id}/decision")
    ChangeRequestResponse decide(@PathVariable UUID id, @Valid @RequestBody DecisionInput body) {
        return ChangeRequestResponse.of(changeRequests.decide(id, body.toCommand()));
    }

    @Tag(name = "GTIN exemptions")
    @Operation(operationId = "listMyGtinExemptions",
            summary = "The organisation's requests to sell an item without a barcode")
    @GetMapping("/v1/gtin-exemptions/mine")
    List<ExemptionResponse> myExemptions() {
        return exemptions.mine().stream().map(ExemptionResponse::of).toList();
    }

    @StaffOrServiceOnly
    @Tag(name = "Review queue")
    @Operation(operationId = "listGtinExemptions", summary = "Staff queue of no-barcode exemption requests (catalog:moderate)",
            description = "Oldest first, with duplicate hints.")
    @GetMapping("/v1/gtin-exemptions")
    ExemptionPageResponse listExemptions(@RequestParam(required = false) ExemptionStatus status,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "25") int size) {
        return ExemptionPageResponse.of(exemptions.queue(status, page, size));
    }

    @StaffOrServiceOnly
    @Tag(name = "Review queue")
    @Operation(operationId = "decideGtinExemption",
            summary = "Approve or reject a no-barcode exemption with a reason (catalog:moderate)",
            description = "Approving moves the page to IN_REVIEW (never LIVE); rejecting rejects the page with the reason.")
    @PostMapping("/v1/gtin-exemptions/{id}/decision")
    ExemptionResponse decideExemption(@PathVariable UUID id, @Valid @RequestBody ExemptionDecisionInput body) {
        return ExemptionResponse.of(exemptions.decide(id, body.approve(), body.reason()));
    }

    @StaffOrServiceOnly
    @Tag(name = "Review queue")
    @Operation(operationId = "reviewQueue",
            summary = "One queue for staff - change requests and no-barcode exemptions, with age and SLA (catalog:moderate)")
    @GetMapping("/v1/review-queue")
    QueuePageResponse reviewQueue(@RequestParam(required = false) ReviewQueueService.Kind kind,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return QueuePageResponse.of(queue.queue(kind, page, size));
    }
}
