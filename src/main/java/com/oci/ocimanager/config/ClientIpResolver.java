package com.oci.ocimanager.config;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 取客户端真实 IP。默认只认 TCP 对端地址。
 * 只有显式打开 app.trust-forwarded-for，并且对端属于本机回环或显式配置的受信任反向代理网段 (app.trusted-proxies) 时，
 * 才按从右向左顺序剥离受信任代理节点，提取最右侧的首个非受信任合法客户端 IP。
 * 全程采用零 DNS 解析的纯字面量 IPv4/IPv6 校验，防止伪造头注入或反向 DNS 阻塞。
 */
public final class ClientIpResolver {

    private static final List<CidrBlock> LOOPBACK_CIDRS = List.of(
            parseCidrOrNull("127.0.0.0/8"),
            parseCidrOrNull("::1/128")
    );

    private ClientIpResolver() {}

    public static String resolve(HttpServletRequest request) {
        return resolve(request, false, null);
    }

    public static String resolve(HttpServletRequest request, boolean trustForwarded) {
        return resolve(request, trustForwarded, null);
    }

    public static String resolve(HttpServletRequest request, boolean trustForwarded, String trustedProxiesCsv) {
        if (request == null) return "UNKNOWN";
        byte[] remoteBytes = parseIpLiteral(request.getRemoteAddr());
        String remote = sanitizeIpLiteral(request.getRemoteAddr());
        if (remote == null) remote = "UNKNOWN";
        if (!trustForwarded || remoteBytes == null) {
            return remote;
        }

        List<CidrBlock> trustedCidrs = buildTrustedCidrs(trustedProxiesCsv);
        if (!matchesAny(remoteBytes, trustedCidrs)) {
            return remote;
        }

        String forwarded = combineHeaders(request, "X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = sanitizeIpLiteral(parts[i]);
                if (candidate == null) {
                    // 遇到非法伪造节段立即停止向左回溯，防止绕过可信跳边界读取客户端伪造的前缀
                    break;
                }
                byte[] candidateBytes = parseIpLiteral(candidate);
                if (candidateBytes != null && !matchesAny(candidateBytes, trustedCidrs)) {
                    return candidate;
                }
            }
        }

        String realIp = sanitizeIpLiteral(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            byte[] realBytes = parseIpLiteral(realIp);
            if (realBytes != null && !matchesAny(realBytes, trustedCidrs)) {
                return realIp;
            }
        }
        return remote;
    }

    private static List<CidrBlock> buildTrustedCidrs(String trustedProxiesCsv) {
        if (trustedProxiesCsv == null || trustedProxiesCsv.isBlank()) {
            return LOOPBACK_CIDRS;
        }
        List<CidrBlock> result = new ArrayList<>(LOOPBACK_CIDRS);
        for (String raw : trustedProxiesCsv.split("[,;\\s]+")) {
            if (raw == null || raw.isBlank()) continue;
            CidrBlock cidr = parseCidrOrNull(raw.trim());
            if (cidr != null) {
                result.add(cidr);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean matchesAny(byte[] addr, List<CidrBlock> cidrs) {
        for (CidrBlock cidr : cidrs) {
            if (cidr != null && cidr.matches(addr)) {
                return true;
            }
        }
        return false;
    }

    static String sanitizeIpLiteral(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "unknown".equalsIgnoreCase(trimmed)) return null;
        // 处理带方括号的 IPv6（如 [2001:db8::1] 或 [2001:db8::1]:443）
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close <= 1) return null;
            trimmed = trimmed.substring(1, close);
        } else if (trimmed.indexOf(':') >= 0 && trimmed.indexOf(':') == trimmed.lastIndexOf(':') && trimmed.indexOf('.') >= 0) {
            // IPv4:port 形态（如 203.0.113.5:54321）
            trimmed = trimmed.substring(0, trimmed.indexOf(':'));
        }
        if (trimmed.length() > 45) return null;
        byte[] parsed = parseIpLiteral(trimmed);
        return parsed != null ? normalizeFormattedIp(trimmed, parsed) : null;
    }

    private static String combineHeaders(HttpServletRequest request, String headerName) {
        java.util.Enumeration<String> values = request.getHeaders(headerName);
        if (values == null || !values.hasMoreElements()) {
            return request.getHeader(headerName);
        }
        StringBuilder sb = new StringBuilder();
        while (values.hasMoreElements()) {
            String v = values.nextElement();
            if (v != null && !v.isBlank()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(v);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String normalizeFormattedIp(String original, byte[] bytes) {
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "." + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
        }
        if (bytes.length == 16) {
            return formatCanonicalIpv6(bytes);
        }
        return original.toLowerCase(java.util.Locale.ROOT);
    }

    private static String formatCanonicalIpv6(byte[] bytes) {
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF);
        }
        int bestStart = -1;
        int bestLen = 0;
        int curStart = -1;
        int curLen = 0;
        for (int i = 0; i < 8; i++) {
            if (groups[i] == 0) {
                if (curStart < 0) curStart = i;
                curLen++;
                if (curLen > bestLen && curLen >= 2) {
                    bestStart = curStart;
                    bestLen = curLen;
                }
            } else {
                curStart = -1;
                curLen = 0;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (bestStart >= 0 && i >= bestStart && i < bestStart + bestLen) {
                if (i == bestStart) {
                    sb.append("::");
                }
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(Integer.toHexString(groups[i]));
        }
        return sb.toString();
    }

    /**
     * 零 DNS 严格解析 IPv4 或 IPv6 字面量；不合法一律返回 null。
     */
    static byte[] parseIpLiteral(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 45) return null;
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.indexOf(':') >= 0) {
            return parseIpv6Literal(s);
        }
        return parseIpv4Literal(s);
    }

    private static byte[] parseIpv4Literal(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) return null;
            if (p.length() > 1 && p.charAt(0) == '0') return null;
            int val = 0;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') return null;
                val = val * 10 + (c - '0');
            }
            if (val < 0 || val > 255) return null;
            out[i] = (byte) val;
        }
        return out;
    }

    private static byte[] parseIpv6Literal(String s) {
        if (s.indexOf('%') >= 0) return null; // 拒绝 zone index
        String ipv4Tail = null;
        int lastColon = s.lastIndexOf(':');
        if (lastColon >= 0 && s.indexOf('.', lastColon) > 0) {
            ipv4Tail = s.substring(lastColon + 1);
            s = s.substring(0, lastColon + 1) + "0:0";
        }

        int doubleColon = s.indexOf("::");
        if (doubleColon >= 0 && s.indexOf("::", doubleColon + 2) >= 0) {
            return null;
        }

        String[] headParts;
        String[] tailParts;
        if (doubleColon >= 0) {
            String left = s.substring(0, doubleColon);
            String right = s.substring(doubleColon + 2);
            headParts = left.isEmpty() ? new String[0] : left.split(":", -1);
            tailParts = right.isEmpty() ? new String[0] : right.split(":", -1);
            if (headParts.length + tailParts.length >= 8) return null;
        } else {
            headParts = s.split(":", -1);
            tailParts = new String[0];
            if (headParts.length != 8) return null;
        }

        byte[] out = new byte[16];
        int pos = 0;
        for (String group : headParts) {
            int val = parseHexHextet(group);
            if (val < 0) return null;
            out[pos++] = (byte) ((val >>> 8) & 0xFF);
            out[pos++] = (byte) (val & 0xFF);
        }
        int zeroGroups = 8 - (headParts.length + tailParts.length);
        pos += zeroGroups * 2;
        for (String group : tailParts) {
            int val = parseHexHextet(group);
            if (val < 0) return null;
            out[pos++] = (byte) ((val >>> 8) & 0xFF);
            out[pos++] = (byte) (val & 0xFF);
        }

        if (ipv4Tail != null) {
            byte[] v4 = parseIpv4Literal(ipv4Tail);
            if (v4 == null) return null;
            System.arraycopy(v4, 0, out, 12, 4);
            // 若为 IPv4-mapped IPv6 (::ffff:a.b.c.d)，归一为 4 字节 IPv4 以便统一匹配 IPv4 CIDR
            boolean isV4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (out[i] != 0) { isV4Mapped = false; break; }
            }
            if (isV4Mapped && (out[10] & 0xFF) == 0xFF && (out[11] & 0xFF) == 0xFF) {
                return v4;
            }
        }
        return out;
    }

    private static int parseHexHextet(String group) {
        if (group == null || group.isEmpty() || group.length() > 4) return -1;
        int val = 0;
        for (int i = 0; i < group.length(); i++) {
            int d = Character.digit(group.charAt(i), 16);
            if (d < 0) return -1;
            val = (val << 4) | d;
        }
        return val;
    }

    private static CidrBlock parseCidrOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        int slash = trimmed.indexOf('/');
        String ipPart = slash >= 0 ? trimmed.substring(0, slash) : trimmed;
        byte[] network = parseIpLiteral(ipPart);
        if (network == null) return null;
        int maxBits = network.length * 8;
        int prefixBits = maxBits;
        if (slash >= 0) {
            try {
                prefixBits = Integer.parseInt(trimmed.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (prefixBits < 0 || prefixBits > maxBits) return null;
        }
        return new CidrBlock(network, prefixBits);
    }

    private record CidrBlock(byte[] network, int prefixBits) {
        boolean matches(byte[] candidate) {
            if (candidate == null || candidate.length != network.length) return false;
            int fullBytes = prefixBits / 8;
            int remBits = prefixBits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) return false;
            }
            if (remBits > 0) {
                int mask = (0xFF << (8 - remBits)) & 0xFF;
                if ((candidate[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
