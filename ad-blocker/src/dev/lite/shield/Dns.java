package dev.lite.shield;

/**
 * Just enough IPv4, UDP and DNS to answer lookups on the tunnel: read the question out of a query
 * packet, build a "nothing here" answer for a blocked name, and wrap a real resolver's reply in an
 * IPv4/UDP packet addressed back to the app that asked.
 */
final class Dns {
    private Dns() {}

    /** A parsed query: where the DNS message sits in the packet and what it asks for. */
    static final class Query {
        int ipLen, dnsOff, dnsLen, qEnd, qtype;
        String name;
    }

    /** Null unless this is an IPv4 UDP packet to port 53 carrying one well-formed question. */
    static Query parse(byte[] p, int len) {
        if (len < 28 || (p[0] >> 4 & 0xF) != 4 || p[9] != 17) return null;
        int ihl = (p[0] & 0xF) * 4;
        int total = u16(p, 2);
        if (total > len || ihl + 8 > total) return null;
        if (u16(p, ihl + 2) != 53) return null;
        Query q = new Query();
        q.ipLen = total;
        q.dnsOff = ihl + 8;
        q.dnsLen = total - q.dnsOff;
        if (q.dnsLen < 12 || u16(p, q.dnsOff + 4) != 1) return null; // exactly one question
        StringBuilder sb = new StringBuilder();
        int i = q.dnsOff + 12;
        while (true) {
            if (i >= total) return null;
            int l = p[i] & 0xFF;
            if (l == 0) { i++; break; }
            if (l > 63 || i + 1 + l > total) return null;
            if (sb.length() > 0) sb.append('.');
            for (int k = 1; k <= l; k++) sb.append((char) (p[i + k] & 0xFF));
            i += 1 + l;
        }
        if (i + 4 > total) return null;
        q.qtype = u16(p, i);
        q.qEnd = i + 4;
        q.name = sb.toString();
        return q;
    }

    /** The answer for a blocked name: 0.0.0.0 (or ::) for address lookups, an empty answer otherwise. */
    static byte[] blockedReply(byte[] p, Query q) {
        int qLen = q.qEnd - q.dnsOff;
        boolean a = q.qtype == 1, aaaa = q.qtype == 28;
        int ansLen = a ? 16 : aaaa ? 28 : 0;
        byte[] dns = new byte[qLen + ansLen];
        System.arraycopy(p, q.dnsOff, dns, 0, qLen);
        dns[2] = (byte) (0x80 | (p[q.dnsOff + 2] & 0x79)); // response, same opcode, keep "recursion desired"
        dns[3] = (byte) 0x80;                               // recursion available, no error
        dns[6] = 0; dns[7] = (byte) (ansLen > 0 ? 1 : 0);   // answers
        dns[8] = 0; dns[9] = 0; dns[10] = 0; dns[11] = 0;   // no authority or extra records
        if (ansLen > 0) {
            int o = qLen;
            dns[o] = (byte) 0xC0; dns[o + 1] = 12;          // name: pointer to the question
            dns[o + 2] = 0; dns[o + 3] = (byte) q.qtype;
            dns[o + 4] = 0; dns[o + 5] = 1;                 // class IN
            dns[o + 6] = 0; dns[o + 7] = 0; dns[o + 8] = 0x01; dns[o + 9] = 0x2C; // TTL 300 s
            dns[o + 10] = 0; dns[o + 11] = (byte) (a ? 4 : 16); // address is all zeros
        }
        return wrap(p, dns, dns.length);
    }

    /** An IPv4/UDP packet carrying dns back to whoever sent the query in p (addresses and ports swapped). */
    static byte[] wrap(byte[] p, byte[] dns, int dnsLen) {
        int ihl = (p[0] & 0xF) * 4;
        int total = 28 + dnsLen;
        byte[] o = new byte[total];
        o[0] = 0x45;
        o[2] = (byte) (total >> 8); o[3] = (byte) total;
        o[6] = 0x40;                 // don't fragment
        o[8] = 64;                   // TTL
        o[9] = 17;                   // UDP
        System.arraycopy(p, 16, o, 12, 4); // from: the address the app asked
        System.arraycopy(p, 12, o, 16, 4); // to: the app
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += u16(o, i);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        sum = ~sum & 0xFFFF;
        o[10] = (byte) (sum >> 8); o[11] = (byte) sum;
        o[20] = p[ihl + 2]; o[21] = p[ihl + 3]; // source port 53
        o[22] = p[ihl];     o[23] = p[ihl + 1]; // the app's port
        int udpLen = 8 + dnsLen;
        o[24] = (byte) (udpLen >> 8); o[25] = (byte) udpLen;
        // UDP checksum 0: allowed for IPv4, and the packet never leaves the device
        System.arraycopy(dns, 0, o, 28, dnsLen);
        return o;
    }

    static int u16(byte[] b, int i) { return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF); }
}
