package ru.evolenta.pgs.worker.trade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Offline HTTP(S) syntax checks from the published validator; never performs DNS or I/O. */
final class TradeUrl {
    private TradeUrl() {}
    static boolean valid(String text) {
        var match = Pattern.compile("(?i)^https?://([^/?#]*)(.*)$").matcher(text);
        if (!match.matches()) return false;
        String authority = match.group(1), tail = match.group(2);
        int hash = tail.indexOf('#');
        String fragment = hash >= 0 ? tail.substring(hash + 1) : "";
        String before = hash >= 0 ? tail.substring(0, hash) : tail;
        int question = before.indexOf('?');
        String query = question >= 0 ? before.substring(question + 1) : "";
        String path = question >= 0 ? before.substring(0, question) : before;
        if (!component(path, "/;:@&=+$,") || !component(query, "/?;:@&=+$,[]") || !component(fragment, "/?;:@&=+$,[]")) return false;
        int at = authority.indexOf('@');
        if (at >= 0) {
            if (authority.indexOf('@', at + 1) >= 0 || !component(authority.substring(0, at), ";:&=+$,")) return false;
            authority = authority.substring(at + 1);
        }
        if (authority.isEmpty()) return false;
        String host, port = "";
        boolean ipv6 = authority.startsWith("[");
        if (ipv6) {
            int end = authority.indexOf(']');
            if (end < 0) return false;
            host = authority.substring(1, end);
            String rest = authority.substring(end + 1);
            if (!rest.isEmpty() && !rest.startsWith(":")) return false;
            port = rest.isEmpty() ? "" : rest.substring(1);
        } else {
            int colon = authority.indexOf(':');
            host = colon >= 0 ? authority.substring(0, colon) : authority;
            if (colon >= 0) port = authority.substring(colon + 1);
        }
        if (!port.isEmpty()) {
            if (!port.matches("[0-9]+")) return false;
            try { Integer.parseInt(port); } catch (NumberFormatException error) { return false; }
        }
        if (ipv6) return ipv6(host);
        if (ipv4(host)) return true;
        String domain = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        var labels = domain.split("\\.", -1);
        if (!Arrays.stream(labels).allMatch(label -> label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))) return false;
        return labels.length == 1 || labels[labels.length - 1].matches("[A-Za-z][A-Za-z0-9-]*");
    }
    private static boolean component(String value, String extra) {
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '%') {
                if (index + 2 >= value.length() || !value.substring(index + 1, index + 3).matches("[0-9A-Fa-f]{2}")) return false;
                index += 2;
            } else if (ch < 128) {
                if (!(Character.isLetterOrDigit(ch) || ("-_.!~*'()" + extra).indexOf(ch) >= 0)) return false;
            } else if (Character.isSpaceChar(ch) || Character.isISOControl(ch)) return false;
        }
        return true;
    }
    private static boolean ipv4(String value) {
        var parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]+")) return false;
            try { if (Integer.parseInt(part) > 255) return false; } catch (NumberFormatException error) { return false; }
        }
        return true;
    }
    private static boolean ipv6(String host) {
        int zone = host.indexOf('%');
        if (zone >= 0) {
            if (!host.substring(zone + 1).matches("[A-Za-z0-9]+")) return false;
            host = host.substring(0, zone);
        }
        if (!host.contains(":")) return false;
        int compressed = host.indexOf("::");
        if (compressed >= 0 && host.indexOf("::", compressed + 2) >= 0) return false;
        String simple = compressed >= 0 ? host.substring(0, compressed) + ':' + host.substring(compressed + 2) : host;
        var groups = new ArrayList<>(Arrays.asList(simple.split(":", -1)));
        if (compressed >= 0 && !groups.isEmpty() && groups.getFirst().isEmpty()) groups.removeFirst();
        if (compressed >= 0 && !groups.isEmpty() && groups.getLast().isEmpty()) groups.removeLast();
        int units = 0;
        for (int index = 0; index < groups.size(); index++) {
            String group = groups.get(index);
            if (group.contains(".")) { if (index != groups.size() - 1 || !ipv4(group)) return false; units += 2; }
            else { if (!group.matches("[0-9a-fA-F]{1,4}")) return false; units++; }
        }
        return compressed >= 0 ? units < 8 : units == 8;
    }
}
