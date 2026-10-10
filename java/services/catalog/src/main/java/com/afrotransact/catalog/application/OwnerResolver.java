package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.platform.security.Actor;

/** Decides who owns a new listing from the actor — sellers list for their org, retail staff for AfroTransact Retail. */
public interface OwnerResolver {

    ListingOwner ownerFor(Actor actor);
}
