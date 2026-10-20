package dev.devilswarchild.cinderlink.core.presence;

import dev.devilswarchild.cinderlink.core.identity.Keys;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** ChaCha20-Poly1305 authenticated encryption (JDK built-in). Output is nonce(12) + ciphertext + tag(16). */
final class Seal {
    private static final int NONCE = 12;

    private Seal() {}

    static byte[] encrypt(byte[] key32, byte[] aad, byte[] plain) {
        try {
            byte[] nonce = Keys.randomBytes(NONCE);
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key32, "ChaCha20"), new IvParameterSpec(nonce));
            c.updateAAD(aad);
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[NONCE + ct.length];
            System.arraycopy(nonce, 0, out, 0, NONCE);
            System.arraycopy(ct, 0, out, NONCE, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns null if the data was tampered with, truncated, or encrypted under a different key. */
    static byte[] decrypt(byte[] key32, byte[] aad, byte[] sealed) {
        if (sealed == null || sealed.length < NONCE + 16) return null;
        try {
            Cipher c = Cipher.getInstance("ChaCha20-Poly1305");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key32, "ChaCha20"),
                    new IvParameterSpec(Arrays.copyOf(sealed, NONCE)));
            c.updateAAD(aad);
            return c.doFinal(sealed, NONCE, sealed.length - NONCE);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
