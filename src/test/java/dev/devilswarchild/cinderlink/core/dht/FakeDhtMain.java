package dev.devilswarchild.cinderlink.core.dht;

/** Runs a private 20-node test DHT on localhost and prints its bootstrap address. For in-game testing. */
public final class FakeDhtMain {
    public static void main(String[] args) throws Exception {
        FakeDhtNetwork net = new FakeDhtNetwork(20);
        var b = net.bootstrap().get(0);
        System.out.println("BOOTSTRAP 127.0.0.1:" + b.getPort());
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
