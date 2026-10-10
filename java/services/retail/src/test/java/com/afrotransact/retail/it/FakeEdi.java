package com.afrotransact.retail.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.afrotransact.retail.port.EdiTransport;
import com.afrotransact.retail.port.EdiTransportException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Records X12 interchanges instead of sending them; can be told to refuse (partner down). */
@TestConfiguration(proxyBeanMethods = false)
class FakeEdi {

    static final List<EdiTransport.EdiMessage> SENT = new CopyOnWriteArrayList<>();
    static volatile boolean down;

    @Bean
    @Primary
    EdiTransport fakeEdiTransport() {
        return message -> {
            if (down) {
                throw new EdiTransportException("partner SFTP unreachable", true, null);
            }
            SENT.add(message);
            return "fake://" + message.interchangeId() + "/" + message.reference() + "_" + message.controlNumber();
        };
    }
}
