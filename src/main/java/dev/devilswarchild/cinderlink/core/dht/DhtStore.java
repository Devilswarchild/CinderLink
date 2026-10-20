package dev.devilswarchild.cinderlink.core.dht;

import java.util.Optional;

/** Where signed records live. The real implementation is {@link DhtClient}; tests use an in-memory map. */
public interface DhtStore {
    Optional<MutableItem> get(byte[] publicKey, byte[] salt);

    int put(MutableItem item);
}
