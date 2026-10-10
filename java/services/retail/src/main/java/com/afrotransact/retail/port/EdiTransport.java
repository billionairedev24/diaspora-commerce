package com.afrotransact.retail.port;

import com.afrotransact.retail.domain.EdiProtocol;

/**
 * Delivers X12 documents to an EDI-enabled supplier (S225). Adapters: SFTP and AS2 skeletons (off by default), a
 * fake in tests.
 */
public interface EdiTransport {

    /**
     * Transmits one interchange.
     *
     * @return the transport's receipt (remote file name, AS2 message id)
     * @throws EdiTransportException when it was not delivered ({@link EdiTransportException#retryable()} tells the
     *                               dispatch job whether to try again)
     */
    String transmit(EdiMessage message);

    /**
     * One X12 interchange for a trading partner.
     *
     * @param controlNumber ISA13 interchange control number (the partner de-duplicates on it)
     * @param documentType  X12 transaction set, e.g. 850
     * @param reference     our document reference (PO number)
     */
    record EdiMessage(String supplierCode, String interchangeId, EdiProtocol protocol, String endpoint,
                      String controlNumber, String documentType, String reference, String payload) {
    }
}
