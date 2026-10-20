package dev.devilswarchild.cinderlink.core.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** File helpers: where CinderLink keeps its data, and crash-safe owner-only writes. */
public final class SecureFiles {
    private SecureFiles() {}

    /**
     * ~/.cinderlink by default: outside every game instance, so modpacks can never ship (and share) someone's
     * identity, and your friends list follows you between packs. Dev clients override it with -Dcinderlink.home.
     */
    public static Path home() {
        String override = System.getProperty("cinderlink.home");
        Path dir = override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".cinderlink");
        return dir.toAbsolutePath().normalize();
    }

    /** Writes to a temp file, then atomically swaps it in, so a crash mid-write can't corrupt the original. */
    public static void writeAtomically(Path file, byte[] data) throws IOException {
        Path dir = file.getParent();
        Files.createDirectories(dir);
        restrict(dir, true);
        Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            restrict(tmp, false);
            Files.write(tmp, data);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Owner-only permissions where the OS supports POSIX permissions (Windows home folders are per-user already). */
    private static void restrict(Path p, boolean directory) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system
        }
    }
}
