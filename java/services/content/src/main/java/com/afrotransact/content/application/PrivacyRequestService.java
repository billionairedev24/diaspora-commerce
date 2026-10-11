package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.domain.privacy.PrivacyRequest;
import com.afrotransact.content.domain.privacy.PrivacyRequestStatus;

/**
 * Privacy requests from Your Privacy Choices (access, delete, correct, opt out). Anyone may submit one; staff with
 * {@code content:legal.view} read the queue and staff with {@code content:legal.edit} complete or deny requests.
 */
public interface PrivacyRequestService {

    /** Validates and stores a request; a signed-in caller's account id is attached. */
    PrivacyRequest submit(PrivacyRequest.Submission submission);

    /** Newest first, optionally one status (content:legal.view). */
    List<PrivacyRequest> list(PrivacyRequestStatus status);

    /** One request by its PR-###### reference (content:legal.view). */
    PrivacyRequest get(String reference);

    /** Completes or denies a received request (content:legal.edit); audited. */
    PrivacyRequest resolve(String reference, PrivacyRequestStatus outcome, String note);
}
