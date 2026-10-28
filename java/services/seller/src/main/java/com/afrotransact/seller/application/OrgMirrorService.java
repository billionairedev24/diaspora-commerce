package com.afrotransact.seller.application;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.port.Page;

/**
 * The organisations as identity mirrors them (the same shape as {@code seller.org.approved} /
 * {@code seller.org.updated}: status, kind, names, owners and the approved applicant). Identity reads it to reconcile
 * its copy with this service, the source of truth, when an event was missed or arrived late. Callers need
 * {@link OrgDirectoryService#DIRECTORY_READ} and must be a service or staff.
 */
public interface OrgMirrorService {

    /** Ordered by id; {@code statuses} empty means every status. */
    Page<OrgEventPayload> page(int page, int size, Set<OrgStatus> statuses);

    /** One organisation, or empty when it does not exist. */
    Optional<OrgEventPayload> one(String orgId);
}
