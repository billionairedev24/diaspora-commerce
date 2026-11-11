package com.afrotransact.seller.application;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.seller.application.SellingViews.SellingMyApplication;

/**
 * A seller applies to sell in a scope (owner, admin or catalog role): the documents the rule asks for are uploaded to
 * media-service first and referenced here with their dates. The application becomes a staff case of the same
 * two-step review as seller applications.
 */
public interface SellingApplicationService {

    /** Applies, or renews an approval whose document is being replaced. Repeating it while one is in progress returns that one. */
    SellingMyApplication apply(String orgId, ApplyCommand command);

    /** Answers a request for information with the documents asked for; the review starts again at step 1. */
    SellingMyApplication resubmit(String orgId, String applicationId, ResubmitCommand command);

    /** The organisation's applications, newest first. */
    List<SellingMyApplication> list(String orgId);

    SellingMyApplication get(String orgId, String applicationId);

    /**
     * @param assetId          the media-service asset of the uploaded file
     * @param issuedOn         the date on an invoice
     * @param expiresOn        the expiry date of a permit, licence or policy
     * @param namesAfroTransact the seller confirms the policy names AfroTransact as additional insured
     */
    record DocumentInput(String kind, String assetId, String fileName, LocalDate issuedOn, LocalDate expiresOn,
                         boolean namesAfroTransact, String note) {
    }

    record ApplyCommand(String scopeType, String scopeKey, String note, List<DocumentInput> documents) {

        public ApplyCommand {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }

    record ResubmitCommand(String note, List<DocumentInput> documents) {

        public ResubmitCommand {
            documents = documents == null ? List.of() : List.copyOf(documents);
        }
    }
}
