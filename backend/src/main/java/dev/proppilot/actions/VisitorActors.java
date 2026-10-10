package dev.proppilot.actions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Names the anonymous people who decide on actions without storing who they are: a short hash of their address and a
 * salt that exists only in this process, so the same visitor keeps one name until the next restart and no address is
 * ever written down or recoverable.
 */
@Component
public class VisitorActors {

    private static final int HEX_CHARS = 8;

    private final byte[] salt = new byte[16];

    VisitorActors() {
        new SecureRandom().nextBytes(salt);
    }

    public String actorFor(String clientAddress) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(clientAddress.getBytes(StandardCharsets.UTF_8));
            digest.update(salt);
            return "visitor-" + HexFormat.of().formatHex(digest.digest()).substring(0, HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
