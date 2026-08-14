package com.onroonlink.app;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

final class StunProbe {
    static final class Result {
        final String candidate;
        final boolean stable;
        final int replies;
        Result(String candidate, boolean stable, int replies) {
            this.candidate = candidate;
            this.stable = stable;
            this.replies = replies;
        }
    }

    private static final int MAGIC = 0x2112A442;

    static Result gather(int localPort) throws Exception {
        List<String> got = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", localPort));
            socket.setSoTimeout(2300);
            String a = binding(socket, "stun.cloudflare.com", 3478);
            if (a != null) got.add(a);
            String b = binding(socket, "stun.cloudflare.com", 53);
            if (b != null) got.add(b);
        }
        if (got.isEmpty()) throw new IllegalStateException("STUN 응답을 받지 못했습니다.");
        boolean stable = true;
        for (int i = 1; i < got.size(); i++) {
            if (!got.get(i).equals(got.get(0))) { stable = false; break; }
        }
        return new Result(got.get(0), stable, got.size());
    }

    private static String binding(DatagramSocket socket, String host, int port) {
        try {
            InetAddress target = null;
            for (InetAddress ip : InetAddress.getAllByName(host)) {
                if (ip instanceof Inet4Address) { target = ip; break; }
            }
            if (target == null) return null;
            byte[] tx = new byte[12];
            new SecureRandom().nextBytes(tx);
            ByteBuffer req = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
            req.putShort((short)0x0001);
            req.putShort((short)0);
            req.putInt(MAGIC);
            req.put(tx);
            byte[] out = req.array();
            socket.send(new DatagramPacket(out, out.length, target, port));

            byte[] buf = new byte[1500];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            long deadline = System.currentTimeMillis() + 2200L;
            while (System.currentTimeMillis() < deadline) {
                socket.receive(in);
                String mapped = parse(buf, in.getLength(), tx);
                if (mapped != null) return mapped;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static String parse(byte[] b, int n, byte[] tx) {
        if (n < 20) return null;
        int type = u16(b, 0);
        int len = u16(b, 2);
        int magic = i32(b, 4);
        if (type != 0x0101 || magic != MAGIC) return null;
        for (int i = 0; i < 12; i++) if (b[8+i] != tx[i]) return null;
        int limit = Math.min(n, 20 + len);
        int off = 20;
        while (off + 4 <= limit) {
            int at = u16(b, off);
            int al = u16(b, off + 2);
            int v = off + 4;
            if (v + al > limit) break;
            if ((at == 0x0020 || at == 0x0001) && al >= 8 && (b[v+1] & 0xff) == 0x01) {
                int p = u16(b, v + 2);
                int a0 = b[v+4] & 0xff, a1 = b[v+5] & 0xff, a2 = b[v+6] & 0xff, a3 = b[v+7] & 0xff;
                if (at == 0x0020) {
                    p ^= 0x2112;
                    a0 ^= 0x21; a1 ^= 0x12; a2 ^= 0xA4; a3 ^= 0x42;
                }
                return a0 + "." + a1 + "." + a2 + "." + a3 + ":" + p;
            }
            off = v + al;
            int rem = off & 3;
            if (rem != 0) off += 4 - rem;
        }
        return null;
    }

    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o+1] & 0xff); }
    private static int i32(byte[] b, int o) { return ((b[o] & 0xff) << 24) | ((b[o+1] & 0xff) << 16) | ((b[o+2] & 0xff) << 8) | (b[o+3] & 0xff); }
}
