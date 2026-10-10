package com.afrotransact.payments.application;

import java.util.UUID;

/** What the idempotency store keeps for a write: the id of the resource it created. */
record StoredRef(UUID id) {
}
