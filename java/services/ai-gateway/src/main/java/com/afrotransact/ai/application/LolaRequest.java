package com.afrotransact.ai.application;

/**
 * One shopper message to Lola.
 *
 * @param sessionId conversation id from the client (a new one is created when absent)
 * @param message   what the shopper typed
 * @param zip       deliver-to ZIP from the header picker, used by check_delivery and find_service
 * @param language  preferred reply language (BCP 47), may be null
 */
public record LolaRequest(String sessionId, String message, String zip, String language) {
}
