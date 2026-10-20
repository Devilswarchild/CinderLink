package dev.devilswarchild.cinderlink.core.presence;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Someone on your list. {@code boxPublic} is null for an outgoing request whose card we haven't fetched yet;
 * {@code inboxSecret} is only kept for outgoing requests (so we can keep re-sending until accepted).
 */
public record Friend(byte[] signPublic, byte[] boxPublic, Relation relation, String mcName, UUID mcUuid,
                     byte[] inboxSecret, long addedAtMs) {
    public enum Relation {
        /** Both sides accepted: presence is exchanged. */
        FRIEND,
        /** We pasted their code and are waiting for them to accept. */
        OUTGOING,
        /** They sent us a request we haven't answered. */
        INCOMING
    }

    /** Stable key for maps: hex of the identity key. */
    public String id() {
        return HexFormat.of().formatHex(signPublic);
    }

    /** Short fingerprint people can compare out loud to be sure they added the right person. */
    public String fingerprint() {
        String hex = HexFormat.of().withUpperCase().formatHex(signPublic, 0, 4);
        return hex.substring(0, 4) + "-" + hex.substring(4);
    }

    Friend with(Relation r, byte[] box, String name, UUID uuid) {
        return new Friend(signPublic, box, r, name, uuid, r == Relation.OUTGOING ? inboxSecret : null, addedAtMs);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Friend f && Arrays.equals(signPublic, f.signPublic);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(signPublic);
    }

    @Override
    public String toString() {
        return "Friend[" + mcName + ", " + relation + "]";
    }
}
