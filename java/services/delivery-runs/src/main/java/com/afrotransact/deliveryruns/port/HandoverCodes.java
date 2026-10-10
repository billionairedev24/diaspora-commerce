package com.afrotransact.deliveryruns.port;

/** Issues pickup-point handover codes (six digits, unguessable). */
public interface HandoverCodes {

    String next();
}
