package dev.devilswarchild.cinderlink.core.dht;

/** Lets tests in other packages spin up the fake DHT and get real clients pointed at it. */
public final class FakeDhtNetworkAccess implements AutoCloseable {
    private final FakeDhtNetwork net;

    public FakeDhtNetworkAccess(int nodes) throws Exception {
        net = new FakeDhtNetwork(nodes);
    }

    public DhtClient client() throws Exception {
        return new DhtClient(net.bootstrap(), true);
    }

    @Override
    public void close() {
        net.close();
    }
}
