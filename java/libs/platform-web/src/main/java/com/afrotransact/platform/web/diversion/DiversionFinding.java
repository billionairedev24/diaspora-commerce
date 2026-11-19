package com.afrotransact.platform.web.diversion;

/** One thing the scan found; the excerpt is the matched text (for staff and the seller, never for logs). */
public record DiversionFinding(DiversionKind kind, String excerpt) {
}
