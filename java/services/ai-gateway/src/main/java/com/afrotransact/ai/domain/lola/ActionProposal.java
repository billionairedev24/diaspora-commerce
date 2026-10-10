package com.afrotransact.ai.domain.lola;

import java.util.Map;

/**
 * Something Lola wants to do that needs the shopper's tap. Lola never adds to cart, checks out, books or starts a
 * return herself: the client shows the proposal and performs the action through the normal APIs when confirmed.
 *
 * @param id                   proposal id (echoed by the client when confirming)
 * @param type                 what would happen
 * @param label                button text, for example "Add all to cart"
 * @param payload              what the client sends to the owning API (cart lines, booking slot, order id …)
 * @param requiresConfirmation always true: spending money, carts, bookings and returns are confirmed by the user
 */
public record ActionProposal(String id, ProposalType type, String label, Map<String, Object> payload,
                             boolean requiresConfirmation) {

    public ActionProposal {
        if (id == null || type == null) {
            throw new IllegalArgumentException("id and type are required");
        }
        label = label == null ? "" : label;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        requiresConfirmation = true;
    }

    public static ActionProposal of(String id, ProposalType type, String label, Map<String, Object> payload) {
        return new ActionProposal(id, type, label, payload, true);
    }
}
