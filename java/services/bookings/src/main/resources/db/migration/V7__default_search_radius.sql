-- The shopper chooses how far to go: the platform no longer limits customer travel or caps nearest suggestions.
-- The category radius rules stay as the default search radius (and the bounds of a provider's declared radius).
update service_area_policy set policy = policy - 'customerTravelMiles' - 'nearestMaxMiles' where id = 1;
