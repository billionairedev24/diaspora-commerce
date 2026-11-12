package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.ChangeRequestStatus;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.ProductChangeRequest;

/**
 * Suggest an edit, report a wrong match, report a duplicate page (S385) and the staff review of them (S386). Filing never
 * changes the page; an approval applies the change without touching the page's status.
 */
public interface ChangeRequestService {

    /**
     * A seller files a request on a page (page ATIN or one of its variants; catalog:write). Types that suggest a change
     * need an open offer on the page (403 {@code catalog.not-your-offer}); one open request per page, type, field and
     * organisation (409 {@code catalog.change-request-exists}).
     */
    ChangeRequestView file(String atin, FileCommand command);

    /** The requests on a page: staff and the page owner see all, other sellers their own. */
    List<ChangeRequestView> forPage(String atin);

    /** The organisation's own requests, newest first (catalog:write). */
    PageView<ChangeRequestView> mine(ChangeRequestStatus status, int page, int size);

    /** One request: its organisation, the page owner or staff. */
    ChangeRequestView get(UUID id);

    /** The requester withdraws a request that is still waiting. */
    ChangeRequestView withdraw(UUID id);

    /** The staff queue (catalog:moderate), oldest first unless asked otherwise. */
    PageView<ChangeRequestView> queue(ChangeRequestStatus status, ChangeRequestType type, boolean oldestFirst, int page,
                                      int size);

    /**
     * Staff approve (apply) or reject a request with a reason of 3 or more characters (catalog:moderate). Publishes
     * {@code catalog.change_request.decided}.
     */
    ChangeRequestView decide(UUID id, DecisionCommand command);

    /**
     * @param field the image slot (MAIN or OTHER) for image types; other types derive it from the payload
     */
    record FileCommand(ChangeRequestType type, String field, Map<String, Object> payload, String note) {

        public FileCommand {
            payload = payload == null ? Map.of() : payload;
        }
    }

    /**
     * @param approve          true to apply, false to reject
     * @param resolution       CORRECT_GTIN, MOVE_VARIANT, SPLIT_VARIANT or MERGE, for WRONG_MATCH and DUPLICATE_PAGE
     * @param resolutionParams by resolution: {@code gtin}; {@code survivorAtin} and {@code variantMap} for MERGE
     * @param expectedVersion  the version the reviewer read (null skips the check)
     */
    record DecisionCommand(boolean approve, String reason, String resolution, Map<String, Object> resolutionParams,
                           Long expectedVersion) {

        public DecisionCommand {
            resolutionParams = resolutionParams == null ? Map.of() : resolutionParams;
        }
    }

    /**
     * @param request  the stored request
     * @param title    the page title now
     * @param overdue  waiting past its SLA
     * @param ageHours hours since it was filed
     */
    record ChangeRequestView(ProductChangeRequest request, String title, boolean overdue, long ageHours) {
    }
}
