package com.afrotransact.content.domain.careers;

/**
 * What a candidate submitted.
 *
 * @param name              full name
 * @param email             email
 * @param linkedin          LinkedIn profile URL (optional)
 * @param workAuthorization answer to "Authorised to work in the US?"
 * @param resumeAssetId     media-service asset id of the uploaded résumé
 * @param resumeFileName    original file name
 * @param coverNote         optional note
 * @param consent           consent to process the application
 */
public record ApplicationDraft(String name, String email, String linkedin, String workAuthorization,
                               String resumeAssetId, String resumeFileName, String coverNote, Boolean consent) {
}
