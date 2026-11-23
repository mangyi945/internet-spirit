package com.hljjs.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.FileStorage;

/**
 * 磁链下载（libtorrent4j 2.1.0）。
 *
 * 设计要点：
 *  - SessionManager 的创建/start 会阻塞，统一在后台线程里做，JS 侧调用只等一个 latch（最多 20 秒）
 *  - 所有对外方法都 try/catch，返回 JSON 字符串，异常绝不抛进 WebView
 *  - 下载目录固定 filesDir/bt，任务按 info hash 记 id
 *  - 有活动任务时拉起 BtService（前台服务 + 通知 + 唤醒锁），全部停下就让它自己退
 */
public final class TorrentBridge {

    private static final String TAG = "TorrentBridge";

    private static final Object LOCK = new Object();
    private static SessionManager SM = null;
    private static File SAVE_DIR = null;
    private static volatile boolean STARTING = false;
    private static volatile String LAST_ERR = "";
    private static Context APP = null;

    private static final List<String> ORDER = new ArrayList<String>();
    /* 最近告警（排查"一直 0 个种子"用，btDiag() 里能看到） */
    private static final java.util.ArrayDeque<String> ALERTS = new java.util.ArrayDeque<String>();
    private static void pushAlert(String s) {
        synchronized (ALERTS) {
            ALERTS.addLast(s);
            while (ALERTS.size() > 80) ALERTS.removeFirst();
        }
    }
    private static final java.util.HashMap<String, Boolean> PAUSED = new java.util.HashMap<String, Boolean>();
    /* 任务清单持久化：App 重启后按原始磁链把任务加回去（以前只存 DHT 会话状态，任务表在内存里，重启就没了） */
    private static final java.util.HashMap<String, String> URIS = new java.util.HashMap<String, String>();
    private static final String TASKS_FILE = "bt-tasks.txt";
    /* 选择性下载：id -> 被取消勾选（IGNORE）的文件序号；SEL_APPLIED 记录"已应用过"避免每 3 秒重复设置 */
    private static final java.util.HashMap<String, java.util.HashSet<Integer>> IGNORED = new java.util.HashMap<>();
    private static final java.util.HashSet<String> SEL_APPLIED = new java.util.HashSet<>();
    private static final String SEL_FILE = "bt-select.txt";
    private static final String RESUME_DIR = "bt-resume";
    private static volatile boolean SEL_LOADED = false;
    /* 会话日志（libtorrent 的 SESSION_LOG），https tracker 排查时最有用 */
    private static final java.util.ArrayDeque<String> SESSLOG = new java.util.ArrayDeque<String>();
    private static void pushSess(String s) {
        synchronized (SESSLOG) {
            SESSLOG.addLast(s);
            while (SESSLOG.size() > 120) SESSLOG.removeFirst();
        }
    }

    private static final Pattern BTIH = Pattern.compile("btih:([A-Za-z0-9]{32,40})", Pattern.CASE_INSENSITIVE);

    private TorrentBridge() {
    }

    /* ---------------- 启动 ---------------- */

    /** 预热：在后台线程把 session 起起来，别占 JS 线程 */
    public static void warm(final Context c) {
        try {
            final Context ctx = c.getApplicationContext();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try { loadSelection(ctx); } catch (Throwable ignored) { }
                    try { ensureStarted(ctx); } catch (Throwable ignored) { }
                }
            }, "bt-warm");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) { }
    }

    private static void ensureDir(Context c) {
        if (SAVE_DIR == null) {
            SAVE_DIR = new File(c.getFilesDir(), "bt");
            if (!SAVE_DIR.exists()) SAVE_DIR.mkdirs();
        }
        APP = c.getApplicationContext();
    }

    /* ---------------- CA 证书：给内置 OpenSSL 补信任根 ---------------- */

    /**
     * libtorrent4j 自带的 OpenSSL 编译时把 openssldir 写死成构建机路径
     * （/home/runner/work/.../openssl-3.5.5/../openssl/ssl/cert.pem），设备上根本没有这个文件，
     * 于是 SSL_CTX_set_default_verify_paths() 返回失败（错误栈里是 BIO routines），
     * libtorrent 抛 "init fail" → 所有 https tracker 都连不上（http / udp / DHT 不受影响）。
     *
     * OpenSSL 认 SSL_CERT_FILE / SSL_CERT_DIR 两个环境变量（X509_get_default_cert_file_env），
     * 所以这里把系统 CA 目录里的证书拼成一个 PEM 包，再用 android.system.Os.setenv 注入进进程。
     * Android 的 /system/etc/security/cacerts 就是 <hash>.0 命名的哈希目录，CApath 也能直接用。
     */
    private static volatile String CA_FILE = "";
    private static volatile int CA_CERTS = 0;
    private static volatile String CA_ERR = "";
    private static volatile boolean CA_TRIED = false;

    private static final String[] CA_DIRS = {
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts",
            "/system/etc/security/cacerts_google"
    };

    static synchronized void installCa(Context c) {
        if (!CA_FILE.isEmpty()) return;
        if (c == null) return;
        if (CA_TRIED) return;
        CA_TRIED = true;
        try {
            File dir = new File(c.getFilesDir(), "bt-ca");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "cacert.pem");
            if (!out.isFile() || out.length() < 4096) {
                StringBuilder sb = new StringBuilder(512 * 1024);
                int n = 0;
                for (int i = 0; i < CA_DIRS.length; i++) {
                    File[] fs = new File(CA_DIRS[i]).listFiles();
                    if (fs == null || fs.length == 0) continue;
                    int before = n;
                    for (int k = 0; k < fs.length; k++) {
                        File f = fs[k];
                        if (f == null || !f.isFile() || f.length() < 64 || f.length() > 262144) continue;
                        String pem = readText(f, 262144);
                        if (pem == null || pem.indexOf("-----BEGIN CERTIFICATE-----") < 0) continue;
                        sb.append(pem.trim()).append('\n');
                        n++;
                    }
                    if (n > before) break;   // 第一个能读出证书的目录就够了
                }
                if (n == 0) {
                    CA_ERR = "没读到系统 CA 目录";
                    Log.w(TAG, "installCa: no system CA found");
                    return;
                }
                java.io.FileOutputStream fo = new java.io.FileOutputStream(out, false);
                try {
                    fo.write(sb.toString().getBytes("US-ASCII"));
                    fo.flush();
                } finally {
                    try { fo.close(); } catch (Throwable ignored) { }
                }
            }
            int certs = countCerts(out);
            if (certs <= 0) {
                CA_ERR = "CA 包是空的";
                return;
            }
            CA_CERTS = certs;
            CA_FILE = out.getAbsolutePath();
            CA_ERR = "";
            try {
                android.system.Os.setenv("SSL_CERT_FILE", CA_FILE, true);
            } catch (Throwable e) {
                CA_ERR = "setenv SSL_CERT_FILE: " + e;
            }
            for (int i = 0; i < CA_DIRS.length; i++) {
                if (new File(CA_DIRS[i]).isDirectory()) {
                    try { android.system.Os.setenv("SSL_CERT_DIR", CA_DIRS[i], true); } catch (Throwable ignored) { }
                    break;
                }
            }
            Log.i(TAG, "CA bundle ready: " + CA_CERTS + " certs -> " + CA_FILE);
        } catch (Throwable e) {
            CA_ERR = String.valueOf(e);
            Log.w(TAG, "installCa failed: " + e);
        }
    }

    private static String readText(File f, int cap) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            int want = (int) Math.min(f.length(), (long) cap);
            byte[] buf = new byte[Math.max(want, 1)];
            int off = 0, r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
            return new String(buf, 0, off, "ISO-8859-1");   // PEM 是纯 ASCII
        } catch (Throwable e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private static int countCerts(File f) {
        String s = readText(f, 4 * 1024 * 1024);
        if (s == null) return 0;
        int n = 0, i = 0;
        while ((i = s.indexOf("-----BEGIN CERTIFICATE-----", i)) >= 0) { n++; i += 27; }
        return n;
    }

    /* ---------------- 会话参数 / DHT 状态持久化 ---------------- */

    private static final String STATE_FILE = "bt-session.bin";
    /** 落盘的会话状态大小（诊断用） */
    private static volatile long STATE_BYTES = 0;

    /** 公共 DHT 引导节点：冷启动多打几个，比只靠 libtorrent4j 默认的 4 个快 */
    private static final String DHT_NODES =
            "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,"
            + "dht.libtorrent.org:25401,dht.aelitis.com:6881,router.bitcomet.com:6881";

    /** 磁链自带 tracker 太少时补这些（UDP + 2 个 HTTP：有的 Wi-Fi 只挡 UDP，TCP 还能用） */
    private static final String[] PUBLIC_TRACKERS = {
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
            // HTTP（TCP）兜底：实测在「挡 UDP 的 Wi-Fi」上只有这两个还能回 peer
            "http://tracker.dler.com:6969/announce",
            "http://open.acgtracker.com:1096/announce"
    };

    private static void applyTuning(SessionParams sp) {
        try {
            org.libtorrent4j.SettingsPack s = sp.getSettings();
            try { s.setDhtBootstrapNodes(DHT_NODES); } catch (Throwable ignored) { }
            try { s.setMaxMetadataSize(8 * 1024 * 1024); } catch (Throwable ignored) { }
            // 加密：pe_enabled = 允许加密也允许明文。设成 forced 会过滤掉大量普通做种节点
            try {
                int pe = org.libtorrent4j.swig.settings_pack.enc_policy.pe_enabled.swigValue();
                s.setInteger(org.libtorrent4j.swig.settings_pack.int_types.out_enc_policy.swigValue(), pe);
                s.setInteger(org.libtorrent4j.swig.settings_pack.int_types.in_enc_policy.swigValue(), pe);
            } catch (Throwable ignored) { }
            // uTP / UPnP / NAT-PMP / LSD 全开（uTP 自带拥塞控制，不容易打满上行）
            try {
                s.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_outgoing_utp.swigValue(), true);
                s.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_incoming_utp.swigValue(), true);
                s.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_upnp.swigValue(), true);
                s.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_natpmp.swigValue(), true);
                s.setBoolean(org.libtorrent4j.swig.settings_pack.bool_types.enable_lsd.swigValue(), true);
            } catch (Throwable ignored) { }
            try { s.setInteger(org.libtorrent4j.swig.settings_pack.int_types.active_limit.swigValue(), 12); } catch (Throwable ignored) { }
            try { s.setInteger(org.libtorrent4j.swig.settings_pack.int_types.connections_limit.swigValue(), 200); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
    }

    private static SessionParams buildSessionParams(Context c) {
        SessionParams sp;
        byte[] st = loadSessionState(c);
        try {
            sp = (st != null && st.length > 32) ? new SessionParams(st) : new SessionParams();
        } catch (Throwable e) {
            sp = new SessionParams();
        }
        applyTuning(sp);
        return sp;
    }

    private static File selFile(Context c) {
        return new File(c.getFilesDir(), SEL_FILE);
    }

    /** 读「取消勾选」清单（启动时一次） */
    private static synchronized void loadSelection(Context c) {
        if (SEL_LOADED || c == null) return;
        SEL_LOADED = true;
        try {
            File f = selFile(c);
            if (!f.isFile() || f.length() == 0 || f.length() > 4L * 1024 * 1024) return;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { int off = 0, r; while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r; }
            finally { try { in.close(); } catch (Throwable ignored) { } }
            String[] lines = new String(b, "UTF-8").split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line == null || line.trim().isEmpty()) continue;
                String[] pp = line.split("\t", 2);
                if (pp.length < 2) continue;
                try {
                    java.util.HashSet<Integer> set = IGNORED.get(pp[0]);
                    if (set == null) { set = new java.util.HashSet<Integer>(); IGNORED.put(pp[0], set); }
                    set.add(Integer.parseInt(pp[1].trim()));
                } catch (Throwable ignored) { }
            }
        } catch (Throwable e) {
            Log.w(TAG, "loadSelection: " + e);
        }
    }

    private static synchronized void saveSelection(Context c) {
        try {
            if (c == null) return;
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, java.util.HashSet<Integer>> e : IGNORED.entrySet()) {
                for (Integer idx : e.getValue()) sb.append(e.getKey()).append('\t').append(idx).append('\n');
            }
            File f = selFile(c);
            File tmp = new File(c.getFilesDir(), SEL_FILE + ".tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp, false);
            try { out.write(sb.toString().getBytes("UTF-8")); out.flush(); }
            finally { try { out.close(); } catch (Throwable ignored) { } }
            if (!tmp.renameTo(f)) { try { f.delete(); } catch (Throwable ignored) { } tmp.renameTo(f); }
        } catch (Throwable e) {
            Log.w(TAG, "saveSelection: " + e);
        }
    }

    /** 元数据到了以后，把用户不想要的文件设成 IGNORE（每个任务只做一次） */
    static synchronized void applySelectionOnce(String id) {
        if (SEL_APPLIED.contains(id)) return;
        try {
            TorrentHandle h = handleOf(id);
            if (h == null) return;
            TorrentInfo ti = h.torrentFile();
            if (ti == null) return;
            int n = ti.files().numFiles();
            java.util.HashSet<Integer> off = IGNORED.get(id);
            for (int i = 0; i < n; i++) {
                boolean ign = off != null && off.contains(i);
                try { h.filePriority(i, ign ? org.libtorrent4j.Priority.IGNORE : org.libtorrent4j.Priority.DEFAULT); }
                catch (Throwable ignored) { }
            }
            SEL_APPLIED.add(id);
        } catch (Throwable ignored) { }
    }

    /** 用户勾选/取消某个文件 */
    public static String setFile(String id, int idx, boolean on) {
        try {
            java.util.HashSet<Integer> off = IGNORED.get(id);
            if (off == null) { off = new java.util.HashSet<Integer>(); IGNORED.put(id, off); }
            if (on) off.remove(Integer.valueOf(idx)); else off.add(Integer.valueOf(idx));
            saveSelection(APP);
            TorrentHandle h = handleOf(id);
            if (h != null) {
                try { h.filePriority(idx, on ? org.libtorrent4j.Priority.DEFAULT : org.libtorrent4j.Priority.IGNORE); }
                catch (Throwable ignored) { }
            }
            return "ok";
        } catch (Throwable e) {
            return "err:" + e;
        }
    }

    /** 播放器从某一段开始读：把顺序下载范围也挪过去（弱网下 seek 更快接上） */
    static void sequentialFrom(String id, int first, int last) {
        try {
            TorrentHandle h = handleOf(id);
            if (h == null) return;
            h.setSequentialRange(Math.max(0, first), Math.max(0, last));
        } catch (Throwable ignored) { }
    }

    private static File resumeDir(Context c) {
        File d = new File(c.getFilesDir(), RESUME_DIR);
        try { if (!d.isDirectory()) d.mkdirs(); } catch (Throwable ignored) { }
        return d;
    }

    private static File resumeFile(Context c, String id) {
        return new File(resumeDir(c), id + ".resume");
    }

    /** 收到 SAVE_RESUME_DATA 告警时落盘 */
    private static void saveResumeData(Context c, org.libtorrent4j.alerts.SaveResumeDataAlert a) {
        try {
            String id = a.handle().infoHash().toHex();
            byte[] buf = org.libtorrent4j.AddTorrentParams.writeResumeDataBuf(a.params());
            if (buf == null || buf.length < 32) return;
            File f = resumeFile(c, id);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp, false);
            try { out.write(buf); out.flush(); }
            finally { try { out.close(); } catch (Throwable ignored) { } }
            if (!tmp.renameTo(f)) { try { f.delete(); } catch (Throwable ignored) { } tmp.renameTo(f); }
        } catch (Throwable e) {
            Log.w(TAG, "saveResumeData: " + e);
        }
    }

    /** 让所有任务把 resume data 报一遍（含元数据，下次启动直接恢复，不用重新找 peer 拿 metadata） */
    static void requestResumeSaves() {
        try {
            for (int i = 0; i < ORDER.size(); i++) {
                TorrentHandle h = handleOf(ORDER.get(i));
                if (h == null) continue;
                try { if (h.torrentFile() != null) h.saveResumeData(TorrentHandle.SAVE_INFO_DICT); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    /** 用 resume data 恢复任务；没有就返回 false 交给磁链重加 */
    private static boolean restoreFromResume(Context c, String id) {
        try {
            File rf = resumeFile(c, id);
            if (!rf.isFile() || rf.length() < 32 || rf.length() > 8L * 1024 * 1024) return false;
            byte[] data = new byte[(int) rf.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(rf);
            try { int off = 0, r; while (off < data.length && (r = in.read(data, off, data.length - off)) > 0) off += r; }
            finally { try { in.close(); } catch (Throwable ignored) { } }
            org.libtorrent4j.swig.error_code ec = new org.libtorrent4j.swig.error_code();
            org.libtorrent4j.swig.add_torrent_params atp =
                org.libtorrent4j.swig.libtorrent.read_resume_data_ex(org.libtorrent4j.Vectors.bytes2byte_vector(data), ec);
            if (atp == null || ec.value() != 0) return false;
            atp.setSave_path(SAVE_DIR.getAbsolutePath());
            atp.setFlags(atp.getFlags().or_(TorrentFlags.AUTO_MANAGED));
            SM.swig().async_add_torrent(atp);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "restoreFromResume(" + id + "): " + e);
            return false;
        }
    }

    private static File tasksFile(Context c) {
        return new File(c.getFilesDir(), TASKS_FILE);
    }

    /** 把任务表（id / 是否暂停 / 原始磁链）写盘 */
    private static synchronized void saveTasks(Context c) {
        try {
            if (c == null) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ORDER.size(); i++) {
                String id = ORDER.get(i);
                String uri = URIS.get(id);
                if (uri == null || uri.isEmpty()) continue;
                boolean paused = PAUSED.containsKey(id) && Boolean.TRUE.equals(PAUSED.get(id));
                sb.append(id).append('\t').append(paused ? '1' : '0').append('\t').append(uri).append('\n');
            }
            File f = tasksFile(c);
            File tmp = new File(c.getFilesDir(), TASKS_FILE + ".tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp, false);
            try { out.write(sb.toString().getBytes("UTF-8")); out.flush(); }
            finally { try { out.close(); } catch (Throwable ignored) { } }
            if (!tmp.renameTo(f)) {
                try { f.delete(); } catch (Throwable ignored) { }
                tmp.renameTo(f);
            }
        } catch (Throwable e) {
            Log.w(TAG, "saveTasks: " + e);
        }
    }

    /** 启动时把上次的任务加回会话（按磁链重加，已下好的文件会被重新校验，进度不丢） */
    private static synchronized void restoreTasks(Context c) {
        try {
            if (c == null || SM == null || SAVE_DIR == null) return;
            File f = tasksFile(c);
            if (!f.isFile() || f.length() == 0 || f.length() > 2L * 1024 * 1024) return;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0, r;
                while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            } finally { try { in.close(); } catch (Throwable ignored) { } }
            String[] lines = new String(b, "UTF-8").split("\n");
            int n = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line == null || line.trim().isEmpty()) continue;
                String[] p = line.split("\t", 3);
                if (p.length < 3) continue;
                String id = p[0].trim();
                String uri = p[2].trim();
                boolean paused = "1".equals(p[1].trim());
                if (id.isEmpty() || !uri.startsWith("magnet:")) continue;
                try {
                    if (SM.find(Sha1Hash.parseHex(id)) == null) {
                        boolean byResume = restoreFromResume(c, id);
                        if (!byResume) SM.download(uri, SAVE_DIR, TorrentFlags.AUTO_MANAGED);
                    }
                    if (ORDER.indexOf(id) < 0) ORDER.add(id);
                    URIS.put(id, uri);
                    if (paused) {
                        PAUSED.put(id, true);
                        TorrentHandle h = handleOf(id);
                        if (h != null) h.pause();
                    }
                    n++;
                } catch (Throwable e) {
                    Log.w(TAG, "restore task failed " + id + ": " + e);
                }
            }
            if (n > 0) {
                pushAlert("TASK_RESTORE : 恢复 " + n + " 个下载任务");
                BtService.start(c);
            }
        } catch (Throwable e) {
            Log.w(TAG, "restoreTasks: " + e);
        }
    }

    private static File stateFile(Context c) {
        return new File(c.getFilesDir(), STATE_FILE);
    }

    private static byte[] loadSessionState(Context c) {
        try {
            File f = stateFile(c);
            if (!f.isFile() || f.length() < 32 || f.length() > 8L * 1024 * 1024) return null;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0, r;
                while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            STATE_BYTES = b.length;
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void saveSessionState(Context c) {
        try {
            SessionManager sm = SM;
            if (sm == null || c == null) return;
            byte[] b = sm.saveState();
            if (b == null || b.length < 32) return;
            File f = stateFile(c);
            File tmp = new File(c.getFilesDir(), STATE_FILE + ".tmp");
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp, false);
            try { out.write(b); out.flush(); } finally { try { out.close(); } catch (Throwable ignored) { } }
            STATE_BYTES = b.length;
            if (!tmp.renameTo(f)) {
                try { f.delete(); } catch (Throwable ignored) { }
                tmp.renameTo(f);
            }
        } catch (Throwable e) {
            Log.w(TAG, "saveSessionState: " + e);
        }
    }

    private static volatile boolean SAVER_ON = false;

    /** 每 60 秒把会话状态（DHT 路由表）写一次盘；下次启动 new SessionParams(bytes) 直接恢复 */
    private static void startStateSaver(final Context c) {
        if (SAVER_ON) return;
        SAVER_ON = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try { Thread.sleep(60000); } catch (InterruptedException ignored) { }
                    if (SM == null) { SAVER_ON = false; return; }
                    saveSessionState(c);
                    try { requestResumeSaves(); } catch (Throwable ignored) { }
                }
            }
        }, "bt-state-saver");
        t.setDaemon(true);
        t.start();
    }

    /** 磁链自带 tracker 少于 2 个时，补一批公共 UDP tracker（等任务真正入队后再补） */
    private static void topUpTrackers(final String hash, final String uri) {
        try {
            int n = 0;
            Matcher tm = Pattern.compile("(?i)[?&]tr=").matcher(uri);
            while (tm.find()) n++;
            if (n >= 2) return;
            final int haveTrs = n;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    for (int i = 0; i < 10; i++) {
                        final TorrentHandle h = handleOf(hash);
                        if (h != null) {
                            for (String u : PUBLIC_TRACKERS) {
                                try { h.addTracker(new org.libtorrent4j.AnnounceEntry(u)); } catch (Throwable ignored) { }
                            }
                            try { h.forceReannounce(); } catch (Throwable ignored) { }
                            try { h.forceDHTAnnounce(); } catch (Throwable ignored) { }
                            pushAlert("TRACKER_TOPUP : 磁链只带 " + haveTrs + " 个 tracker，已补 " + PUBLIC_TRACKERS.length + " 个公共 UDP tracker");
                            return;
                        }
                        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                    }
                }
            }, "bt-trackers");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) { }
    }

    /** 保证 session 已启动；返回 true 表示可用 */
    private static boolean ensureStarted(Context c) {
        if (SM != null) return true;
        synchronized (LOCK) {
            if (SM != null) return true;
            if (STARTING) {
                // 另一个线程正在启动，等它
                try {
                    for (int i = 0; i < 400 && SM == null; i++) LOCK.wait(50);
                } catch (InterruptedException ignored) {
                }
                return SM != null;
            }
            STARTING = true;
        }
        final CountDownLatch done = new CountDownLatch(1);
        final Context ctx = c.getApplicationContext();
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureDir(ctx);
                    // 必须在 session start 之前：https tracker 的 SSL_CTX 靠环境变量找 CA
                    installCa(ctx);
                    // 注意：logging=true 会打开 dht_log / peer_log / dht_pkt，日志量极大（实测 DHT_PKT 刷屏），
                    // 平时必须是 false；排查 libtorrent 内部问题时可临时改 true
                    SessionManager sm = new SessionManager();
                    // 会话状态（DHT 路由表 / 已连节点）落盘：冷启动 DHT 是空的，
                    // 靠默认引导节点要等几分钟；恢复上次的状态能几秒内接上网络
                    SessionParams sp = buildSessionParams(ctx);
                    // 监听器要在 start 之前挂：session start 时的那条
                    // "SSL set_default verify_paths failed: ..." 是诊断 https tracker 的关键
                    final org.libtorrent4j.AlertListener al = new org.libtorrent4j.AlertListener() {
                        @Override public int[] types() { return null; }
                        @Override public void alert(org.libtorrent4j.alerts.Alert<?> a) {
                            try {
                                if (a.type() == org.libtorrent4j.alerts.AlertType.SAVE_RESUME_DATA) {
                                    try { saveResumeData(APP, (org.libtorrent4j.alerts.SaveResumeDataAlert) a); } catch (Throwable ignored) { }
                                    return;
                                }
                                if (a.type() == org.libtorrent4j.alerts.AlertType.METADATA_RECEIVED) {
                                    try {
                                        String hid = ((org.libtorrent4j.alerts.TorrentAlert<?>) a).handle().infoHash().toHex();
                                        SEL_APPLIED.remove(hid);
                                        applySelectionOnce(hid);
                                    } catch (Throwable ignored) { }
                                }
                                String t = String.valueOf(a.type());
                                String msg = String.valueOf(a.message());
                                if (t.indexOf("SESSION") >= 0) { pushSess(t + " : " + msg); return; }
                                if (t.indexOf("DHT") >= 0 && msg.indexOf("SSL") < 0) {
                                    if (t.indexOf("DHT_OUTGOING_GET_PEERS") >= 0 || t.indexOf("DHT_GET_PEERS") >= 0 || t.indexOf("DHT_REPLY") >= 0) return;
                                }
                                if (t.indexOf("TRACKER") < 0 && t.indexOf("DHT") < 0 && t.indexOf("METADATA") < 0 && msg.indexOf("SSL") < 0) return;
                                pushAlert(t + " : " + msg);
                            } catch (Throwable ignored) { }
                        }
                    };
                    boolean hooked = false;
                    try { sm.addListener(al); hooked = true; } catch (Throwable ignored) { }
                    sm.start(sp);
                    if (!hooked) { try { sm.addListener(al); } catch (Throwable ignored) { } }
                    // 只绑 IPv4：不少网络没有可用 IPv6，libtorrent 若同时监听 [::] 会用 IPv6 socket 宣布 tracker，
                    // 结果所有 tracker 都报 "Network is unreachable"（本次实测的根因）
                    try { sm.listenInterfaces("0.0.0.0:6881"); } catch (Throwable ignored) { }
                    // 连接数：手机 NAT 表很小，400 以上容易把路由器打满（表现为有 peer 但 0 速度）
                    try { sm.maxConnections(200); } catch (Throwable ignored) { }
                    try { sm.maxPeers(120); } catch (Throwable ignored) { }
                    // 默认开 DHT：磁链里的 UDP tracker 不一定可达（尤其国内网络），DHT 是主要兜底
                    try { sm.startDht(); } catch (Throwable ignored) { }
                    SM = sm;
                    LAST_ERR = "";
                    // 定期把 DHT 路由表写盘，下次启动直接恢复
                    startStateSaver(ctx);
                    // 把上次没下完的任务加回来（否则重启 App 任务就"消失"了）
                    try { restoreTasks(ctx); } catch (Throwable ignored) { }
                } catch (Throwable e) {
                    LAST_ERR = String.valueOf(e);
                    Log.e(TAG, "session start failed: " + e, e);
                } finally {
                    done.countDown();
                    synchronized (LOCK) { STARTING = false; LOCK.notifyAll(); }
                }
            }
        }, "bt-start");
        th.setDaemon(true);
        th.start();
        try { done.await(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return SM != null;
    }

    /* ---------------- info hash ---------------- */

    private static final char[] B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    /** 磁链里的 btih：40 位十六进制直接用；32 位 base32 转成十六进制 */
    public static String infoHashOf(String magnet) {
        try {
            Matcher m = BTIH.matcher(String.valueOf(magnet));
            if (!m.find()) return "";
            String raw = m.group(1);
            if (raw.length() == 40) return raw.toLowerCase();
            // base32 -> 40 位 hex
            String s = raw.toUpperCase();
            StringBuilder bits = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                int v = new String(B32).indexOf(s.charAt(i));
                if (v < 0) return "";
                String b = Integer.toBinaryString(v);
                while (b.length() < 5) b = "0" + b;
                bits.append(b);
            }
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i + 4 <= bits.length() && hex.length() < 40; i += 4) {
                hex.append(Character.forDigit(Integer.parseInt(bits.substring(i, i + 4), 2), 16));
            }
            return hex.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    /* ---------------- 对外操作 ---------------- */

    public static synchronized String add(Context c, String magnet) {
        JSONObject out = new JSONObject();
        try {
            String uri = String.valueOf(magnet == null ? "" : magnet).trim();
            if (!uri.startsWith("magnet:")) {
                out.put("ok", false);
                out.put("error", "不是磁链");
                return out.toString();
            }
            String hash = infoHashOf(uri);
            if (hash.isEmpty()) {
                out.put("ok", false);
                out.put("error", "磁链里没有 btih");
                return out.toString();
            }
            if (ORDER.indexOf(hash) >= 0) {
                out.put("ok", true);
                out.put("dup", true);
                out.put("id", hash);
                return out.toString();
            }
            if (!ensureStarted(c)) {
                out.put("ok", false);
                out.put("error", "下载引擎起不来：" + LAST_ERR);
                return out.toString();
            }
            ensureDir(c);
            SM.download(uri, SAVE_DIR, TorrentFlags.AUTO_MANAGED);
            ORDER.add(hash);
            URIS.put(hash, uri);
            SEL_APPLIED.remove(hash);
            saveTasks(c);
            // 磁链只带一两个 tracker 时补公共 tracker（等任务入队后再补，见 topUpTrackers）
            topUpTrackers(hash, uri);
            out.put("ok", true);
            out.put("id", hash);
            out.put("dup", false);
            BtService.start(c);
            return out.toString();
        } catch (Throwable e) {
            Log.e(TAG, "add failed", e);
            try {
                out.put("ok", false);
                out.put("error", String.valueOf(e));
            } catch (Throwable ignored) { }
            return out.toString();
        }
    }

    private static TorrentHandle handleOf(String id) {
        if (SM == null) return null;
        try {
            TorrentHandle h = SM.find(Sha1Hash.parseHex(String.valueOf(id)));
            if (h != null && h.isValid()) return h;
        } catch (Throwable ignored) { }
        return null;
    }

    public static String list() {
        JSONArray arr = new JSONArray();
        try {
            if (SM == null) return arr.toString();
            ensureStarted(APP);
            for (int i = 0; i < ORDER.size(); i++) {
                String id = ORDER.get(i);
                TorrentHandle h = handleOf(id);
                if (h == null) continue;
                try { if (h.torrentFile() != null) applySelectionOnce(id); } catch (Throwable ignored) { }
                TorrentStatus st = h.status();
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("magnet", URIS.containsKey(id) ? URIS.get(id) : "");
                o.put("name", st.name() == null ? "" : st.name());
                boolean meta = st.hasMetadata();
                o.put("hasMeta", meta);
                o.put("progress", Math.round(st.progress() * 1000) / 10.0);
                o.put("downloaded", st.totalDone());
                o.put("total", st.totalWanted());
                o.put("rate", st.downloadRate());
                o.put("seeds", st.numSeeds());
                o.put("peers", st.numPeers());
                o.put("state", String.valueOf(st.state()));
                              o.put("paused", PAUSED.containsKey(id) ? PAUSED.get(id) : false);
                arr.put(o);
            }
        } catch (Throwable e) {
            Log.e(TAG, "list failed", e);
        }
        return arr.toString();
    }

    public static String pause(String id) {
        try {
            TorrentHandle h = handleOf(id);
            if (h == null) return "no";
            h.pause();
            PAUSED.put(String.valueOf(id), true);
            saveTasks(APP);
            return "ok";
        } catch (Throwable e) {
            return "err:" + e;
        }
    }

    public static String resume(String id) {
        try {
            TorrentHandle h = handleOf(id);
            if (h == null) return "no";
            h.resume();
            PAUSED.put(String.valueOf(id), false);
            saveTasks(APP);
            return "ok";
        } catch (Throwable e) {
            return "err:" + e;
        }
    }

    /** 删除任务；delFiles=true 时把已下载的文件一并删掉 */
    public static String remove(Context c, String id, boolean delFiles) {
        try {
            TorrentHandle h = handleOf(id);
            if (h == null) return "no";
            if (delFiles) {
                try {
                    TorrentInfo ti = h.torrentFile();
                    if (ti != null) {
                        FileStorage fs = ti.files();
                        ensureDir(c);
                        int n = fs.numFiles();
                        for (int i = 0; i < n; i++) {
                            String p = fs.filePath(i);
                            if (p == null) continue;
                            File f = new File(SAVE_DIR, p);
                            // 只删 bt 目录里的东西，防路径穿越
                            String canon = f.getCanonicalPath();
                            if (!canon.startsWith(SAVE_DIR.getCanonicalPath())) continue;
                            if (f.exists() && f.isFile()) f.delete();
                        }
                    }
                } catch (Throwable ignored) { }
            }
            SM.remove(h);
            ORDER.remove(id);
            PAUSED.remove(id);
            URIS.remove(id);
            saveTasks(c);
            if (ORDER.isEmpty()) BtService.stop(c);
            return "ok";
        } catch (Throwable e) {
            return "err:" + e;
        }
    }

    public static String files(String id) {
        JSONArray arr = new JSONArray();
        try {
            TorrentHandle h = handleOf(id);
            if (h == null) return arr.toString();
            TorrentInfo ti = h.torrentFile();
            if (ti == null) return arr.toString();
            FileStorage fs = ti.files();
            int n = fs.numFiles();
            applySelectionOnce(id);
            java.util.HashSet<Integer> offSel = IGNORED.get(id);
            long[] fp = null;
            try { fp = h.fileProgress(); } catch (Throwable ignored) { }
            for (int i = 0; i < n; i++) {
                JSONObject o = new JSONObject();
                o.put("index", i);
                o.put("selected", !(offSel != null && offSel.contains(i)));
                o.put("path", fs.filePath(i));
                try { o.put("size", fs.fileSize(i)); } catch (Throwable ignored) { o.put("size", 0); }
                try { o.put("offset", fs.fileOffset(i)); } catch (Throwable ignored) { o.put("offset", 0L); }
                try { if (fp != null && i < fp.length) o.put("done", fp[i]); } catch (Throwable ignored) { }
                arr.put(o);
            }
        } catch (Throwable e) {
            Log.e(TAG, "files failed", e);
        }
        return arr.toString();
    }

    /* ---------------- 边下边播 ---------------- */

    /** 流服务要用的只读访问器 */
    static TorrentHandle handleForStream(String id) { return handleOf(id); }
    static File saveDirForStream() { return SAVE_DIR; }

    /** 把某个文件设成顺序下载 + 最高优先级，并给前几个 piece 加急（起播更快） */
    private static void prioritize(String id, int idx) {
        try {
            TorrentHandle h = handleOf(id);
            if (h == null) return;
            TorrentInfo ti = h.torrentFile();
            if (ti == null) return;
            FileStorage fs = ti.files();
            if (idx < 0 || idx >= fs.numFiles()) return;
            long off = fs.fileOffset(idx);
            long size = fs.fileSize(idx);
            int pl = ti.pieceLength();
            if (pl <= 0) return;
            int first = (int) (off / pl);
            int last = (int) ((off + Math.max(0, size - 1)) / pl);
            try { h.filePriority(idx, org.libtorrent4j.Priority.TOP_PRIORITY); } catch (Throwable ignored) { }
            try { h.setSequentialRange(first, last); } catch (Throwable ignored) { }
            for (int p = first; p <= Math.min(last, first + 8); p++) {
                try { h.setPieceDeadline(p, 1500); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    /** 播放器请求哪一段，就把那一段的 piece 提成最高优先级（mp4 的 moov 常在文件尾部） */
    static void prioritizePieces(String id, int first, int last) {
        try {
            TorrentHandle h = handleOf(id);
            if (h == null) return;
            int a0 = Math.max(0, first);
            int b0 = Math.max(a0, last);
            for (int p = a0; p <= b0; p++) {
                try { h.piecePriority(p, org.libtorrent4j.Priority.TOP_PRIORITY); } catch (Throwable ignored) { }
                try { h.setPieceDeadline(p, 1200); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    /** 返回本地播放地址（http://127.0.0.1:port/bt/<hash>/<idx>），失败返回 err:... */
    public static String stream(String id, int idx) {
        try {
            int port = BtStreamServer.ensureStarted();
            if (port <= 0) return "err:流服务起不来";
            prioritize(id, idx);
            return "http://127.0.0.1:" + port + "/bt/" + id + "/" + idx;
        } catch (Throwable e) {
            return "err:" + e;
        }
    }

    /** 给 BtService 用：返回 "个数|总速率|平均进度|名字"，没有活动任务返回 "" */
    public static String summary() {
        try {
            if (SM == null) return "";
            int n = 0;
            long rate = 0;
            long done = 0;
            long total = 0;
            String firstName = "";
            for (int i = 0; i < ORDER.size(); i++) {
                TorrentHandle h = handleOf(ORDER.get(i));
                if (h == null) continue;
                TorrentStatus st = h.status();
                Boolean pz = PAUSED.get(ORDER.get(i));
                if (pz != null && pz) continue;
                n++;
                rate += st.downloadRate();
                done += st.totalDone();
                total += st.totalWanted();
                if (firstName.isEmpty()) firstName = st.name() == null ? "" : st.name();
            }
            if (n == 0) return "";
            double pct = total > 0 ? (done * 100.0 / total) : 0;
            return n + "|" + rate + "|" + pct + "|" + firstName;
        } catch (Throwable e) {
            return "";
        }
    }

    /* ---------------- 网络变化监听（Android 侧，不依赖 WebView） ---------------- */

    /** 换网络后重建 socket / 重启 DHT / 重新宣布 tracker（Android 切 Wi-Fi ↔ 数据后必须做） */
    public static String networkReset() {
        try {
            if (SM == null) return "no";
            try { SM.reopenNetworkSockets(); } catch (Throwable ignored) { }
            // 关键：**不要** stopDht/startDht。实测这么一停一起，DHT 之后一直是 0 节点
            // （即使网络本身没问题），tracker 也不再 announce；只重建 socket + 重新绑定 + 重新宣布就好
            try { SM.listenInterfaces("0.0.0.0:6881"); } catch (Throwable ignored) { }
            int n = 0;
            for (String id : new ArrayList<String>(ORDER)) {
                TorrentHandle h = handleOf(id);
                if (h == null) continue;
                try { h.forceReannounce(); n++; } catch (Throwable ignored) { }
                // 切网后 DHT 也要重新宣布，否则只靠 tracker 找不到新 peer
                try { h.forceDHTAnnounce(); } catch (Throwable ignored) { }
                try { SM.dhtAnnounce(h.infoHash()); } catch (Throwable ignored) { }
            }
            // 顺便把新状态写盘（新网卡的端口/DHT 表）
            try { saveSessionState(APP); } catch (Throwable ignored) { }
            synchronized (ALERTS) { ALERTS.clear(); }
            pushAlert("NETWORK_RESET : 已重建 socket 并重新宣布 " + n + " 个任务");
            return "ok:" + n;
        } catch (Throwable e) {
            return "err:" + e;
        }
    }

    /** 把已完成任务的文件复制到系统「下载」目录（Download/hljjs/），返回 ok/no/empty/not-done */
    public static String exportToDownloads(Context c, String id) {
        try {
            TorrentHandle h = handleOf(id);
            if (h == null) return "no";
            TorrentStatus st = h.status();
            if (!st.isFinished() && st.progress() < 0.999f) return "not-done";
            TorrentInfo ti = h.torrentFile();
            if (ti == null) return "no-meta";
            FileStorage fs = ti.files();
            ensureDir(c);
            int n = fs.numFiles();
            int copied = 0;
            for (int i = 0; i < n; i++) {
                String rel = fs.filePath(i);
                if (rel == null) continue;
                File src = new File(SAVE_DIR, rel);
                if (!src.isFile() || src.length() == 0) continue;
                try {
                    if (copyToDownloads(c, src)) copied++;
                } catch (Throwable ignored) { }
            }
            return copied > 0 ? "ok" : "empty";
        } catch (Throwable e) {
            return "err:" + e;
        }
    }

    private static boolean copyToDownloads(Context c, File src) throws Exception {
        String relPath = "hljjs";   // 放在「下载/hljjs/」下面，方便找
        java.io.OutputStream os = null;
        java.io.InputStream is = null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/" + relPath);
                android.net.Uri uri = c.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                os = c.getContentResolver().openOutputStream(uri);
            } else {
                File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS), relPath);
                if (!dir.exists()) dir.mkdirs();
                os = new java.io.FileOutputStream(new File(dir, src.getName()));
            }
            if (os == null) return false;
            is = new java.io.FileInputStream(src);
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
            os.flush();
            return true;
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignored) { }
            try { if (os != null) os.close(); } catch (Throwable ignored) { }
        }
    }

    /** 下载目录当前占用（字节），用来验证"删除任务连文件一起删" */
    private static long dirBytes(File dir) {
        long n = 0;
        try {
            if (dir == null || !dir.isDirectory()) return 0;
            File[] fs = dir.listFiles();
            if (fs == null) return 0;
            for (File f : fs) n += f.isDirectory() ? dirBytes(f) : f.length();
        } catch (Throwable ignored) { }
        return n;
    }

    /** 清空下载目录（任务列表也清），用于"清空下载缓存" */
    public static String purge(Context c) {
        try {
            ensureDir(c);
            int n = 0;
            try { for (String id : new ArrayList<String>(ORDER)) { TorrentHandle h = handleOf(id); if (h != null) { try { SM.remove(h); } catch (Throwable ignored) { } } } } catch (Throwable ignored) { }
            ORDER.clear();
            PAUSED.clear();
            n = deleteAll(SAVE_DIR);
            BtService.stop(c);
            return "ok:" + n;
        } catch (Throwable e) {
            return "err:" + e;
        }
    }

    private static int deleteAll(File dir) {
        int n = 0;
        try {
            if (dir == null || !dir.isDirectory()) return 0;
            File[] fs = dir.listFiles();
            if (fs == null) return 0;
            for (File f : fs) {
                if (f.isDirectory()) { n += deleteAll(f); f.delete(); }
                else { if (f.delete()) n++; }
            }
        } catch (Throwable ignored) { }
        return n;
    }

    /** 给某个任务补一个 tracker（用于磁链只带 UDP tracker 且不可达的情况） */
    public static String addTracker(String id, String url) {
        try {
            TorrentHandle h = handleOf(id);
            if (h == null) return "no";
            String u = String.valueOf(url == null ? "" : url).trim();
            if (u.isEmpty()) return "empty";
            h.addTracker(new org.libtorrent4j.AnnounceEntry(u));
            try { h.forceReannounce(); } catch (Throwable ignored) { }
            return "ok";
        } catch (Throwable e) {
            return "err:" + e;
        }
    }

    /** 诊断：会话/ DHT / tracker / 最近告警，返回 JSON 字符串 */
    public static String diag() {
        JSONObject o = new JSONObject();
        try {
            o.put("running", SM != null && SM.isRunning());
            o.put("dhtRunning", SM != null && SM.isDhtRunning());
            o.put("dhtNodes", SM != null ? SM.dhtNodes() : 0);
            o.put("external", SM != null ? String.valueOf(SM.externalAddress()) : "");
            o.put("listen", SM != null ? String.valueOf(SM.listenInterfaces()) : "");
            o.put("saveDir", SAVE_DIR != null ? SAVE_DIR.getAbsolutePath() : "");
            o.put("dirBytes", dirBytes(SAVE_DIR));
            o.put("downloadRateLimit", SM != null ? SM.downloadRateLimit() : -1);
            o.put("maxConnections", SM != null ? SM.maxConnections() : -1);
            // 速度/进度：卡住时看这两项就知道是「没连上」还是「连上了但没数据」
            o.put("downloadRate", SM != null ? SM.downloadRate() : 0);
            o.put("uploadRate", SM != null ? SM.uploadRate() : 0);
            o.put("stateFile", STATE_BYTES);
            // https tracker 能不能用的关键：有没有把系统 CA 注入给内置 OpenSSL
            try { if (CA_FILE.isEmpty()) installCa(APP); } catch (Throwable ignored) { }
            o.put("caFile", CA_FILE);
            o.put("caCerts", CA_CERTS);
            o.put("caErr", CA_ERR);
            String envFile = "", envDir = "";
            try { String v = android.system.Os.getenv("SSL_CERT_FILE"); envFile = v == null ? "" : v; } catch (Throwable ignored) { }
            try { String v = android.system.Os.getenv("SSL_CERT_DIR"); envDir = v == null ? "" : v; } catch (Throwable ignored) { }
            o.put("caEnvFile", envFile);
            o.put("caEnvDir", envDir);
            // tracker 回复/报错的次数（UI 上用来解释"为什么一直 0 个种子"）
            int okTr = 0, errTr = 0, metaAlerts = 0;
            synchronized (ALERTS) {
                for (String a : ALERTS) {
                    if (a.indexOf("TRACKER_REPLY") >= 0) okTr++;
                    else if (a.indexOf("TRACKER_ERROR") >= 0) errTr++;
                    else if (a.indexOf("METADATA") >= 0) metaAlerts++;
                }
            }
            o.put("trackerReplies", okTr);
            o.put("trackerErrors", errTr);
            o.put("metadataAlerts", metaAlerts);
            JSONArray alerts = new JSONArray();
            synchronized (ALERTS) { for (String a : ALERTS) alerts.put(a); }
            o.put("alerts", alerts);
            JSONArray sess = new JSONArray();
            synchronized (SESSLOG) { for (String a : SESSLOG) sess.put(a); }
            o.put("sessionLogs", sess);
            JSONArray trs = new JSONArray();
            for (int i = 0; i < ORDER.size(); i++) {
                TorrentHandle h = handleOf(ORDER.get(i));
                if (h == null) continue;
                JSONObject t = new JSONObject();
                t.put("id", ORDER.get(i));
                JSONArray urls = new JSONArray();
                try { for (org.libtorrent4j.AnnounceEntry ae : h.trackers()) urls.put(ae.url()); } catch (Throwable ignored) { }
                t.put("trackers", urls);
                TorrentStatus st = h.status();
                t.put("state", String.valueOf(st.state()));
                t.put("peers", st.numPeers());
                t.put("seeds", st.numSeeds());
                t.put("hasMeta", st.hasMetadata());
                t.put("progress", Math.round(st.progress() * 1000) / 10.0);
                t.put("rate", st.downloadRate());
                t.put("downloaded", st.totalDone());
                t.put("total", st.totalWanted());
                trs.put(t);
            }
            o.put("torrents", trs);
        } catch (Throwable e) {
            try { o.put("error", String.valueOf(e)); } catch (Throwable ignored) { }
        }
        return o.toString();
    }

    /** 是否还有任务在列表里（暂停也算，用于决定服务要不要常驻） */
    public static boolean hasAny() {
        return !ORDER.isEmpty();
    }
}
