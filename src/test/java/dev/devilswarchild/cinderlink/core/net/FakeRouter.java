package dev.devilswarchild.cinderlink.core.net;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/** A tiny in-process stand-in for a home router's UPnP HTTP endpoints. */
final class FakeRouter implements AutoCloseable {
    final HttpServer server;
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    volatile Function<String, String[]> controlHandler; // body -> {status, responseBody}
    volatile String description;

    FakeRouter(String serviceType) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        description = "<?xml version=\"1.0\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device>"
                + "<deviceList><device><serviceList><service>"
                + "<serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>"
                + "<controlURL>/l3f</controlURL></service><service>"
                + "<serviceType>" + serviceType + "</serviceType>"
                + "<controlURL>/ctl/IPConn</controlURL></service></serviceList></device></deviceList>"
                + "</device></root>";
        server.createContext("/desc.xml", ex -> reply(ex, 200, description));
        server.createContext("/ctl/IPConn", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(ex.getRequestHeaders().getFirst("SOAPAction") + " " + body);
            String[] r = controlHandler.apply(body);
            reply(ex, Integer.parseInt(r[0]), r[1]);
        });
        server.start();
    }

    URI location() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/desc.xml");
    }

    static String ok(String action, String inner) {
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<u:" + action + "Response xmlns:u=\"urn:x\">" + inner + "</u:" + action + "Response>"
                + "</s:Body></s:Envelope>";
    }

    static String fault(int code, String desc) {
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
                + "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + code + "</errorCode>"
                + "<errorDescription>" + desc + "</errorDescription></UPnPError></detail></s:Fault>"
                + "</s:Body></s:Envelope>";
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
