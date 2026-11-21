package com.hljjs.app;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 极简 SOCKS5 服务端：给 tun2socks 用。
 * 支持 CONNECT（TCP）和 UDP ASSOCIATE（DNS 等 UDP）。
 * 域名用本进程的系统 DNS 解析（bionic getaddrinfo 可用）。
 */
public class LocalProxy implements Runnable {
    private static final String TAG = "LocalProxy";
    public static final int PORT = 10800;
    private volatile boolean running = true;
    private ServerSocket server;

    @Override
    public void run() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", PORT));
            Log.i(TAG, "socks5 listening 127.0.0.1:" + PORT);
            while (running) {
                final Socket c = server.accept();
                new Thread(new Runnable() {
                    public void run() { handle(c); }
                }).start();
            }
        } catch (Throwable e) {
            Log.e(TAG, "socks5 error " + e);
        }
    }

    private void handle(Socket c) {
        try {
            c.setSoTimeout(20000);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            // 握手
            int ver = in.read();
            if (ver != 5) { c.close(); return; }
            int n = in.read();
            for (int i = 0; i < n; i++) in.read();
            out.write(new byte[]{5, 0});
            out.flush();
            // 请求
            int rv = in.read();
            int cmd = in.read();
            in.read(); // rsv
            int atyp = in.read();
            String host = null;
            byte[] ip = null;
            if (atyp == 1) {
                ip = new byte[4];
                readFully(in, ip);
                host = (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
            } else if (atyp == 3) {
                int len = in.read();
                byte[] h = new byte[len];
                readFully(in, h);
                host = new String(h, "UTF-8");
            } else if (atyp == 4) {
                byte[] ip6 = new byte[16];
                readFully(in, ip6);
                host = InetAddress.getByAddress(ip6).getHostAddress();
            } else { c.close(); return; }
            int port = (in.read() << 8) | in.read();
            if (rv != 5) { c.close(); return; }

            if (cmd == 1) { // CONNECT
                Socket target = new Socket();
                target.connect(new InetSocketAddress(host, port), 10000);
                out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                out.flush();
                c.setSoTimeout(0);
                pump(c, target);
            } else if (cmd == 3) { // UDP ASSOCIATE
                DatagramSocket udp = new DatagramSocket();
                int uport = udp.getLocalPort();
                out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, (byte) (uport >> 8), (byte) uport});
                out.flush();
                udpLoop(udp, c);
            } else {
                c.close();
            }
        } catch (Throwable e) {
            Log.w(TAG, "handle err " + e);
            try { c.close(); } catch (Throwable ignored) { }
        }
    }

    /** SOCKS5 UDP 转发循环：解析 10 字节头，转发到目标并回包 */
    private void udpLoop(DatagramSocket udp, Socket ctl) {
        try {
            byte[] buf = new byte[65535];
            DatagramPacket rp = new DatagramPacket(buf, buf.length);
            while (running && !ctl.isClosed()) {
                udp.setSoTimeout(60000);
                udp.receive(rp);
                byte[] data = new byte[rp.getLength()];
                System.arraycopy(rp.getData(), 0, data, 0, rp.getLength());
                if (data.length < 10) continue;
                int p = 3;
                int atyp = data[p++] & 0xff;
                String host;
                if (atyp == 1) {
                    host = (data[p] & 0xff) + "." + (data[p+1] & 0xff) + "." + (data[p+2] & 0xff) + "." + (data[p+3] & 0xff);
                    p += 4;
                } else if (atyp == 3) {
                    int len = data[p++] & 0xff;
                    host = new String(data, p, len, "UTF-8");
                    p += len;
                } else break;
                int port = ((data[p] & 0xff) << 8) | (data[p+1] & 0xff);
                p += 2;
                int payloadLen = data.length - p;
                if (payloadLen <= 0) continue;
                byte[] payload = new byte[payloadLen];
                System.arraycopy(data, p, payload, 0, payloadLen);

                DatagramSocket up = new DatagramSocket();
                protect(up);
                up.setSoTimeout(5000);
                InetAddress addr = InetAddress.getByName(host);
                up.send(new DatagramPacket(payload, payload.length, addr, port));
                byte[] rb = new byte[65535];
                DatagramPacket rr = new DatagramPacket(rb, rb.length);
                up.receive(rr);
                up.close();

                byte[] resp = new byte[10 + rr.getLength()];
                resp[0] = 0; resp[1] = 0; resp[2] = 0;
                resp[3] = 1;
                System.arraycopy(rr.getAddress().getAddress(), 0, resp, 4, 4);
                resp[8] = (byte) (port >> 8);
                resp[9] = (byte) port;
                System.arraycopy(rr.getData(), 0, resp, 10, rr.getLength());
                udp.send(new DatagramPacket(resp, resp.length, rp.getAddress(), rp.getPort()));
            }
        } catch (Throwable e) {
            Log.w(TAG, "udp loop err " + e);
        }
        try { udp.close(); } catch (Throwable ignored) { }
    }

    /** protect 由 VpnDnsService 提供 */
    private static interface Protector { }
    private java.util.function.Consumer<DatagramSocket> protectHook;

    private void protect(DatagramSocket s) {
        if (protectHook != null) protectHook.accept(s);
    }

    public void setProtectHook(java.util.function.Consumer<DatagramSocket> hook) {
        this.protectHook = hook;
    }

    private static void readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n <= 0) throw new Exception("eof");
            off += n;
        }
    }

    private void pump(final Socket a, final Socket b) {
        new Thread(new Runnable() { public void run() { copy(a, b); } }).start();
        copy(b, a);
    }

    private void copy(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable ignored) { }
        try { from.close(); } catch (Throwable ignored) { }
        try { to.close(); } catch (Throwable ignored) { }
    }

    public ServerSocket serverSocket() { return server; }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
    }
}
