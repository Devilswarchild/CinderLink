package dev.devilswarchild.cinderlink.core.dht;

/** Lets tests in other packages spin up the fake DHT and get real clients pointed at it. */
public final class FakeDhtNetworkAccess implements AutoCloseable {
    private final FakeDhtNetwork net;

    public FakeDhtNetworkAccess(int nodes) throws Exception {
        net = new FakeDhtNetwork(nodes);
    }

    /** A realistic network: silent nodes, BEP 44-unaware nodes, and a find_node-only entry point. */
    public static FakeDhtNetworkAccess messy(int nodes) throws Exception {
        return new FakeDhtNetworkAccess(new FakeDhtNetwork(nodes, 33, 20, true));
    }

    private FakeDhtNetworkAccess(FakeDhtNetwork net) {
        this.net = net;
    }

    public DhtClient client() throws Exception {
        return new DhtClient(net.bootstrap(), true);
    }

    @Override
    public void close() {
        net.close();
    }
}
