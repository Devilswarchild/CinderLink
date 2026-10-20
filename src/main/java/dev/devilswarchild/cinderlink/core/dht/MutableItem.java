package dev.devilswarchild.cinderlink.core.dht;

import dev.devilswarchild.cinderlink.core.identity.Keys;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

/**
 * A BEP 44 mutable item: a value of up to 1000 bytes, signed by an Ed25519 key, stored at
 * SHA1(publicKey + salt). Higher {@code seq} replaces lower. We always store a single byte string as the value.
 */
public record MutableItem(byte[] publicKey, byte[] salt, long seq, byte[] value, byte[] signature) {
    public static final int MAX_VALUE = 1000 - 6; // the bencoded value (with its length prefix) must fit in 1000
    public static final int MAX_SALT = 64;

    public static MutableItem sign(KeyPair key, byte[] salt, long seq, byte[] value) {
        if (value.length > MAX_VALUE) throw new IllegalArgumentException("value too large for the DHT");
        if (salt.length > MAX_SALT) throw new IllegalArgumentException("salt too long");
        byte[] pub = Keys.raw(key.getPublic());
        return new MutableItem(pub, salt.clone(), seq, value.clone(),
                Keys.sign(key.getPrivate(), signingBuffer(salt, seq, value)));
    }

    public static byte[] target(byte[] publicKey, byte[] salt) {
        return Keys.sha1(publicKey, salt);
    }

    public byte[] target() {
        return target(publicKey, salt);
    }

    public boolean verify() {
        return publicKey.length == 32 && signature.length == 64 && value.length <= MAX_VALUE
                && Keys.verify(publicKey, signingBuffer(salt, seq, value), signature);
    }

    /** Exactly the bytes BEP 44 says to sign: [4:salt + salt] 3:seqi{seq}e1:v + bencoded value. */
    static byte[] signingBuffer(byte[] salt, long seq, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (salt.length > 0) {
            out.writeBytes("4:salt".getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(Bencode.encode(salt));
        }
        out.writeBytes(("3:seqi" + seq + "e1:v").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(Bencode.encode(value));
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MutableItem m && seq == m.seq && Arrays.equals(publicKey, m.publicKey)
                && Arrays.equals(salt, m.salt) && Arrays.equals(value, m.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(publicKey) ^ Long.hashCode(seq);
    }
}
