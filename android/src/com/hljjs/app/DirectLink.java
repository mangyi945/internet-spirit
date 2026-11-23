package com.hljjs.app;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 原生 TCP 直连通道。
 *
 * 为什么需要它：
 *   WebRTC 每次连接都必须交换一次 SDP（协议规定），哪怕你已经知道对方的 IP。
 *   而 TCP 只要知道地址就能连 —— 这才是"只交换地址"能做到的事。
 *
 * 协议（极简，行分隔文本）：
 *   连上后双方各发一行  HI|<自己的ID>
 *   之后每行一条消息  MSG|<文本>
 *   心跳              PING / PONG
 */
public class DirectLink {

    public interface Sink {
        /** 事件以 JSON 字符串回调给网页层 */
        void onEvent(String json);
        String myId();
    }

    public static final int DEFAULT_PORT = 8643;

    private final Sink sink;
    private ServerSocket server;
    private Thread acceptThread;
    private final List<Peer> peers = new ArrayList<>();
    private volatile boolean running = false;
    private int port = DEFAULT_PORT;

    public DirectLink(Sink sink) {
        this.sink = sink;
    }

    public static class Peer {
        Socket socket;
        PrintWriter out;
        String peerId = "";
        String addr = "";
    }

    /* ---------- 监听 ---------- */
    public synchronized String start() {
        if (running) return "已经在监听 " + port;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port), 20);
            running = true;
            acceptThread = new Thread(new Runnable() {
                @Override public void run() {
                    while (running) {
                        try {
                            final Socket s = server.accept();
                            new Thread(new Runnable() {
                                @Override public void run() { handle(s, true); }
                            }).start();
                        } catch (Exception e) {
                            if (running) Log.w("DirectLink", "accept: " + e.getMessage());
                            break;
                        }
                    }
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();
            return "监听中：" + port;
        } catch (Exception e) {
            running = false;
            return "启动失败：" + e.getMessage();
        }
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        for (Peer p : peers) { try { p.socket.close(); } catch (Exception ignored) { } }
        peers.clear();
    }

    /* ---------- 拨号 ---------- */
    public synchronized String dial(String host, int p) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, p), 6000);
            s.setTcpNoDelay(true);
            new Thread(new Runnable() {
                private final Socket sock = s;
                @Override public void run() { handle(sock, false); }
            }).start();
            return "正在连接 " + host + ":" + p;
        } catch (Exception e) {
            return "连接失败：" + e.getMessage();
        }
    }

    /* ---------- 局域网扫描：找出同网段里也在监听 8643 的设备 ----------
       ⚠️ 旧实现是「每个 IP 直接 new Thread」：254 个地址 × 多张网卡（Wi-Fi/蜂窝/VPN）
       就是上千条线程，每条约 260ms 连接 + 900ms 读超时，扫描期间 App 直接被拖死。
       现在：单个后台线程 + 固定 16 路并发 + 150ms 连接超时 + 互斥（同时只跑一轮）。 */
    private volatile boolean scanning = false;

    public void scanLan() {
        if (scanning) return;
        scanning = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newFixedThreadPool(16);
                try {
                    java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
                    java.util.List<String> prefixes = new ArrayList<>();
                    final java.util.List<String> own = new ArrayList<>();
                    while (ifs.hasMoreElements()) {
                        java.net.NetworkInterface ni = ifs.nextElement();
                        if (!ni.isUp() || ni.isLoopback()) continue;
                        java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                        while (addrs.hasMoreElements()) {
                            java.net.InetAddress a = addrs.nextElement();
                            if (!(a instanceof java.net.Inet4Address)) continue;
                            String ip = a.getHostAddress();
                            if (ip == null || ip.indexOf('.') < 0) continue;
                            if (!own.contains(ip)) own.add(ip);
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                String pfx = ip.substring(0, ip.lastIndexOf('.') + 1);
                                if (!prefixes.contains(pfx)) prefixes.add(pfx);
                            }
                        }
                    }
                    for (final String prefix : prefixes) {
                        for (int i = 1; i <= 254; i++) {
                            final String host = prefix + i;
                            if (own.contains(host)) continue;   // 别探自己（会跟自己握手、刷一串 open/close）
                            pool.execute(new Runnable() {
                                @Override public void run() {
                                    probe(host);
                                }
                            });
                        }
                    }
                    try { pool.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS); } catch (Throwable ignored) { }
                    Log.d("DirectLink", "scanLan done prefixes=" + prefixes.size());
                } catch (Throwable e) {
                    Log.w("DirectLink", "scanLan: " + e.getMessage());
                } finally {
                    try { pool.shutdownNow(); } catch (Throwable ignored) { }
                    scanning = false;
                }
            }
        }, "direct-scan").start();
    }

    /** 探测一台主机：连上、报 HI，等对方回 HI（150ms 连接超时，1s 读超时） */
    private void probe(String host) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(host, DEFAULT_PORT), 150);
            s.setSoTimeout(1000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
            out.println("HI|" + safeId());
            String line = in.readLine();
            if (line != null && line.startsWith("HI|")) {
                emit("{\"type\":\"found\",\"addr\":\"" + esc(host) + "\",\"peer\":\"" + esc(line.substring(3)) + "\"}");
            }
        } catch (Exception ignored) {
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) { }
        }
    }

    /* ---------- 处理一条连接 ---------- */
    private void handle(Socket s, boolean inbound) {
        Peer p = new Peer();
        p.socket = s;
        p.addr = s.getInetAddress() != null ? s.getInetAddress().getHostAddress() : "?";
        try {
            s.setSoTimeout(0);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"), true);
            p.out = out;

            // 互相报 ID
            out.println("HI|" + safeId());
            String first = in.readLine();
            if (first != null && first.startsWith("HI|")) p.peerId = first.substring(3);

            synchronized (this) { peers.add(p); }
            emit("{\"type\":\"open\",\"peer\":\"" + esc(p.peerId) + "\",\"addr\":\"" + esc(p.addr) + "\"}");

            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("MSG|")) {
                    emit("{\"type\":\"msg\",\"peer\":\"" + esc(p.peerId) + "\",\"text\":\"" + esc(line.substring(4)) + "\"}");
                } else if (line.equals("PING")) {
                    out.println("PONG");
                }
            }
        } catch (Exception e) {
            Log.w("DirectLink", "conn: " + e.getMessage());
        } finally {
            synchronized (this) { peers.remove(p); }
            try { s.close(); } catch (Exception ignored) { }
            emit("{\"type\":\"close\",\"peer\":\"" + esc(p.peerId) + "\",\"addr\":\"" + esc(p.addr) + "\"}");
        }
    }

    /* ---------- 发送 ---------- */
    public synchronized int sendAll(String text) {
        int n = 0;
        for (Peer p : peers) {
            try { p.out.println("MSG|" + text); n++; } catch (Exception ignored) { }
        }
        return n;
    }

    public synchronized String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"port\":").append(port).append(",\"running\":").append(running)
          .append(",\"peers\":[");
        boolean first = true;
        for (Peer p : peers) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"peer\":\"").append(esc(p.peerId)).append("\",\"addr\":\"").append(esc(p.addr)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /* ---------- 工具 ---------- */
    private String safeId() {
        try { return sink.myId(); } catch (Exception e) { return "unknown"; }
    }

    private void emit(String json) {
        try { sink.onEvent(json); } catch (Exception ignored) { }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
