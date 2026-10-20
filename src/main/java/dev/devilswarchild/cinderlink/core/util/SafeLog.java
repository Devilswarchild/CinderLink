package dev.devilswarchild.cinderlink.core.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts anything sensitive before it reaches latest.log, since players post logs publicly.
 * Every log line that could contain network or identity data must go through {@link #redact}.
 */
public final class SafeLog {
    // Bracketed IPv6 (optionally with port) or a bare run of hex/colon/dot characters.
    private static final Pattern IPV6_CANDIDATE =
            Pattern.compile("\\[([0-9A-Fa-f:.%]+)\\](?::\\d{1,5})?|(?<![0-9A-Za-z:])([0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*)(?![0-9A-Za-z:])");
    private static final Pattern IPV4 =
            Pattern.compile("(?<![0-9.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?(?![0-9.])");
    private static final Pattern LONG_HEX = Pattern.compile("\\b[0-9a-fA-F]{32,}\\b");
    private static final Pattern HEX_GROUP = Pattern.compile("[0-9A-Fa-f]{1,4}");

    private SafeLog() {}

    public static String redact(String message) {
        if (message == null) return "null";
        String out = LONG_HEX.matcher(message).replaceAll("<key>");
        Matcher m = IPV6_CANDIDATE.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String addr = m.group(1) != null ? m.group(1) : m.group(2);
            m.appendReplacement(sb, isIpv6(addr) ? "<ip6>" : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(sb);
        return IPV4.matcher(sb.toString()).replaceAll("<ip>");
    }

    /** True for full (8 groups) or compressed ("::") IPv6, incl. an embedded IPv4 tail. Rejects times like 12:30:45. */
    static boolean isIpv6(String s) {
        int zone = s.indexOf('%');
        if (zone >= 0) s = s.substring(0, zone);
        int doubles = s.split("::", -1).length - 1;
        if (doubles > 1) return false;
        String[] parts = s.replace("::", ":X:").split(":", -1);
        int groups = 0;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.equals("X")) continue;
            if (i == parts.length - 1 && p.contains(".")) {
                if (!p.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")) return false;
                groups += 2;
            } else if (HEX_GROUP.matcher(p).matches()) {
                groups++;
            } else {
                return false;
            }
        }
        return doubles == 1 ? groups <= 7 : groups == 8;
    }
}
