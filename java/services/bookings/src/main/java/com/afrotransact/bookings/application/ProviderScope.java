package com.afrotransact.bookings.application;

import com.afrotransact.platform.security.Actor;

/**
 * Who is acting for a provider organisation and how far they reach.
 *
 * @param ownOnly practitioners see and change only their own calendar and hours
 */
record ProviderScope(Actor actor, String orgId, boolean ownOnly) {
}
