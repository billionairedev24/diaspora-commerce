package com.afrotransact.retail.domain;

/** How an EDI-enabled supplier receives X12 documents. */
public enum EdiProtocol {
    /** File drop on the supplier's (or their VAN's) SFTP server. */
    SFTP,
    /** AS2 over HTTPS (signed and encrypted S/MIME, MDN receipt). */
    AS2
}
