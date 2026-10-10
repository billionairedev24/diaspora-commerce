package com.afrotransact.retail.adapter.out.edi;

import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.retail.port.EdiTransport;
import com.afrotransact.retail.port.EdiTransportException;

/**
 * SKELETON — AS2 (RFC 4130) to the partner's HTTPS endpoint: {@code AS2-From} = our AS2 id, {@code AS2-To} = the
 * partner's interchange id, payload {@code application/edi-x12}, signed with the retail EDI certificate, encrypted
 * for the partner and a signed synchronous MDN requested; the MDN's {@code Message-ID} is the receipt.
 *
 * <p>S/MIME signing / encryption and MDN verification are not wired yet (no AS2 library in the build):
 * {@link #transmit} validates the settings and builds the headers, then fails without retry. To finish it, add an
 * AS2 library (for example as2-lib) configured with {@code at.retail.edi.as2.*}. Enabled only with
 * {@code at.retail.edi.enabled=true}.
 */
public class As2EdiTransport implements EdiTransport {

    private final String as2From;
    private final String keystorePath;

    public As2EdiTransport(String as2From, String keystorePath) {
        this.as2From = as2From;
        this.keystorePath = keystorePath;
    }

    @Override
    public String transmit(EdiMessage message) {
        if (as2From == null || as2From.isBlank() || keystorePath == null || keystorePath.isBlank()) {
            throw new EdiTransportException("AS2 transport not configured (at.retail.edi.as2.from, keystore-path)",
                    false, null);
        }
        Map<String, String> headers = headers(message);
        throw new EdiTransportException("AS2 signing/encryption not implemented yet; would POST to "
                + message.endpoint() + " with " + headers.keySet(), false, null);
    }

    Map<String, String> headers(EdiMessage message) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("AS2-Version", "1.2");
        h.put("AS2-From", as2From);
        h.put("AS2-To", message.interchangeId());
        h.put("Subject", "X12 " + message.documentType() + " " + message.reference());
        h.put("Content-Type", "application/edi-x12");
        h.put("Disposition-Notification-To", as2From);
        h.put("Disposition-Notification-Options",
                "signed-receipt-protocol=optional, pkcs7-signature; signed-receipt-micalg=optional, sha-256");
        return h;
    }
}
