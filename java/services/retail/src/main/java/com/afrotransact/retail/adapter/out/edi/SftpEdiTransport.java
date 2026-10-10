package com.afrotransact.retail.adapter.out.edi;

import java.net.URI;
import java.util.Locale;

import com.afrotransact.retail.port.EdiTransport;
import com.afrotransact.retail.port.EdiTransportException;

/**
 * SKELETON — SFTP file drop to the supplier's (or their VAN's) server: {@code <endpoint path>/<PO>_<ISA13>.x12},
 * written as {@code .part} then renamed so the partner never picks up half a file. Authentication with the retail
 * EDI key ({@code at.retail.edi.sftp.private-key-path}, host key pinned via {@code known-hosts-path}).
 *
 * <p>The SSH client is not wired yet (no SFTP library in the build): {@link #transmit} validates the partner
 * settings and computes the remote path, then fails without retry. To finish it, add an SSH/SFTP client (for example
 * sshj), open a session with the key, upload to {@link #remotePath} + ".part" and rename. Enabled only with
 * {@code at.retail.edi.enabled=true}.
 */
public class SftpEdiTransport implements EdiTransport {

    private final String username;
    private final String privateKeyPath;
    private final String knownHostsPath;

    public SftpEdiTransport(String username, String privateKeyPath, String knownHostsPath) {
        this.username = username;
        this.privateKeyPath = privateKeyPath;
        this.knownHostsPath = knownHostsPath;
    }

    @Override
    public String transmit(EdiMessage message) {
        if (username == null || username.isBlank() || privateKeyPath == null || privateKeyPath.isBlank()
                || knownHostsPath == null || knownHostsPath.isBlank()) {
            throw new EdiTransportException("SFTP transport not configured (at.retail.edi.sftp.username, "
                    + "private-key-path, known-hosts-path)", false, null);
        }
        String path = remotePath(message);
        throw new EdiTransportException("SFTP client not implemented yet; would upload " + path + " as " + username,
                false, null);
    }

    /** Remote file for the interchange: the endpoint's directory plus {@code <PO>_<control>.x12}. */
    static String remotePath(EdiMessage message) {
        URI uri = URI.create(message.endpoint());
        String dir = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        String file = (message.reference() + "_" + message.controlNumber() + ".x12").replaceAll("[^A-Za-z0-9._-]",
                "_");
        return (dir.endsWith("/") ? dir : dir + "/") + file.toUpperCase(Locale.ROOT).replace(".X12", ".x12");
    }
}
