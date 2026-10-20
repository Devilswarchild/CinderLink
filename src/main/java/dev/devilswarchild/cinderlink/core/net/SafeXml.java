package dev.devilswarchild.cinderlink.core.net;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * XML parsing locked down against XXE / entity-expansion attacks. Router replies are untrusted input:
 * anything on the LAN can answer a discovery request, so DOCTYPEs and external entities are refused outright.
 */
final class SafeXml {
    private SafeXml() {}

    static Document parse(byte[] xml) throws UpnpException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new UpnpException("Router sent XML we couldn't read", e);
        }
    }

    /** All descendant elements with this local name, ignoring namespace prefixes. */
    static List<Element> all(Node root, String localName) {
        List<Element> out = new ArrayList<>();
        collect(root, localName, out);
        return out;
    }

    private static void collect(Node n, String localName, List<Element> out) {
        NodeList kids = n.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k instanceof Element e) {
                String name = e.getLocalName() != null ? e.getLocalName() : e.getTagName();
                if (localName.equals(name)) out.add(e);
                collect(e, localName, out);
            }
        }
    }

    /** Text of the first direct-or-nested child with this name, trimmed, or null. */
    static String text(Node root, String localName) {
        List<Element> found = all(root, localName);
        return found.isEmpty() ? null : found.get(0).getTextContent().trim();
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
