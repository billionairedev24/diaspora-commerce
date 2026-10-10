package com.afrotransact.retail.adapter.out.edi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.afrotransact.retail.domain.EdiProtocol;
import com.afrotransact.retail.port.EdiTransport;
import com.afrotransact.retail.port.EdiTransportException;
import org.junit.jupiter.api.Test;

/** The EDI adapters are off by default and the SFTP / AS2 skeletons refuse without retry until wired. */
class EdiTransportsTest {

    private static final EdiTransport.EdiMessage MESSAGE = new EdiTransport.EdiMessage("SUP-ACC", "ACCRA01",
            EdiProtocol.SFTP, "sftp://edi.accrapack.example/inbound/po", "000012345", "850", "PO-1P-323", "ISA*…~");

    @Test
    void disabledByDefault() {
        EdiTransport off = new RoutingEdiTransport(false, Map.of(EdiProtocol.SFTP, m -> "sent"));
        assertThatThrownBy(() -> off.transmit(MESSAGE)).isInstanceOf(EdiTransportException.class)
                .hasMessageContaining("disabled").satisfies(e -> assertThat(((EdiTransportException) e).retryable())
                        .isFalse());
        EdiTransport on = new RoutingEdiTransport(true, Map.of(EdiProtocol.SFTP, m -> "sent:" + m.reference()));
        assertThat(on.transmit(MESSAGE)).isEqualTo("sent:PO-1P-323");
    }

    @Test
    void sftpSkeletonComputesTheRemoteFileAndRefusesUntilWired() {
        assertThat(SftpEdiTransport.remotePath(MESSAGE)).isEqualTo("/inbound/po/PO-1P-323_000012345.x12");
        assertThatThrownBy(() -> new SftpEdiTransport(null, null, null).transmit(MESSAGE))
                .isInstanceOf(EdiTransportException.class).hasMessageContaining("not configured");
        assertThatThrownBy(() -> new SftpEdiTransport("retail", "/keys/id", "/keys/known").transmit(MESSAGE))
                .isInstanceOf(EdiTransportException.class).hasMessageContaining("not implemented");
    }

    @Test
    void as2SkeletonBuildsTheHeaders() {
        As2EdiTransport as2 = new As2EdiTransport("AFROTRANSACT", "/keys/as2.p12");
        assertThat(as2.headers(MESSAGE)).containsEntry("AS2-From", "AFROTRANSACT").containsEntry("AS2-To", "ACCRA01")
                .containsEntry("Content-Type", "application/edi-x12");
        assertThatThrownBy(() -> as2.transmit(MESSAGE)).isInstanceOf(EdiTransportException.class);
    }
}
