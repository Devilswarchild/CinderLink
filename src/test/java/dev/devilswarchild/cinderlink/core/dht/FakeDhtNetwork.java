package dev.devilswarchild.cinderlink.core.dht;

import dev.devilswarchild.cinderlink.core.identity.Keys;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A small in-process Mainline DHT (BEP 5 + 44) on localhost, for testing the client against real packets. */
final class FakeDhtNetwork implements AutoCloseable {
    final List<FakeNode> nodes = new ArrayList<>();

    enum Kind {
        /** Supports BEP 5 and BEP 44. */
        NORMAL,
        /** Never answers (offline, firewalled, overloaded). */
        SILENT,
        /** Only BEP 5: answers find_node, rejects get/put with error 204, like bootstrap routers and older clients. */
        LEGACY
    }

    FakeDhtNetwork(int count) throws Exception {
        this(count, 0, 0, false);
    }

    /** {@code silentPercent} / {@code legacyPercent} of nodes misbehave; optionally the bootstrap node is LEGACY. */
    FakeDhtNetwork(int count, int silentPercent, int legacyPercent, boolean legacyBootstrap) throws Exception {
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < count; i++) {
            FakeNode n = new FakeNode();
            int roll = r.nextInt(100);
            n.kind = roll < silentPercent ? Kind.SILENT : roll < silentPercent + legacyPercent ? Kind.LEGACY : Kind.NORMAL;
            nodes.add(n);
        }
        nodes.get(0).kind = legacyBootstrap ? Kind.LEGACY : Kind.NORMAL;
        nodes.forEach(FakeNode::start);
    }

    List<InetSocketAddress> bootstrap() {
        return List.of(nodes.get(0).address());
    }

    int nodesStoring(byte[] target) {
        String key = HexFormat.of().formatHex(target);
        return (int) nodes.stream().filter(n -> n.store.containsKey(key)).count();
    }

    @Override
    public void close() {
        nodes.forEach(n -> n.socket.close());
    }

    final class FakeNode {
        final byte[] id = Keys.randomBytes(20);
        final DatagramSocket socket;
        final Map<String, MutableItem> store = new ConcurrentHashMap<>();
        /** When set, this node answers every get with this (possibly forged) item. */
        volatile MutableItem liar;
        volatile Kind kind = Kind.NORMAL;

        FakeNode() throws Exception {
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        }

        InetSocketAddress address() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), socket.getLocalPort());
        }

        void start() {
            Thread t = new Thread(this::loop, "fake-dht");
            t.setDaemon(true);
            t.start();
        }

        private byte[] token(InetSocketAddress who) {
            return Arrays.copyOf(Keys.sha1(id, who.toString().getBytes()), 8);
        }

        /** Like a real routing table, each node only knows a random slice of the network. */
        private List<FakeNode> view;

        private synchronized List<FakeNode> view() {
            if (view == null) {
                List<FakeNode> all = new ArrayList<>(nodes);
                java.util.Collections.shuffle(all, new java.util.Random(java.util.Arrays.hashCode(id)));
                view = all.subList(0, Math.max(8, all.size() * 2 / 3));
            }
            return view;
        }

        private byte[] closestNodes(byte[] target) {
            List<FakeNode> sorted = new ArrayList<>(view());
            sorted.sort((a, b) -> DhtNode.compareDistance(target, a.id, b.id));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (FakeNode n : sorted.subList(0, Math.min(8, sorted.size()))) {
                out.writeBytes(n.id);
                out.writeBytes(new byte[]{127, 0, 0, 1});
                int port = n.socket.getLocalPort();
                out.write(port >> 8);
                out.write(port);
            }
            return out.toByteArray();
        }

        private void loop() {
            byte[] buf = new byte[4096];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    Object msg = Bencode.decode(p.getData(), 0, p.getLength());
                    InetSocketAddress from = (InetSocketAddress) p.getSocketAddress();
                    String q = Bencode.str(msg, "q");
                    Map<String, Object> a = Bencode.dict(msg, "a");
                    Map<String, Object> r = new HashMap<>();
                    r.put("id", id);
                    Object reply;
                    if (kind == Kind.SILENT) continue;
                    if (kind == Kind.LEGACY && !"find_node".equals(q)) {
                        reply = Map.of("t", Bencode.bytes(msg, "t"), "y", "e", "e", List.of(204L, "Method Unknown"));
                    } else if ("find_node".equals(q)) {
                        r.put("nodes", closestNodes(Bencode.bytes(a, "target")));
                        reply = Map.of("t", Bencode.bytes(msg, "t"), "y", "r", "r", r);
                    } else if ("get".equals(q)) {
                        byte[] target = Bencode.bytes(a, "target");
                        r.put("token", token(from));
                        r.put("nodes", closestNodes(target));
                        MutableItem item = liar != null ? liar : store.get(HexFormat.of().formatHex(target));
                        if (item != null) {
                            r.put("k", item.publicKey());
                            r.put("seq", item.seq());
                            r.put("sig", item.signature());
                            r.put("v", item.value());
                        }
                        reply = Map.of("t", Bencode.bytes(msg, "t"), "y", "r", "r", r);
                    } else if ("put".equals(q)) {
                        byte[] salt = Bencode.bytes(a, "salt");
                        MutableItem item = new MutableItem(Bencode.bytes(a, "k"), salt == null ? new byte[0] : salt,
                                Bencode.integer(a, "seq"), Bencode.bytes(a, "v"), Bencode.bytes(a, "sig"));
                        String key = HexFormat.of().formatHex(item.target());
                        MutableItem old = store.get(key);
                        if (!Arrays.equals(Bencode.bytes(a, "token"), token(from))) {
                            reply = Map.of("t", Bencode.bytes(msg, "t"), "y", "e", "e", List.of(203L, "bad token"));
                        } else if (!item.verify()) {
                            reply = Map.of("t", Bencode.bytes(msg, "t"), "y", "e", "e", List.of(206L, "bad sig"));
                        } else if (old != null && old.seq() >= item.seq()) {
                            reply = Map.of("t", Bencode.bytes(msg, "t"), "y", "e", "e", List.of(302L, "old seq"));
                        } else {
                            store.put(key, item);
                            reply = Map.of("t", Bencode.bytes(msg, "t"), "y", "r", "r", r);
                        }
                    } else {
                        continue;
                    }
                    byte[] out = Bencode.encode(reply);
                    socket.send(new DatagramPacket(out, out.length, from));
                } catch (Exception e) {
                    // closed or junk
                }
            }
        }
    }
}
