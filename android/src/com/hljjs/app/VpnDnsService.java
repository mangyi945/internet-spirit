package com.hljjs.app;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * 只接管 DNS：TUN 路由只有 10.0.0.1/32。
 * 打过补丁的 cloudflared 会把 DNS 查询发到 10.0.0.1:53（HLJJS_TUN_DNS），
 * 我们读 TUN、转发到公网 DNS、构造回包写回 TUN。443 流量完全不经过 TUN。
 */
public class VpnDnsService extends VpnService implements Runnable {
    private static final String TAG = "VpnDns";
    public static final String TUN_DNS = "10.0.0.1";
    private Thread thread;
    private ParcelFileDescriptor tun;

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        if (thread == null) {
            thread = new Thread(this, "vpn-dns");
            thread.start();
        }
        return START_STICKY;
    }

    @Override
    public void run() {
        try {
            Builder b = new Builder();
            b.setMtu(1500);
            b.addAddress("10.0.0.2", 24);
            b.addDnsServer(TUN_DNS);
            b.addRoute(TUN_DNS, 32);
            b.setSession("hljjs-dns");
            tun = b.establish();
            if (tun == null) { Log.e(TAG, "establish failed"); return; }
            Log.i(TAG, "tun established fd=" + tun.getFd());

            FileInputStream in = new FileInputStream(tun.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
            byte[] buf = new byte[32767];
            while (true) {
                int n = in.read(buf);
                if (n < 28) continue;
                if (((buf[0] >> 4) & 0x0f) != 4) continue;
                if ((buf[9] & 0xff) != 17) continue;
                int ihl = (buf[0] & 0x0f) * 4;
                int dport = ((buf[ihl + 2] & 0xff) << 8) | (buf[ihl + 3] & 0xff);
                if (dport != 53) continue;
                byte[] pkt = new byte[n];
                System.arraycopy(buf, 0, pkt, 0, n);
                final FileOutputStream fo = out;
                new Thread(new Runnable() {
                    public void run() { handleDns(pkt, fo); }
                }).start();
            }
        } catch (Throwable e) {
            Log.e(TAG, "vpn error " + e);
        }
    }

    private void handleDns(byte[] packet, FileOutputStream out) {
        try {
            int ihl = (packet[0] & 0x0f) * 4;
            int off = ihl + 8;
            int len = packet.length - off;
            if (len <= 0) return;
            byte[] q = new byte[len];
            System.arraycopy(packet, off, q, 0, len);

            DatagramSocket up = new DatagramSocket();
            protect(up);
            up.setSoTimeout(3000);
            up.send(new DatagramPacket(q, q.length, InetAddress.getByName("223.5.5.5"), 53));
            byte[] rb = new byte[4096];
            DatagramPacket rp = new DatagramPacket(rb, rb.length);
            up.receive(rp);
            up.close();

            int rlen = rp.getLength();
            int total = ihl + 8 + rlen;
            byte[] mock = new byte[total];
            System.arraycopy(packet, 0, mock, 0, ihl);
            System.arraycopy(packet, 12, mock, 16, 4);
            System.arraycopy(packet, 16, mock, 12, 4);
            mock[2] = (byte) (total >> 8);
            mock[3] = (byte) (total & 0xff);
            System.arraycopy(packet, ihl, mock, ihl + 2, 2);
            System.arraycopy(packet, ihl + 2, mock, ihl, 2);
            int ulen = 8 + rlen;
            mock[ihl + 4] = (byte) (ulen >> 8);
            mock[ihl + 5] = (byte) (ulen & 0xff);
            System.arraycopy(rp.getData(), 0, mock, ihl + 8, rlen);
            mock[10] = 0; mock[11] = 0;
            int ipSum = checksum(mock, 0, ihl);
            mock[10] = (byte) (ipSum >> 8);
            mock[11] = (byte) ipSum;
            mock[ihl + 6] = 0; mock[ihl + 7] = 0;
            synchronized (out) { out.write(mock); }
            Log.i(TAG, "dns answered qlen=" + len + " rlen=" + rlen);
        } catch (Throwable e) {
            Log.w(TAG, "dns err " + e);
        }
    }

    private static int checksum(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = 0; i < len; i += 2) {
            int hi = b[off + i] & 0xff;
            int lo = (i + 1 < len) ? (b[off + i + 1] & 0xff) : 0;
            sum += (hi << 8) | lo;
        }
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return (~sum) & 0xffff;
    }

    @Override
    public void onDestroy() {
        try { if (thread != null) thread.interrupt(); } catch (Throwable ignored) { }
        try { if (tun != null) tun.close(); } catch (Throwable ignored) { }
        super.onDestroy();
    }
}
