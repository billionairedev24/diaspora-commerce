package com.afrotransact.identity.adapter.out.notification;

import com.afrotransact.identity.port.VerificationCodeSender;
import com.afrotransact.identity.port.VerificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local development: writes the code to the log instead of sending it. Never enable outside {@code local}. */
public class LoggingVerificationCodeSender implements VerificationCodeSender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingVerificationCodeSender.class);

    @Override
    public void send(VerificationMessage m) {
        LOG.info("[local] {} code for {} via {} to {}: {} (expires {})", m.purpose(), m.userId(), m.channel(),
                m.destination(), m.code(), m.expiresAt());
    }
}
