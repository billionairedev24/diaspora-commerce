package com.afrotransact.retail.adapter.out.edi;

import java.util.Map;

import com.afrotransact.retail.domain.EdiProtocol;
import com.afrotransact.retail.port.EdiTransport;
import com.afrotransact.retail.port.EdiTransportException;

/**
 * Picks the adapter for the partner's protocol. EDI is off by default ({@code at.retail.edi.enabled=false}): every
 * transmission then fails without retry ("EDI transport disabled"), so EDI dispatches show FAILED and can be retried
 * by a buyer once the transport is configured.
 */
public class RoutingEdiTransport implements EdiTransport {

    private final boolean enabled;
    private final Map<EdiProtocol, EdiTransport> byProtocol;

    public RoutingEdiTransport(boolean enabled, Map<EdiProtocol, EdiTransport> byProtocol) {
        this.enabled = enabled;
        this.byProtocol = Map.copyOf(byProtocol);
    }

    @Override
    public String transmit(EdiMessage message) {
        if (!enabled) {
            throw new EdiTransportException("EDI transport disabled in this environment (at.retail.edi.enabled)",
                    false, null);
        }
        EdiTransport transport = byProtocol.get(message.protocol());
        if (transport == null) {
            throw new EdiTransportException("No EDI transport for " + message.protocol(), false, null);
        }
        return transport.transmit(message);
    }
}
