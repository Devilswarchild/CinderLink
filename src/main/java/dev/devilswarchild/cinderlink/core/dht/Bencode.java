package dev.devilswarchild.cinderlink.core.dht;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bencoding (the BitTorrent wire format). Strings are byte[], integers are Long, lists are List, dictionaries
 * are Map&lt;String, Object&gt; with keys sorted on encode. The decoder treats input as hostile: it limits size,
 * nesting depth and number lengths, and rejects anything malformed.
 */
public final class Bencode {
    static final int MAX_DEPTH = 16;
    static final int MAX_INPUT = 4096;

    private Bencode() {}

    public static class DecodeException extends Exception {
        DecodeException(String m) {
            super(m);
        }
    }

    public static byte[] encode(Object o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, o);
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void write(ByteArrayOutputStream out, Object o) {
        if (o instanceof byte[] b) {
            out.writeBytes((b.length + ":").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(b);
        } else if (o instanceof String s) {
            write(out, s.getBytes(StandardCharsets.UTF_8));
        } else if (o instanceof Long || o instanceof Integer) {
            out.writeBytes(("i" + ((Number) o).longValue() + "e").getBytes(StandardCharsets.US_ASCII));
        } else if (o instanceof List<?> l) {
            out.write('l');
            for (Object e : l) write(out, e);
            out.write('e');
        } else if (o instanceof Map<?, ?> m) {
            out.write('d');
            // Keys must be sorted as raw bytes; our keys are ASCII so String order matches.
            for (Map.Entry<String, Object> e : new TreeMap<>((Map<String, Object>) m).entrySet()) {
                write(out, e.getKey());
                write(out, e.getValue());
            }
            out.write('e');
        } else {
            throw new IllegalArgumentException("can't bencode " + (o == null ? "null" : o.getClass()));
        }
    }

    public static Object decode(byte[] data) throws DecodeException {
        return decode(data, 0, data.length);
    }

    public static Object decode(byte[] data, int off, int len) throws DecodeException {
        if (len > MAX_INPUT) throw new DecodeException("too large");
        Reader r = new Reader(Arrays.copyOfRange(data, off, off + len));
        Object o = r.value(0);
        if (r.pos != r.buf.length) throw new DecodeException("trailing data");
        return o;
    }

    private static final class Reader {
        final byte[] buf;
        int pos;

        Reader(byte[] buf) {
            this.buf = buf;
        }

        Object value(int depth) throws DecodeException {
            if (depth > MAX_DEPTH) throw new DecodeException("too deep");
            if (pos >= buf.length) throw new DecodeException("truncated");
            byte c = buf[pos];
            if (c == 'i') {
                pos++;
                long v = number('e');
                return v;
            } else if (c == 'l') {
                pos++;
                List<Object> list = new ArrayList<>();
                while (peek() != 'e') list.add(value(depth + 1));
                pos++;
                return list;
            } else if (c == 'd') {
                pos++;
                Map<String, Object> map = new TreeMap<>();
                while (peek() != 'e') {
                    String k = new String(string(), StandardCharsets.ISO_8859_1);
                    map.put(k, value(depth + 1));
                }
                pos++;
                return map;
            } else if (c >= '0' && c <= '9') {
                return string();
            }
            throw new DecodeException("bad token");
        }

        byte peek() throws DecodeException {
            if (pos >= buf.length) throw new DecodeException("truncated");
            return buf[pos];
        }

        byte[] string() throws DecodeException {
            long n = number(':');
            if (n < 0 || n > buf.length - pos) throw new DecodeException("bad string length");
            byte[] s = Arrays.copyOfRange(buf, pos, pos + (int) n);
            pos += (int) n;
            return s;
        }

        long number(char end) throws DecodeException {
            int start = pos;
            while (pos < buf.length && buf[pos] != end) {
                byte b = buf[pos];
                if (!((b >= '0' && b <= '9') || (b == '-' && pos == start))) throw new DecodeException("bad number");
                if (pos - start > 19) throw new DecodeException("number too long");
                pos++;
            }
            if (pos >= buf.length || pos == start) throw new DecodeException("bad number");
            String s = new String(buf, start, pos - start, StandardCharsets.US_ASCII);
            pos++;
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw new DecodeException("bad number");
            }
        }
    }

    // Typed accessors for decoded dictionaries; return null on missing or wrong type.

    public static Map<String, Object> dict(Object m, String key) {
        return m instanceof Map<?, ?> mm && mm.get(key) instanceof Map<?, ?> d ? cast(d) : null;
    }

    public static byte[] bytes(Object m, String key) {
        return m instanceof Map<?, ?> mm && mm.get(key) instanceof byte[] b ? b : null;
    }

    public static Long integer(Object m, String key) {
        return m instanceof Map<?, ?> mm && mm.get(key) instanceof Long l ? l : null;
    }

    public static String str(Object m, String key) {
        byte[] b = bytes(m, key);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
