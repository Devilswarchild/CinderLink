package dev.devilswarchild.cinderlink.core.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * One Internet Gateway Device (the router) and the WAN connection service we talk to.
 * All requests stay on the local network and bypass any configured proxy.
 */
public final class UpnpGateway {
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int TIMEOUT_MS = 4000;
    private static final List<String> PREFERRED_SERVICES = List.of(
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANPPPConnection:1");

    private final URI controlUrl;
    private final String serviceType;
    private final InetAddress localAddress;

    UpnpGateway(URI controlUrl, String serviceType, InetAddress localAddress) {
        this.controlUrl = controlUrl;
        this.serviceType = serviceType;
        this.localAddress = localAddress;
    }

    /** Our own LAN address as seen by the router; this is where the mapping points. */
    public InetAddress localAddress() {
        return localAddress;
    }

    public String serviceType() {
        return serviceType;
    }

    /** Fetches and parses the device description at {@code location} (from SSDP). */
    static UpnpGateway fromLocation(URI location, InetAddress localAddress) throws UpnpException {
        byte[] body = http("GET", location, null, null);
        return fromDescription(body, location, localAddress);
    }

    static UpnpGateway fromDescription(byte[] xml, URI location, InetAddress localAddress) throws UpnpException {
        Document doc = SafeXml.parse(xml);
        String urlBase = SafeXml.text(doc, "URLBase");
        URI base = location;
        if (urlBase != null && !urlBase.isBlank()) {
            try {
                base = URI.create(urlBase.trim());
            } catch (IllegalArgumentException ignored) {
                // keep location as base
            }
        }
        for (String wanted : PREFERRED_SERVICES) {
            for (Element svc : SafeXml.all(doc, "service")) {
                String type = SafeXml.text(svc, "serviceType");
                String control = SafeXml.text(svc, "controlURL");
                if (wanted.equals(type) && control != null && !control.isBlank()) {
                    URI controlUri = base.resolve(control.trim());
                    requireLocal(controlUri, location);
                    return new UpnpGateway(controlUri, type, localAddress);
                }
            }
        }
        throw new UpnpException(-1, "Router doesn't offer port mapping (no WAN connection service)");
    }

    /** The control URL must stay on the same LAN host that answered discovery. */
    private static void requireLocal(URI control, URI location) throws UpnpException {
        String scheme = control.getScheme();
        if (!"http".equalsIgnoreCase(scheme) || control.getHost() == null
                || !control.getHost().equalsIgnoreCase(location.getHost())) {
            throw new UpnpException(-1, "Router gave a control address on a different host; refusing it");
        }
    }

    public InetAddress externalAddress() throws UpnpException {
        Map<String, String> out = call("GetExternalIPAddress", Map.of());
        String ip = out.get("NewExternalIPAddress");
        if (ip == null || ip.isBlank() || !ip.matches("[0-9a-fA-F.:]+")) {
            throw new UpnpException(-1, "Router didn't report a public address");
        }
        try {
            return InetAddress.getByName(ip.trim());
        } catch (IOException e) {
            throw new UpnpException("Router reported an unreadable address", e);
        }
    }

    public void addPortMapping(int externalPort, int internalPort, String protocol, String description, int leaseSeconds)
            throws UpnpException {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("NewRemoteHost", "");
        args.put("NewExternalPort", Integer.toString(externalPort));
        args.put("NewProtocol", protocol);
        args.put("NewInternalPort", Integer.toString(internalPort));
        args.put("NewInternalClient", localAddress.getHostAddress());
        args.put("NewEnabled", "1");
        args.put("NewPortMappingDescription", description);
        args.put("NewLeaseDuration", Integer.toString(leaseSeconds));
        call("AddPortMapping", args);
    }

    public void deletePortMapping(int externalPort, String protocol) throws UpnpException {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("NewRemoteHost", "");
        args.put("NewExternalPort", Integer.toString(externalPort));
        args.put("NewProtocol", protocol);
        call("DeletePortMapping", args);
    }

    Map<String, String> call(String action, Map<String, String> args) throws UpnpException {
        StringBuilder body = new StringBuilder()
                .append("<?xml version=\"1.0\"?>")
                .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
                .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
                .append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">");
        for (Map.Entry<String, String> e : args.entrySet()) {
            body.append('<').append(e.getKey()).append('>')
                .append(SafeXml.escape(e.getValue()))
                .append("</").append(e.getKey()).append('>');
        }
        body.append("</u:").append(action).append("></s:Body></s:Envelope>");

        byte[] reply = http("POST", controlUrl, body.toString().getBytes(StandardCharsets.UTF_8),
                "\"" + serviceType + "#" + action + "\"");
        Document doc = SafeXml.parse(reply);
        String code = SafeXml.text(doc, "errorCode");
        if (code != null) {
            String desc = SafeXml.text(doc, "errorDescription");
            int parsed;
            try {
                parsed = Integer.parseInt(code.trim());
            } catch (NumberFormatException e) {
                parsed = -1;
            }
            throw new UpnpException(parsed, "Router refused " + action + ": " + (desc == null ? "error " + code : desc));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Element resp : SafeXml.all(doc, action + "Response")) {
            var kids = resp.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i) instanceof Element el) {
                    String name = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
                    out.put(name, el.getTextContent().trim());
                }
            }
        }
        return out;
    }

    /** Minimal HTTP on the LAN: no proxy, short timeouts, capped response size. 500 is read too (SOAP faults). */
    static byte[] http(String method, URI uri, byte[] body, String soapAction) throws UpnpException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                if (soapAction != null) c.setRequestProperty("SOAPAction", soapAction);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new UpnpException(-1, "Router replied with HTTP " + status + " and no body");
            try (in) {
                byte[] data = readCapped(in);
                if (status >= 400 && status != 500) {
                    throw new UpnpException(-1, "Router replied with HTTP " + status);
                }
                return data;
            }
        } catch (UpnpException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new UpnpException("Couldn't talk to the router", e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static byte[] readCapped(InputStream in) throws IOException, UpnpException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > MAX_RESPONSE_BYTES) throw new UpnpException(-1, "Router reply was too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
