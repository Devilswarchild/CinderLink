package dev.devilswarchild.cinderlink.core.presence;

import dev.devilswarchild.cinderlink.core.dht.Bencode;
import dev.devilswarchild.cinderlink.core.dht.MutableItem;
import dev.devilswarchild.cinderlink.core.identity.Identity;
import dev.devilswarchild.cinderlink.core.identity.Keys;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Every key derivation and wire format in one place. Version-tagged ("cl1") so a future format can coexist.
 *
 * <ul>
 *   <li><b>Card</b>: your X25519 public key, signed by your identity key. Found via your Cinder Code.</li>
 *   <li><b>Inbox</b>: {@link #INBOX_SLOTS} drop boxes whose signing keys anyone holding your current code can
 *       derive. Requests are encrypted to your card key, so only you can read them.</li>
 *   <li><b>Pair records</b>: one per friend per direction, signed by a key derived from the pair's shared
 *       secret. Outsiders can't compute where they live, can't read them, and can't link them to you.</li>
 * </ul>
 */
public final class Protocol {
    public static final int INBOX_SLOTS = 4;
    static final byte[] CARD_SALT = bytes("cl1-card");
    static final byte[] INBOX_SALT = bytes("cl1-in");
    static final byte[] PAIR_SALT = bytes("cl1-p");
    private static final byte[] AAD_PAIR = bytes("cinderlink-presence-v1");
    private static final byte[] AAD_REQ = bytes("cinderlink-request-v1");

    private Protocol() {}

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    // ---- card

    static MutableItem card(Identity me, long seq) {
        Map<String, Object> m = new TreeMap<>();
        m.put("x", me.boxPublic());
        return MutableItem.sign(me.signingKeyPair(), CARD_SALT, seq, Bencode.encode(m));
    }

    static byte[] cardBoxKey(MutableItem item) {
        try {
            byte[] x = Bencode.bytes(Bencode.decode(item.value()), "x");
            return x != null && x.length == 32 ? x : null;
        } catch (Bencode.DecodeException e) {
            return null;
        }
    }

    // ---- inbox

    static KeyPair inboxKey(byte[] ownerSignPublic, byte[] inboxSecret, int slot) {
        return Keys.ed25519FromSeed(Keys.sha256(bytes("cl1-inbox"), ownerSignPublic, inboxSecret, new byte[]{(byte) slot}));
    }

    /**
     * A request from {@code me} to the owner of ({@code toSign}, {@code toBox}), signed and encrypted.
     * {@code requestId} is fixed when the sender clicks Send and stays the same across automatic re-sends,
     * so the receiver can tell "the same request again" from "a new request".
     */
    static byte[] sealRequest(Identity me, String mcName, UUID mcUuid, long now, long requestId, byte[] toSign,
                              byte[] toBox) throws GeneralSecurityException {
        Map<String, Object> body = new TreeMap<>();
        body.put("k", me.signPublic());
        body.put("x", me.boxPublic());
        body.put("n", mcName);
        body.put("u", PresenceRecord.uuidBytes(mcUuid));
        body.put("t", now);
        body.put("r", requestId);
        Map<String, Object> signed = new TreeMap<>(body);
        signed.put("to", toSign); // binds the request to its recipient so it can't be replayed elsewhere
        body.put("sig", me.sign(Bencode.encode(signed)));
        KeyPair eph = Keys.newX25519();
        byte[] key = Keys.sha256(bytes("cl1-req"), Keys.agree(eph.getPrivate(), toBox));
        byte[] sealed = Seal.encrypt(key, AAD_REQ, Bencode.encode(body));
        byte[] out = new byte[32 + sealed.length];
        System.arraycopy(Keys.raw(eph.getPublic()), 0, out, 0, 32);
        System.arraycopy(sealed, 0, out, 32, sealed.length);
        return out;
    }

    public record Request(byte[] signPublic, byte[] boxPublic, String mcName, UUID mcUuid, long timestampMs,
                          long requestId) {}

    /** Decrypts and verifies a request addressed to {@code me}; null if it isn't valid. */
    static Request openRequest(Identity me, byte[] data) {
        if (data == null || data.length < 32 + 28) return null;
        try {
            byte[] shared = me.sharedSecret(Arrays.copyOf(data, 32));
            byte[] plain = Seal.decrypt(Keys.sha256(bytes("cl1-req"), shared), AAD_REQ,
                    Arrays.copyOfRange(data, 32, data.length));
            if (plain == null) return null;
            Object o = Bencode.decode(plain);
            byte[] k = Bencode.bytes(o, "k"), x = Bencode.bytes(o, "x"), u = Bencode.bytes(o, "u"),
                    sig = Bencode.bytes(o, "sig");
            String n = Bencode.str(o, "n");
            Long t = Bencode.integer(o, "t");
            Long rid = Bencode.integer(o, "r");
            if (rid == null) return null;
            if (k == null || k.length != 32 || x == null || x.length != 32 || u == null || u.length != 16
                    || sig == null || t == null || !PresenceRecord.validName(n)) return null;
            Map<String, Object> signed = new TreeMap<>();
            signed.put("k", k);
            signed.put("x", x);
            signed.put("n", n);
            signed.put("u", u);
            signed.put("t", t);
            signed.put("r", rid);
            signed.put("to", me.signPublic());
            if (!Keys.verify(k, Bencode.encode(signed), sig)) return null;
            return new Request(k, x, n, PresenceRecord.uuid(u), t, rid);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- pair records

    /** Signing key for records written by {@code author} to the other member of the pair. */
    static KeyPair pairKey(byte[] pairSecret, byte[] authorSignPublic) {
        return Keys.ed25519FromSeed(Keys.sha256(bytes("cl1-pair-sign"), pairSecret, authorSignPublic));
    }

    private static byte[] pairEncKey(byte[] pairSecret, byte[] authorSignPublic) {
        return Keys.sha256(bytes("cl1-pair-enc"), pairSecret, authorSignPublic);
    }

    static byte[] sealRecord(byte[] pairSecret, byte[] authorSignPublic, PresenceRecord r) {
        return Seal.encrypt(pairEncKey(pairSecret, authorSignPublic), AAD_PAIR, r.encode());
    }

    static PresenceRecord openRecord(byte[] pairSecret, byte[] authorSignPublic, byte[] sealed) {
        byte[] plain = Seal.decrypt(pairEncKey(pairSecret, authorSignPublic), AAD_PAIR, sealed);
        return plain == null ? null : PresenceRecord.decode(plain);
    }
}
