package com.company.retail.gateway.ratelimit;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 客户端 IP 解析（限流计数键的来源）。
 *
 * <p><b>安全口径：防止「换一个 X-Forwarded-For 就能重置限流」。</b></p>
 * <ul>
 *   <li>直连来源<b>不</b>在可信代理网段内 → 一律取 TCP 直连地址，<b>忽略</b>转发头；</li>
 *   <li>直连来源命中可信代理网段（生产 = 边缘 Nginx）→ 才采信 X-Forwarded-For，
 *       取链中第一个<b>非可信</b>地址作为真实客户端；</li>
 *   <li>转发头里非 IP 字面量的内容（如主机名）一律忽略——避免触发 DNS 解析
 *       把网关线程拖进阻塞 I/O。</li>
 * </ul>
 */
public final class ClientIpResolver {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";
    static final String UNKNOWN = "unknown";

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(String trustedProxyCidrs) {
        this.trustedProxies = parseCidrs(trustedProxyCidrs);
    }

    public boolean hasTrustedProxies() {
        return !trustedProxies.isEmpty();
    }

    /**
     * 解析本次请求的客户端 IP。
     */
    public String resolve(ServerHttpRequest request) {
        String remote = remoteAddress(request);
        if (trustedProxies.isEmpty() || !isTrusted(remote)) {
            return remote;
        }
        String forwarded = request.getHeaders().getFirst(X_FORWARDED_FOR);
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        String firstValid = null;
        for (String part : forwarded.split(",")) {
            String candidate = parseLiteral(part);
            if (candidate == null) {
                continue;
            }
            if (firstValid == null) {
                firstValid = candidate;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return firstValid != null ? firstValid : remote;
    }

    private static String remoteAddress(ServerHttpRequest request) {
        if (request.getRemoteAddress() == null || request.getRemoteAddress().getAddress() == null) {
            return UNKNOWN;
        }
        String host = request.getRemoteAddress().getAddress().getHostAddress();
        if (host == null) {
            return UNKNOWN;
        }
        int zone = host.indexOf('%');
        return zone > 0 ? host.substring(0, zone) : host;
    }

    private boolean isTrusted(String ip) {
        byte[] addr = parseLiteralBytes(ip);
        if (addr == null) {
            return false;
        }
        for (Cidr cidr : trustedProxies) {
            if (cidr.matches(addr)) {
                return true;
            }
        }
        return false;
    }

    /** 解析 IP 字面量为规范化字符串；非字面量（主机名等）返回 null。 */
    private static String parseLiteral(String raw) {
        byte[] bytes = parseLiteralBytes(raw);
        if (bytes == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] parseLiteralBytes(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        int zone = value.indexOf('%');
        if (zone > 0) {
            value = value.substring(0, zone);
        }
        if (value.indexOf(':') >= 0) {
            if (!IPV6_LITERAL.matcher(value).matches()) {
                return null;
            }
            try {
                return InetAddress.getByName(value).getAddress();
            } catch (UnknownHostException | SecurityException e) {
                return null;
            }
        }
        if (!IPV4.matcher(value).matches()) {
            return null;
        }
        String[] parts = value.split("\\.");
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int octet;
            try {
                octet = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (octet < 0 || octet > 255) {
                return null;
            }
            out[i] = (byte) octet;
        }
        return out;
    }

    private static List<Cidr> parseCidrs(String raw) {
        List<Cidr> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("[,;\\s]+")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            String addrPart = item;
            Integer prefix = null;
            int slash = item.lastIndexOf('/');
            if (slash > 0) {
                addrPart = item.substring(0, slash);
                try {
                    prefix = Integer.parseInt(item.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            byte[] bytes = parseLiteralBytes(addrPart);
            if (bytes == null) {
                continue;
            }
            int maxBits = bytes.length * 8;
            int bits = prefix == null ? maxBits : prefix;
            if (bits < 0 || bits > maxBits) {
                continue;
            }
            result.add(new Cidr(bytes, bits));
        }
        return result;
    }

    /** 简易 CIDR：按前缀位比对。 */
    private static final class Cidr {
        private final byte[] network;
        private final int prefixBits;

        Cidr(byte[] network, int prefixBits) {
            this.network = network.clone();
            this.prefixBits = prefixBits;
        }

        boolean matches(byte[] addr) {
            if (addr == null || addr.length != network.length) {
                return false;
            }
            int bits = prefixBits;
            for (int i = 0; i < addr.length; i++) {
                if (bits <= 0) {
                    return true;
                }
                int take = Math.min(8, bits);
                int mask = (0xFF << (8 - take)) & 0xFF;
                if ((addr[i] & mask) != (network[i] & mask)) {
                    return false;
                }
                bits -= take;
            }
            return true;
        }
    }
}
