package dev.devilswarchild.cinderlink.core.identity;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import javax.crypto.KeyAgreement;

/**
 * Thin wrappers over the JDK's built-in Ed25519 (signing) and X25519 (key agreement). No third-party crypto.
 * Public keys travel as raw 32-byte values; private keys are stored PKCS#8-encoded.
 */
public final class Keys {
    private static final byte[] ED_PREFIX = hex("302a300506032b6570032100");
    private static final byte[] X_PREFIX = hex("302a300506032b656e032100");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Keys() {}

    public static KeyPair newEd25519() {
        return generate("Ed25519", NamedParameterSpec.ED25519, RANDOM);
    }

    public static KeyPair newX25519() {
        return generate("X25519", NamedParameterSpec.X25519, RANDOM);
    }

    /** Deterministic Ed25519 key pair from a 32-byte seed (used for unlinkable per-friend record keys). */
    public static KeyPair ed25519FromSeed(byte[] seed32) {
        return generate("Ed25519", NamedParameterSpec.ED25519, new FixedRandom(seed32));
    }

    private static KeyPair generate(String alg, NamedParameterSpec spec, SecureRandom random) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance(alg);
            g.initialize(spec, random);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(alg + " unavailable in this Java runtime", e);
        }
    }

    public static byte[] raw(PublicKey key) {
        byte[] enc = key.getEncoded();
        return Arrays.copyOfRange(enc, enc.length - 32, enc.length);
    }

    public static PublicKey ed25519Public(byte[] raw32) {
        return decodePublic("Ed25519", ED_PREFIX, raw32);
    }

    public static PublicKey x25519Public(byte[] raw32) {
        return decodePublic("X25519", X_PREFIX, raw32);
    }

    private static PublicKey decodePublic(String alg, byte[] prefix, byte[] raw32) {
        if (raw32 == null || raw32.length != 32) throw new IllegalArgumentException("public key must be 32 bytes");
        byte[] enc = new byte[prefix.length + 32];
        System.arraycopy(prefix, 0, enc, 0, prefix.length);
        System.arraycopy(raw32, 0, enc, prefix.length, 32);
        try {
            return KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(enc));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a valid " + alg + " key", e);
        }
    }

    public static PrivateKey privateKey(String alg, byte[] pkcs8) throws GeneralSecurityException {
        return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    public static byte[] sign(PrivateKey key, byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(message);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(byte[] publicRaw, byte[] message, byte[] signature) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(ed25519Public(publicRaw));
            s.update(message);
            return s.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** X25519 shared secret, hashed so it's never used raw. */
    public static byte[] agree(PrivateKey mine, byte[] theirRaw) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(mine);
        ka.doPhase(x25519Public(theirRaw), true);
        byte[] shared = ka.generateSecret();
        boolean allZero = true;
        for (byte b : shared) allZero &= b == 0;
        if (allZero) throw new GeneralSecurityException("degenerate key agreement");
        return sha256(shared);
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha1(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    /** Feeds a fixed seed to the key generator so the same seed always yields the same key pair. */
    private static final class FixedRandom extends SecureRandom {
        private final byte[] seed;
        private int pos;

        FixedRandom(byte[] seed) {
            if (seed.length != 32) throw new IllegalArgumentException("seed must be 32 bytes");
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) bytes[i] = seed[pos++ % seed.length];
        }
    }
}
