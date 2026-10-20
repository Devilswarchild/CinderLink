package dev.devilswarchild.cinderlink.core.presence;

import dev.devilswarchild.cinderlink.core.dht.DhtStore;
import dev.devilswarchild.cinderlink.core.dht.MutableItem;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory stand-in for the DHT with the same rules: verified signatures, higher seq wins. */
final class MemoryDht implements DhtStore {
    final Map<String, MutableItem> items = new ConcurrentHashMap<>();

    @Override
    public Optional<MutableItem> get(byte[] publicKey, byte[] salt) {
        return Optional.ofNullable(items.get(HexFormat.of().formatHex(MutableItem.target(publicKey, salt))));
    }

    @Override
    public synchronized int put(MutableItem item) {
        if (!item.verify()) return 0;
        String k = HexFormat.of().formatHex(item.target());
        MutableItem old = items.get(k);
        if (old != null && old.seq() >= item.seq()) return 0;
        items.put(k, item);
        return 8;
    }
}
