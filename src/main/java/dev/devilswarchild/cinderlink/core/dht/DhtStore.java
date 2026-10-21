package dev.devilswarchild.cinderlink.core.dht;

import java.util.Optional;

/** Where signed records live. The real implementation is {@link DhtClient}; tests use an in-memory map. */
public interface DhtStore {
    Optional<MutableItem> get(byte[] publicKey, byte[] salt);

    int put(MutableItem item);

    /**
     * Like {@link #get(byte[], byte[])} with control over speed: re-search if the remembered route is older
     * than {@code maxRouteAgeMs}, and optionally stop at the first valid copy (for records that rarely change).
     */
    default Optional<MutableItem> get(byte[] publicKey, byte[] salt, long maxRouteAgeMs, boolean firstValueWins) {
        return get(publicKey, salt);
    }

    /** Finds (and remembers) the nodes for a record ahead of time, so a later put is a single round trip. */
    default void warm(byte[] publicKey, byte[] salt) {}
}
