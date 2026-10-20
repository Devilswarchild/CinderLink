package dev.devilswarchild.cinderlink.core.identity;

import java.util.Arrays;
import java.util.Locale;

/**
 * A Cinder Code: what you give someone so they can send you a friend request.
 *
 * Contents (40 bytes, shown as 64 characters): version, your public identity key, a resettable inbox secret,
 * and a checksum. It contains no address and can't be used to locate you; resetting the inbox secret
 * invalidates every code you've handed out without changing who you are.
 */
public record CinderCode(byte[] identityKey, byte[] inboxSecret) {
    public static final int VERSION = 1;
    public static final int SECRET_BYTES = 5;
    private static final String PREFIX = "CNDR";
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray(); // Crockford base32

    public CinderCode {
        if (identityKey.length != 32 || inboxSecret.length != SECRET_BYTES) throw new IllegalArgumentException();
        identityKey = identityKey.clone();
        inboxSecret = inboxSecret.clone();
    }

    @Override
    public byte[] identityKey() {
        return identityKey.clone();
    }

    @Override
    public byte[] inboxSecret() {
        return inboxSecret.clone();
    }

    public String format() {
        byte[] body = new byte[1 + 32 + SECRET_BYTES];
        body[0] = (byte) VERSION;
        System.arraycopy(identityKey, 0, body, 1, 32);
        System.arraycopy(inboxSecret, 0, body, 33, SECRET_BYTES);
        byte[] check = Arrays.copyOf(Keys.sha256("cinderlink-code".getBytes(), body), 2);
        byte[] all = new byte[body.length + 2];
        System.arraycopy(body, 0, all, 0, body.length);
        System.arraycopy(check, 0, all, body.length, 2);
        String b32 = encode(all);
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < b32.length(); i += 8) sb.append('-').append(b32, i, Math.min(i + 8, b32.length()));
        return sb.toString();
    }

    /** Parses a pasted code. Forgiving about spaces, dashes, case and look-alike letters; strict about the checksum. */
    public static CinderCode parse(String text) {
        if (text == null) throw new IllegalArgumentException("empty");
        String s = text.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if (s.startsWith(PREFIX)) s = s.substring(PREFIX.length());
        s = s.replace('O', '0').replace('I', '1').replace('L', '1');
        if (s.length() != 64) throw new IllegalArgumentException("wrong length");
        byte[] all = decode(s);
        byte[] body = Arrays.copyOf(all, all.length - 2);
        byte[] check = Arrays.copyOf(Keys.sha256("cinderlink-code".getBytes(), body), 2);
        if (all[all.length - 2] != check[0] || all[all.length - 1] != check[1]) {
            throw new IllegalArgumentException("checksum mismatch (typo or incomplete paste)");
        }
        if (body[0] != VERSION) throw new IllegalArgumentException("code is from a newer CinderLink version");
        return new CinderCode(Arrays.copyOfRange(body, 1, 33), Arrays.copyOfRange(body, 33, 33 + SECRET_BYTES));
    }

    static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer << (5 - bits)) & 31]);
        return sb.toString();
    }

    static byte[] decode(String s) {
        byte[] out = new byte[s.length() * 5 / 8];
        int buffer = 0, bits = 0, idx = 0;
        for (char c : s.toCharArray()) {
            int v = -1;
            for (int i = 0; i < ALPHABET.length; i++) if (ALPHABET[i] == c) v = i;
            if (v < 0) throw new IllegalArgumentException("invalid character '" + c + "'");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CinderCode c && Arrays.equals(identityKey, c.identityKey)
                && Arrays.equals(inboxSecret, c.inboxSecret);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(identityKey) * 31 + Arrays.hashCode(inboxSecret);
    }

    @Override
    public String toString() {
        return "CinderCode[redacted]";
    }
}
