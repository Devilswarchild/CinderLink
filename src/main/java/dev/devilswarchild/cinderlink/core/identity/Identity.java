package dev.devilswarchild.cinderlink.core.identity;

import dev.devilswarchild.cinderlink.core.util.SecureFiles;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Properties;

/**
 * Who you are to CinderLink: an Ed25519 signing key (your identity), an X25519 key for encryption, and the
 * inbox secret baked into your Cinder Code. Lives in identity.properties in {@link SecureFiles#home()}.
 */
public final class Identity {
    private static final String FILE = "identity.properties";

    private final byte[] signPublic;
    private final PrivateKey signPrivate;
    private final byte[] boxPublic;
    private final PrivateKey boxPrivate;
    private volatile byte[] inboxSecret;

    private Identity(byte[] signPublic, PrivateKey signPrivate, byte[] boxPublic, PrivateKey boxPrivate,
                     byte[] inboxSecret) {
        this.signPublic = signPublic;
        this.signPrivate = signPrivate;
        this.boxPublic = boxPublic;
        this.boxPrivate = boxPrivate;
        this.inboxSecret = inboxSecret;
    }

    public static Identity create() {
        KeyPair sign = Keys.newEd25519();
        KeyPair box = Keys.newX25519();
        return new Identity(Keys.raw(sign.getPublic()), sign.getPrivate(), Keys.raw(box.getPublic()),
                box.getPrivate(), Keys.randomBytes(CinderCode.SECRET_BYTES));
    }

    /** Loads the identity in {@code dir}, creating and saving a new one on first run. */
    public static Identity loadOrCreate(Path dir) throws IOException {
        Path file = dir.resolve(FILE);
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            Identity id = create();
            id.save(dir);
            return id;
        }
    }

    static Identity parse(String text) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(text));
        try {
            Base64.Decoder b = Base64.getDecoder();
            byte[] signPub = b.decode(req(p, "sign.public"));
            byte[] boxPub = b.decode(req(p, "box.public"));
            PrivateKey signPriv = Keys.privateKey("Ed25519", b.decode(req(p, "sign.private")));
            PrivateKey boxPriv = Keys.privateKey("X25519", b.decode(req(p, "box.private")));
            byte[] secret = b.decode(req(p, "inbox.secret"));
            if (signPub.length != 32 || boxPub.length != 32 || secret.length != CinderCode.SECRET_BYTES) {
                throw new IOException("identity file has wrong key sizes");
            }
            // Make sure the stored private key really matches the stored public key.
            byte[] probe = "cinderlink-identity-check".getBytes(StandardCharsets.US_ASCII);
            if (!Keys.verify(signPub, probe, Keys.sign(signPriv, probe))) {
                throw new IOException("identity file keys don't match");
            }
            return new Identity(signPub, signPriv, boxPub, boxPriv, secret);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("identity file is damaged", e);
        }
    }

    private static String req(Properties p, String key) throws IOException {
        String v = p.getProperty(key);
        if (v == null) throw new IOException("identity file is missing " + key);
        return v.trim();
    }

    public void save(Path dir) throws IOException {
        Base64.Encoder b = Base64.getEncoder();
        String text = "# CinderLink identity. This is your private key: never share this file.\n"
                + "# Deleting it gives you a brand new identity and your friends would need to re-add you.\n"
                + "version=1\n"
                + "sign.public=" + b.encodeToString(signPublic) + "\n"
                + "sign.private=" + b.encodeToString(signPrivate.getEncoded()) + "\n"
                + "box.public=" + b.encodeToString(boxPublic) + "\n"
                + "box.private=" + b.encodeToString(boxPrivate.getEncoded()) + "\n"
                + "inbox.secret=" + b.encodeToString(inboxSecret) + "\n";
        SecureFiles.writeAtomically(dir.resolve(FILE), text.getBytes(StandardCharsets.UTF_8));
    }

    /** Invalidates every Cinder Code handed out so far. Existing friends are unaffected. */
    public void resetCode(Path dir) throws IOException {
        inboxSecret = Keys.randomBytes(CinderCode.SECRET_BYTES);
        save(dir);
    }

    public CinderCode code() {
        return new CinderCode(signPublic, inboxSecret);
    }

    public byte[] signPublic() {
        return signPublic.clone();
    }

    public byte[] boxPublic() {
        return boxPublic.clone();
    }

    public byte[] inboxSecret() {
        return inboxSecret.clone();
    }

    /** The identity signing key pair, for publishing records signed as "you" (only the public card is). */
    public KeyPair signingKeyPair() {
        return new KeyPair(Keys.ed25519Public(signPublic), signPrivate);
    }

    public byte[] sign(byte[] message) {
        return Keys.sign(signPrivate, message);
    }

    /** Hashed X25519 secret shared with the holder of {@code theirBoxPublic}. */
    public byte[] sharedSecret(byte[] theirBoxPublic) throws GeneralSecurityException {
        return Keys.agree(boxPrivate, theirBoxPublic);
    }

    PrivateKey boxPrivate() {
        return boxPrivate;
    }

    @Override
    public String toString() {
        return "Identity[redacted]";
    }
}
