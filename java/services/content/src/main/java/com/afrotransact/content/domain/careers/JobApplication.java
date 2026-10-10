package com.afrotransact.content.domain.careers;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.content.domain.EmailAddress;

/**
 * A validated, stored application.
 *
 * @param id                id (null until issued)
 * @param reference         candidate-facing reference ({@code CAR-123456}), null until issued
 * @param jobId             role
 * @param name              full name
 * @param email             email
 * @param linkedinUrl       LinkedIn URL or null
 * @param workAuthorization answer
 * @param resumeAssetId     media asset id
 * @param resumeFileName    file name
 * @param coverNote         note or null
 * @param submittedAt       when (consent was given at the same time)
 */
public record JobApplication(UUID id, String reference, String jobId, String name, EmailAddress email,
                             String linkedinUrl, WorkAuthorization workAuthorization, String resumeAssetId,
                             String resumeFileName, String coverNote, Instant submittedAt) {

    /** The same application with its id and reference issued. */
    public JobApplication issued(UUID newId, String newReference) {
        return new JobApplication(newId, newReference, jobId, name, email, linkedinUrl, workAuthorization,
                resumeAssetId, resumeFileName, coverNote, submittedAt);
    }

    /** Formats a sequence number as {@code CAR-######}. */
    public static String reference(long sequence) {
        return String.format("CAR-%06d", sequence);
    }
}
