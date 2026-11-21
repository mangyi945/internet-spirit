package com.hljjs.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int PORT = 8642;
    private static final int REQ_CAMERA = 1;
    private static final int REQ_FILE = 1001;

    private WebView web;
    private AssetServer server;
    private BtBridge bt;
    private View fsView = null;
    private WebChromeClient.CustomViewCallback fsCb = null;

    private ValueCallback<Uri[]> filePathCallback;
    private Uri lastSavedUri;

    private OutputStream sink;
    private long putTotal;
    private String pendingName;
    private Uri pendingUri;
    private File pendingFile;
    private TextToSpeech tts;
    private static MainActivity me;

    private static final int REQ_BOOKS = 91;
    private static final int REQ_PICK_IMG = 92;
    private final java.util.ArrayDeque<Uri> pickQueue = new java.util.ArrayDeque<>();
    private byte[] incoming = null;
    private String incomingName = "";
    private String incomingMime = "";
    private boolean ttsReady = false;
    private String ttsEngineName = "";
    /** 当前引擎里能用的「声音」（VOICEVOX 的各个角色等），onInit 后填好 */


    /** 系统可能没设默认引擎，这里按已安装引擎逐个尝试。 */
    private TextToSpeech.OnInitListener ttsMakeListener() {
        return new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                android.util.Log.w("HLJJS_TTS", "onInit status=" + status + " engine=" + ttsEngineName);
                if (status != TextToSpeech.SUCCESS || tts == null) { ttsReady = false; return; }
                ttsReady = true;
                try {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        public void onStart(String id) { }
                        public void onDone(String id) { sendJs("window.__nativeTTSDone&&window.__nativeTTSDone('" + id + "')"); }
                        public void onError(String id) { sendJs("window.__nativeTTSDone&&window.__nativeTTSDone('" + id + "')"); }
                    });
                } catch (Throwable ignored) { }
            }
        };
    }

    private String escJson(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else b.append(c);
        }
        return b.toString();
    }

private void initTts() {
        final TextToSpeech.OnInitListener lis = ttsMakeListener();
        // 先尊重系统「首选 TTS 引擎」设置（比如用户装的 VOICEVOX）
        try {
            String prefer = android.provider.Settings.Secure.getString(
                    getContentResolver(), android.provider.Settings.Secure.TTS_DEFAULT_SYNTH);
            if (prefer != null && prefer.length() > 0 && !prefer.equals("com.yuemeng.speechsuite")) {
                try {
                    tts = new TextToSpeech(this, lis, prefer);
                    ttsEngineName = prefer;
                    android.util.Log.w("HLJJS_TTS", "prefer engine=" + prefer);
                    return;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        try {
            java.util.List<android.content.pm.ResolveInfo> list = getPackageManager().queryIntentServices(
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
            android.util.Log.w("HLJJS_TTS", "engines=" + list.size());
            for (android.content.pm.ResolveInfo ri : list) {
                String pkg = (ri.serviceInfo != null) ? ri.serviceInfo.packageName : null;
                if (pkg == null) continue;
                try {
                    tts = new TextToSpeech(this, lis, pkg);
                    ttsEngineName = pkg;
                    return;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        try {
            tts = new TextToSpeech(this, lis);
            ttsEngineName = "default";
        } catch (Throwable ignored) { }
    }

    private volatile boolean beaconAlive = true;

    /** 监听同网段电脑的 UDP 广播（HLJJS1|http://ip:port|名字），收到就告诉网页。 */
    private void startLanBeaconListener() {
        new Thread(new Runnable() {
            public void run() {
                java.net.DatagramSocket ds = null;
                try {
                    ds = new java.net.DatagramSocket(null);
                    ds.setReuseAddress(true);
                    ds.bind(new java.net.InetSocketAddress(38421));
                    byte[] buf = new byte[1024];
                    while (beaconAlive) {
                        java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                        if (msg == null || !msg.startsWith("HLJJS1|")) continue;
                        String[] parts = msg.split("\\|", 3);
                        final String url = parts.length > 1 ? parts[1].trim() : "";
                        final String name = parts.length > 2 ? parts[2].trim() : "";
                        if (url.length() == 0) continue;
                        final String js = "window.__lanSite&&window.__lanSite(" + jsStr(url) + "," + jsStr(name) + ")";
                        runOnUiThread(new Runnable() {
                            public void run() {
                                try { web.evaluateJavascript(js, null); } catch (Throwable ignored) { }
                            }
                        });
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { if (ds != null) ds.close(); } catch (Throwable ignored) { }
                }
            }
        }).start();
    }

    /** 执行一段 JS（供服务/回调使用）。 */
    private void sendJs(final String js) {
        runOnUiThread(new Runnable() {
            public void run() {
                try { if (web != null) web.evaluateJavascript(js, null); } catch (Throwable ignored) { }
            }
        });
    }

    /* ==================== 系统状态推送（电量 / 网络）====================
       页面状态栏上的 BAT / NET / 信号格只是装饰，和联机没有任何关系。
       以前是页面每 5~20 秒同步过桥来问一次（@JavascriptInterface 会阻塞 JS 线程，
       Java 侧还要 registerReceiver + getSignalStrength 两个跨进程调用），
       单机游戏、听书都会被它顿一下。改成：这里只在真变化时 push，页面不再轮询。 */
    private android.content.BroadcastReceiver sysBatRx = null;
    private android.net.ConnectivityManager.NetworkCallback sysNetCb = null;

    private void startSysPush() {
        try {
            sysBatRx = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    pushSysStatus();
                }
            };
            // 粘性广播：注册的瞬间会先回一次当前状态
            registerReceiver(sysBatRx,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable ignored) { sysBatRx = null; }
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) {
                    sysNetCb = new android.net.ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(android.net.Network n) { pushSysStatus(); }
                        @Override public void onLost(android.net.Network n) { pushSysStatus(); }
                        @Override public void onCapabilitiesChanged(android.net.Network n,
                                android.net.NetworkCapabilities caps) { pushSysStatus(); }
                    };
                    cm.registerDefaultNetworkCallback(sysNetCb);
                }
            }
        } catch (Throwable ignored) { sysNetCb = null; }
    }

    private void stopSysPush() {
        try { if (sysBatRx != null) unregisterReceiver(sysBatRx); } catch (Throwable ignored) { }
        sysBatRx = null;
        try {
            if (sysNetCb != null) {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(sysNetCb);
            }
        } catch (Throwable ignored) { }
        sysNetCb = null;
    }

    private void pushSysStatus() {
        try {
            sendJs("window.__sysStatus&&window.__sysStatus(" + statusObject().toString() + ")");
        } catch (Throwable ignored) { }
    }

    /** 组一份状态 JSON（电量 / 充电 / 信号 / 网络类型）。纯读，不改任何状态。 */
    private org.json.JSONObject statusObject() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            int pct = (bm != null) ? bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
            o.put("bat", pct);
            android.content.Intent st = registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            int status = (st != null) ? st.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) : -1;
            o.put("chg", status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL);

            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            int sig = 0;
            try {
                android.telephony.SignalStrength ss = tm.getSignalStrength();
                if (ss != null) sig = ss.getLevel();
            } catch (Throwable ignored) { }
            o.put("sig", sig);

            String net = "—";
            try {
                int t = tm.getDataNetworkType();
                if (t == android.telephony.TelephonyManager.NETWORK_TYPE_NR) net = "5G";
                else if (t == android.telephony.TelephonyManager.NETWORK_TYPE_LTE) net = "4G";
                else if (t == android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP
                        || t == android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA
                        || t == android.telephony.TelephonyManager.NETWORK_TYPE_UMTS) net = "3G";
                else if (t != 0) net = "2G";
            } catch (Throwable ignored) { }
            try {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI) net = "WiFi";
            } catch (Throwable ignored) { }
            o.put("net", net);
        } catch (Throwable ignored) { }
        return o;
    }

    /** 系统媒体控制（通知栏/锁屏）把指令转给网页。 */
    static void mediaCommand(final String cmd) {
        final MainActivity a = me;
        if (a == null || a.web == null) return;
        a.runOnUiThread(new Runnable() {
            public void run() {
                try {
                    String js = "window.__nvMediaCmd && window.__nvMediaCmd('"
                            + (cmd == null ? "stop" : cmd.replace("'", "")) + "')";
                    a.web.evaluateJavascript(js, null);
                } catch (Throwable ignored) { }
            }
        });
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        me = this;
        /* 后台预热磁链下载引擎：第一次点下载就不用等 session 启动。
           延后 2.5 秒再做：预热要建 session + 读 145 张系统 CA 写 PEM + DHT 引导，
           和 WebView 首屏抢 CPU/IO 会让冷启动更慢（2026-11-21 性能优化） */
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    try { TorrentBridge.warm(getApplicationContext()); } catch (Throwable ignored) { }
                }
            }, 2500);
        } catch (Throwable ignored) { }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
        }

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }

        // 语音/视频通话要录音权限（不然 WebView getUserMedia 报 NotReadableError）
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 78);
        }

        web = new WebView(this);
        // 允许 adb + chrome://inspect 调试网页（方便在真机上排查）
        if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSupportZoom(false);
        s.setUseWideViewPort(true);
        // 页面自己声明了 width=device-width，开启 overview 模式会让它先按整页缩放再重排，
        // 1 MB 的长页在手机上就是首屏多一次 reflow。关掉，直接按 meta viewport 布局。
        s.setLoadWithOverviewMode(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                android.util.Log.d("hljjs-fs", "onShowCustomView " + view);
                if (fsView != null) { try { callback.onCustomViewHidden(); } catch (Throwable e) { } return; }
                fsView = view;
                fsCb = callback;
                setFsImmersive(true);
                try {
                    android.widget.FrameLayout root = findViewById(android.R.id.content);
                    android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT);
                    root.addView(view, lp);
                    web.setVisibility(View.GONE);
                } catch (Throwable ignored) { }
            }

            @Override
            public void onHideCustomView() {
                android.util.Log.d("hljjs-fs", "onHideCustomView");
                try {
                    android.widget.FrameLayout root = findViewById(android.R.id.content);
                    if (fsView != null) root.removeView(fsView);
                } catch (Throwable ignored) { }
                fsView = null;
                setFsImmersive(false);
                try { web.setVisibility(View.VISIBLE); } catch (Throwable ignored) { }
                if (fsCb != null) { try { fsCb.onCustomViewHidden(); } catch (Throwable e) { } fsCb = null; }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { request.grant(request.getResources()); } catch (Exception ignored) { }
                    }
                });
            }

            // 关键：没有这个，网页里的 <input type="file"> 点了没反应
            // （换头像、发文件都靠它）
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        // 后台待机：前台服务 + wake lock，切 App 复制暗号 / 锁屏时连接不断
        try {
            Intent ka = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(ka);
            else startService(ka);
        } catch (Throwable ignored) { }
        try { startSysPush(); } catch (Throwable ignored) { }   // 电量/网络变化 → push 给页面，页面不再轮询
        try { startNetWatch(); } catch (Throwable ignored) { }
        try { startLanBeaconListener(); } catch (Throwable ignored) { }
        handleOpenIntent(getIntent());
        openSessionFromIntent(getIntent());

        initTts();

        // 社区 / 资源库的下载：WebView 默认点了没反应，要自己接
        web.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                                  String mimeType, long contentLength) {
                if (url == null || url.startsWith("blob:") || url.startsWith("data:")) return;
                downloadToDownloads(url, userAgent, contentDisposition);
            }
        });

        // 短信接收：把收到的内容转给网页层
        try {
            android.content.IntentFilter f = new android.content.IntentFilter("android.provider.Telephony.SMS_RECEIVED");
            registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(final android.content.Context ctx, android.content.Intent it) {
                    try {
                        StringBuilder body = new StringBuilder();
                        String from = "";
                        Object[] pdus = (Object[]) it.getExtras().get("pdus");
                        String fmt = it.getExtras().getString("format");
                        if (pdus != null) {
                            for (Object pdu : pdus) {
                                android.telephony.SmsMessage m = fmt != null
                                        ? android.telephony.SmsMessage.createFromPdu((byte[]) pdu, fmt)
                                        : android.telephony.SmsMessage.createFromPdu((byte[]) pdu);
                                body.append(m.getMessageBody());
                                from = m.getOriginatingAddress();
                            }
                        }
                        final String js = "window.__smsEvent && window.__smsEvent({from:" + jsStr(from) +
                                ",body:" + jsStr(body.toString()) + "})";
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                try { web.evaluateJavascript(js, null); } catch (Exception ignored) { }
                            }
                        });
                    } catch (Exception ignored) { }
                }
            }, f);
        } catch (Exception ignored) { }


        // 真全面屏：画到状态栏/刘海下面
        try {
            if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
            // 状态栏给不透明底色（皮肤会再改一次），避免系统在上面做毛玻璃
            getWindow().setStatusBarColor(0xFFC0C0C0);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            if (Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Throwable ignored) { }

        setContentView(web);

        // 聊天里的链接：用系统浏览器打开，别在 WebView 里跳走
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                String low = url.toLowerCase();
                // 自家页面（AssetServer 起的 127.0.0.1:8642）必须留在 WebView 里：
                // 以前这里把任何 http(s) 都丢给系统浏览器，于是「查看 → 刷新界面」
                // （location.reload()）会跳去 Chrome/夸克打开本地页面（2026-11-21 用户报）
                if (low.startsWith("http://127.0.0.1:" + PORT) || low.startsWith("http://localhost:" + PORT)) {
                    return false;
                }
                if (low.startsWith("http://") || low.startsWith("https://")) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) { }
                    return true;
                }
                // data: / blob: / file: / about: 一律交给 WebView 自己处理
                return false;
            }
        });

        try {
            server = new AssetServer(this, getAssets(), PORT);
            server.start();
        } catch (Exception ignored) { }
        try { bt = new BtBridge(this); } catch (Throwable ignored) { }

        web.loadUrl("http://127.0.0.1:" + PORT + "/index.html");
        appWeb = web;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        result = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            result[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        result = new Uri[]{ data.getData() };
                    }
                }
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        if (requestCode == REQ_PICK_IMG) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final Uri u = data.getData();
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            java.io.InputStream in = getContentResolver().openInputStream(u);
                            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[16384];
                            int n; long total = 0;
                            while ((n = in.read(buf)) > 0) {
                                total += n;
                                if (total > 8L * 1024 * 1024) break;
                                bos.write(buf, 0, n);
                            }
                            in.close();
                            String mime = getContentResolver().getType(u);
                            if (mime == null || mime.length() == 0) mime = "image/jpeg";
                            String b64 = android.util.Base64.encodeToString(bos.toByteArray(),
                                    android.util.Base64.NO_WRAP);
                            final String js = "window.__pickedImage&&window.__pickedImage('data:" + mime +
                                    ";base64," + b64 + "')";
                            runOnUiThread(new Runnable() {
                                public void run() {
                                    try { if (web != null) web.evaluateJavascript(js, null); } catch (Throwable ignored) { }
                                }
                            });
                        } catch (Throwable ignored) { }
                    }
                }).start();
            }
            return;
        }
        if (requestCode == REQ_BOOKS) {
            if (resultCode == RESULT_OK && data != null) {
                pickQueue.clear();
                try {
                    if (data.getClipData() != null) {
                        for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                            pickQueue.add(data.getClipData().getItemAt(i).getUri());
                        }
                    } else if (data.getData() != null) {
                        pickQueue.add(data.getData());
                    }
                } catch (Throwable ignored) { }
                Uri u = pickQueue.poll();
                if (u != null) readUriAsync(u, data.getType());
            }
            return;
        }
        if (requestCode == 1002 && resultCode == RESULT_OK) {
            try { startService(new Intent(this, VpnDnsService.class)); } catch (Throwable ignored) { }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenIntent(intent);
        openSessionFromIntent(intent);
    }

    /** 读一个 Uri 到内存，读完通知网页来取。 */
    private void readUriAsync(final Uri u, final String mime) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String name = queryDisplayName(u);
                    java.io.InputStream in = getContentResolver().openInputStream(u);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 80L * 1024 * 1024) break;
                        bos.write(buf, 0, n);
                    }
                    in.close();
                    if (bos.size() <= 0) return;
                    incoming = bos.toByteArray();
                    incomingName = name;
                    incomingMime = mime == null ? "" : mime;
                    runOnUiThread(new Runnable() {
                        public void run() { sendJs("window.__incomingNow&&window.__incomingNow()"); }
                    });
                } catch (Throwable ignored) { }
            }
        }).start();
    }

    /** QQ / 微信点「用其他应用打开」或「分享」进来时，把文件读进内存等网页取走。 */
    private void openSessionFromIntent(Intent intent) {
        if (intent == null) return;
        final String sid;
        try { sid = intent.getStringExtra("open_sid"); } catch (Throwable e) { return; }
        if (sid == null || sid.isEmpty()) return;
        try { intent.removeExtra("open_sid"); } catch (Throwable ignored) { }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    String safe = sid.replace("'", "").replace("\\", "");
                    if (web != null) web.evaluateJavascript("window.__hljjsOpenSession&&window.__hljjsOpenSession('" + safe + "')", null);
                } catch (Throwable ignored) { }
            }
        }, 900);
    }

    private void handleOpenIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = null;
        String mime = intent.getType();
        try {
            if (Intent.ACTION_VIEW.equals(action)) uri = intent.getData();
            else if (Intent.ACTION_SEND.equals(action)) uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                java.util.ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null && !list.isEmpty()) uri = list.get(0);
            }
        } catch (Throwable ignored) { }
        if (uri == null) return;
        final Uri u = uri;
        final String fm = mime;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String name = queryDisplayName(u);
                    java.io.InputStream in = getContentResolver().openInputStream(u);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 80L * 1024 * 1024) break;
                        bos.write(buf, 0, n);
                    }
                    in.close();
                    if (bos.size() <= 0) return;
                    incoming = bos.toByteArray();
                    incomingName = name;
                    incomingMime = fm == null ? "" : fm;
                    runOnUiThread(new Runnable() {
                        public void run() { sendJs("window.__incomingNow&&window.__incomingNow()"); }
                    });
                } catch (Throwable ignored) { }
            }
        }).start();
    }

    private String queryDisplayName(Uri uri) {
        String name = null;
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (c.moveToFirst() && idx >= 0) name = c.getString(idx);
                c.close();
            }
        } catch (Throwable ignored) { }
        if (name == null || name.length() == 0) {
            String p = uri.getPath();
            if (p != null && p.lastIndexOf('/') >= 0) name = p.substring(p.lastIndexOf('/') + 1);
        }
        return (name == null || name.length() == 0) ? "收到的文件" : name;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 传输期间不让 WebView 停摆（否则切后台就断流）
        if (web != null) {
            try { web.onResume(); web.resumeTimers(); } catch (Exception ignored) { }
        }
    }

    /** 视频全屏/退出全屏时隐藏或恢复系统栏 */
    private void setFsImmersive(boolean on) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    if (on) {
                        c.hide(android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars());
                        c.setSystemBarsBehavior(
                                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    } else {
                        c.show(android.view.WindowInsets.Type.statusBars()
                                | android.view.WindowInsets.Type.navigationBars());
                    }
                }
            } else {
                View v = getWindow().getDecorView();
                if (on) {
                    v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                } else {
                    v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                }
            }
            if (on) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onBackPressed() {
        if (fsView != null) {
            try {
                android.widget.FrameLayout root = findViewById(android.R.id.content);
                root.removeView(fsView);
            } catch (Throwable ignored) { }
            fsView = null;
            setFsImmersive(false);
            try { web.setVisibility(View.VISIBLE); } catch (Throwable ignored) { }
            if (fsCb != null) { try { fsCb.onCustomViewHidden(); } catch (Throwable e) { } fsCb = null; }
            return;
        }
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try { if (web != null) web.resumeTimers(); } catch (Throwable ignored) { }
        try {
            if (web != null) web.evaluateJavascript("window.__hljjsAppResume&&window.__hljjsAppResume()", null);
        } catch (Throwable ignored) { }
    }

    private void startNetWatch() {
        try {
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try {
                                if (web != null) web.evaluateJavascript("window.__hljjsAppResume&&window.__hljjsAppResume()", null);
                            } catch (Throwable ignored) { }
                        }
                    });
                }
            });
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onDestroy() {
        try { stopSysPush(); } catch (Throwable ignored) { }
        if (server != null) server.stop();
        try { if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } } catch (Throwable ignored) { }
        super.onDestroy();
    }

    /* ==================== 手机一键部署公网 ==================== */
    private static volatile String phoneHubUrl = "";
    private static LocalProxy localProxy = null;

    public static synchronized void ensureLocalProxyStatic(android.content.Context ctx) {
        ensureLocalProxy();
    }

    private static synchronized void ensureLocalProxy() {
        if (localProxy != null) return;
        localProxy = new LocalProxy();
        new Thread(localProxy, "local-proxy").start();
        try { Thread.sleep(300); } catch (Throwable ignored) { }
    }
    private static volatile String phoneHubState = "idle";
    private static volatile Process phoneHubProc = null;
    private static volatile int phoneHubPort = 0;
    private static volatile Process phoneTunnelProc = null;

    private File phoneDepDir() {
        File d = new File(getFilesDir(), "phonedep");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void copyAsset(String name, File dst) throws Exception {
        if (dst.exists() && dst.length() > 0) return;
        java.io.InputStream in = getAssets().open(name);
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); }
        } finally { in.close(); }
    }

    private void copyAssetDir(String assetPath, File dst) throws Exception {
        String[] list = getAssets().list(assetPath);
        if (list == null || list.length == 0) return;
        dst.mkdirs();
        for (String name : list) {
            String child = assetPath + "/" + name;
            File out = new File(dst, name);
            String[] sub = getAssets().list(child);
            if (sub != null && sub.length > 0) copyAssetDir(child, out);
            else copyAsset(child, out);
        }
    }

    private String phoneHubStartImpl() {
        if ("running".equals(phoneHubState) || "tunnel".equals(phoneHubState)) return "already:" + phoneHubUrl;
        phoneHubState = "running";
        new Thread(new Runnable() {
            public void run() {
                try {
                    File dir = phoneDepDir();
                    copyAsset("serve.py", new File(dir, "serve.py"));
                    copyAsset("index.html", new File(dir, "index.html"));
                    copyAsset("sitecustomize.py", new File(dir, "sitecustomize.py"));
                    copyAsset("hubs.json", new File(dir, "hubs.json"));
                    copyAsset("cacert.pem", new File(dir, "cacert.pem"));

                    File stdlib = new File(dir, "stdlib14");
                    if (!new File(stdlib, "encodings").exists()) {
                        copyAssetDir("stdlib14", stdlib);
                    }
                    if (!new File(stdlib, "encodings").exists()) { phoneHubState = "err:stdlib"; return; }
                    String py = getApplicationInfo().nativeLibraryDir + "/libpython3bin.so";
                    String serve = new File(dir, "serve.py").getAbsolutePath();
                    int hubPort = 9100 + (int)(Math.random() * 90);
                    phoneHubPort = hubPort;
ProcessBuilder pb = new ProcessBuilder(py, serve, String.valueOf(hubPort));
                    pb.directory(dir);
                    pb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
                    pb.environment().put("PYTHONHOME", stdlib.getAbsolutePath());
                    pb.environment().put("HLJJS_NATIVE_LIB", getApplicationInfo().nativeLibraryDir);
                    pb.environment().put("PYTHONPATH", stdlib.getAbsolutePath() + ":" + dir.getAbsolutePath());
                    pb.environment().put("SSL_CERT_FILE", new File(dir, "cacert.pem").getAbsolutePath());
                    pb.environment().put("HLJJS_TUNNEL", "1");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    phoneHubProc = p;
                    Thread.sleep(2500);
                    if (!p.isAlive()) {
                        StringBuilder sb = new StringBuilder();
                        try {
                            java.io.BufferedReader er = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                            String ln; int n = 0;
                            while (n < 24 && (ln = er.readLine()) != null) { sb.append(ln).append(" / "); n++; }
                        } catch (Throwable ignored) { }
                        phoneHubState = "err:python:" + sb.toString().trim();
                        return;
                    }
                    // 先确保 VPN 起来：cloudflared 的 DNS 要走 TUN，否则会退回 127.0.0.1:53 解析失败
                    try {
                        Intent vi = android.net.VpnService.prepare(MainActivity.this);
                        if (vi != null) {
                            final Intent fvi = vi;
                            runOnUiThread(new Runnable() { public void run() { try { startActivityForResult(fvi, 1002); } catch (Throwable e) {} } });
                        } else {
                            startService(new Intent(MainActivity.this, VpnDnsService.class));
                        }
                    } catch (Throwable e) { }
                    // 等 tun0 真的出现（最多 15 秒），否则 cloudflared 一定解析失败
                    boolean tunOk = false;
                    for (int wi = 0; wi < 30; wi++) {
                        try {
                            if (java.net.NetworkInterface.getByName("tun0") != null) { tunOk = true; break; }
                        } catch (Throwable e) { }
                        try { Thread.sleep(500); } catch (Throwable e) { break; }
                    }
                    if (!tunOk) { phoneHubState = "err:vpn"; return; }
                    Thread.sleep(800);
                    String cf = getApplicationInfo().nativeLibraryDir + "/libcloudflared.so";
                    String lastErr = "";
                    for (int attempt = 0; attempt < 3; attempt++) {
                        if (attempt > 0) {
                            phoneHubState = "retry:" + attempt;
                            try { Thread.sleep(30000); } catch (Throwable ignored) { }
                        }
                        String hs = "http://127.0.0.1:" + hubPort;
                        ProcessBuilder cb = new ProcessBuilder(cf, "tunnel", "--url", hs, "--no-autoupdate", "--protocol", "http2", "--edge-ip-version", "4");
                        cb.directory(dir);
                        cb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
                        cb.environment().put("GODEBUG", "netdns=cgo");
                        ensureLocalProxy();
                        cb.environment().put("HTTP_PROXY", "http://127.0.0.1:" + LocalProxy.PORT);
                        cb.environment().put("HTTPS_PROXY", "http://127.0.0.1:" + LocalProxy.PORT);
                        cb.environment().put("ALL_PROXY", "http://127.0.0.1:" + LocalProxy.PORT);
                        cb.environment().put("SSL_CERT_FILE", new File(dir, "cacert.pem").getAbsolutePath());
                        cb.environment().put("HLJJS_TUN_DNS", VpnDnsService.TUN_DNS + ":53");
                        cb.redirectErrorStream(true);

                        final Process cp = cb.start();
                        phoneTunnelProc = cp;
                        phoneHubState = "tunnel";
                        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(cp.getInputStream()));
                        String line;
                        long until = System.currentTimeMillis() + 70000;
                        lastErr = "";
                        while (System.currentTimeMillis() < until && (line = br.readLine()) != null) {
                            String low = line.toLowerCase();
                            if (low.contains("429") || low.contains("tunnelstore")) lastErr = "Cloudflare 限流（429）";
                            else if (low.contains("failed to request quick tunnel") || low.contains("deadline exceeded")) lastErr = "连接 Cloudflare 超时";
                            else if (low.contains("error") && line.length() < 200 && lastErr.isEmpty()) lastErr = line.trim();
                            int i = line.indexOf("https://");
                            if (i >= 0) {
                                int j = line.indexOf(".trycloudflare.com", i);
                                if (j > 0) {
                                    String cand = line.substring(i, j + 18);
                                    if (!cand.contains("api.trycloudflare.com")) {
                                        phoneHubUrl = cand;
                                        phoneHubState = "ready";
                                        return;
                                    }
                                }
                            }
                        }
                        try { cp.destroy(); } catch (Throwable ignored) { }
                        phoneTunnelProc = null;
                        if (lastErr.isEmpty()) lastErr = "没拿到隧道地址（等待超时）";
                    }
                    phoneHubState = "err:tunnel:" + lastErr;

                } catch (Throwable e) {
                    phoneHubState = "err:" + e;
                }
            }
        }).start();
        return "started";
    }

    private String phoneHubStatusImpl() {
        return phoneHubState + "|" + phoneHubUrl + "|" + phoneHubPort;
    }

    /* ==================== 保存文件（JS 桥） ==================== */
    public class Bridge {

        /* 测试：app 进程能否绑 127.0.0.1:53（无 root） */
        @JavascriptInterface
        public String testBind53() {
            try {
                java.net.DatagramSocket ds = new java.net.DatagramSocket(53, java.net.InetAddress.getByName("127.0.0.1"));
                ds.close();
                return "ok";
            } catch (Throwable e) {
                return "err:" + e;
            }
        }

        /* 只关公网隧道：cloudflared 停掉，公网地址失效；中枢 serve.py 继续跑 */
        @JavascriptInterface
        public String phoneHubStop() {
            try { if (phoneTunnelProc != null) { phoneTunnelProc.destroy(); phoneTunnelProc = null; } } catch (Throwable e) { }
            phoneHubUrl = "";
            phoneHubState = (phoneHubProc != null && phoneHubProc.isAlive()) ? "hub-only" : "idle";
            return "stopped";
        }

        /* 验证用：启动 VpnDnsService（TUN 层拦 DNS 包） */
        @JavascriptInterface
        public String startVpnDns() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = android.net.VpnService.prepare(MainActivity.this);
                        if (i != null) startActivityForResult(i, 1002);
                        else startService(new Intent(MainActivity.this, VpnDnsService.class));
                    } catch (Throwable e) { }
                }
            });
            return "started";
        }

        /* 手机一键部署公网：解压内置 python 运行时，起 serve.py 9000 + cloudflared 隧道 */
        @JavascriptInterface
        public String phoneHubStart() {
            return phoneHubStartImpl();
        }

        @JavascriptInterface
        public String phoneHubStatus() {
            return phoneHubStatusImpl();
        }

        /* 手机当中心枢：让 Termux 执行 phone-hub.sh（需 Termux 开 allow-external-apps） */
        @JavascriptInterface
        public String termuxHub() {
            try {
                Intent i = new Intent();
                i.setClassName("com.termux", "com.termux.app.RunCommandService");
                i.setAction("com.termux.RUN_COMMAND");
                i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
                i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", "bash ~/storage/downloads/phone-hub.sh"});
                i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
                i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
                MainActivity.this.startService(i);
                return "sent";
            } catch (Throwable e) {
                return "err:" + e;
            }
        }
        /* ---------- 磁链下载（libtorrent4j） ---------- */
        @JavascriptInterface
        public String btAdd(String magnet) {
            try { return TorrentBridge.add(MainActivity.this, magnet); }
            catch (Throwable e) { return "{\"ok\":false,\"error\":\"" + String.valueOf(e).replace("\"", "'") + "\"}"; }
        }

        @JavascriptInterface
        public String btList() {
            try { return TorrentBridge.list(); } catch (Throwable e) { return "[]"; }
        }

        @JavascriptInterface
        public String btPause(String id) {
            try { return TorrentBridge.pause(id); } catch (Throwable e) { return "err:" + e; }
        }

        @JavascriptInterface
        public String btResume(String id) {
            try { return TorrentBridge.resume(id); } catch (Throwable e) { return "err:" + e; }
        }

        @JavascriptInterface
        public String btRemove(String id, boolean delFiles) {
            try { return TorrentBridge.remove(MainActivity.this, id, delFiles); } catch (Throwable e) { return "err:" + e; }
        }

        @JavascriptInterface
        public String btFiles(String id) {
            try { return TorrentBridge.files(id); } catch (Throwable e) { return "[]"; }
        }

        /* 分享时记录文件 MIME（sharePrepare 写、shareOpen 用） */
        private final java.util.HashMap<String, String> SHARE_MIME = new java.util.HashMap<String, String>();

        /* ---------- 分享文件给其他 App（QQ/微信等） ----------
 * 两步：sharePrepare 把 base64 写进 filesDir/shared/；shareOpen 拉起系统分享面板。
 * 批量分享时对每个文件调 prepare，最后 shareOpen 传文件名数组（ACTION_SEND_MULTIPLE）。
 */
        @JavascriptInterface
        public String sharePrepare(String name, String mime, String b64) {
            try {
                String safe = safeName(name);
                if (safe.isEmpty()) return "badname";
                byte[] data = android.util.Base64.decode(String.valueOf(b64), android.util.Base64.DEFAULT);
                java.io.File dir = SharedProvider.dir(MainActivity.this);
                java.io.File f = new java.io.File(dir, safe);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                try { fo.write(data); } finally { fo.close(); }
                try { SHARE_MIME.put(safe, mime == null || mime.isEmpty() ? guessMime(safe) : mime); } catch (Throwable ignored) { }
                return "ok:" + f.length();
            } catch (Throwable e) { return "err:" + e; }
        }

        @JavascriptInterface
        public String shareOpen(String namesJson, String title) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(String.valueOf(namesJson));
                final java.util.ArrayList<android.net.Uri> uris = new java.util.ArrayList<android.net.Uri>();
                for (int i = 0; i < arr.length(); i++) {
                    String n = safeName(arr.optString(i));
                    if (n.isEmpty()) continue;
                    java.io.File f = new java.io.File(SharedProvider.dir(MainActivity.this), n);
                    if (!f.exists()) continue;
                    uris.add(SharedProvider.uriFor(n));
                }
                if (uris.isEmpty()) return "empty";
                final String n0 = uris.size() == 1 ? safeName(arr.optString(0)) : "";
                String mime0 = "application/octet-stream";
                try {
                    String m = SHARE_MIME.get(n0);
                    if (m != null && !m.isEmpty()) mime0 = m;
                    else if (n0 != null && !n0.isEmpty()) mime0 = guessMime(n0);
                } catch (Throwable ignored) { }
                final String fMime = mime0;
                final String t = title == null || title.isEmpty() ? "分享到…" : title;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            Intent i;
                            if (uris.size() == 1) {
                                i = new Intent(Intent.ACTION_SEND);
                                i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                                i.setType(fMime);
                                i.putExtra(Intent.EXTRA_SUBJECT, n0);
                                i.putExtra(Intent.EXTRA_TEXT, n0);
                            } else {
                                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                                i.setType("*/*");
                            }
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            Intent c = Intent.createChooser(i, t);
                            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(c);
                        } catch (Throwable ignored) { }
                    }
                });
                return "ok:" + uris.size();
            } catch (Throwable e) { return "err:" + e; }
        }

        private String safeName(String name) {
            String n = String.valueOf(name == null ? "" : name).trim();
            n = n.replace('/', '_').replace('\\', '_').replace("..", "_");
            if (n.length() > 120) n = n.substring(n.length() - 120);
            return n;
        }

        @JavascriptInterface
        public String btNetReset() {
            try { return TorrentBridge.networkReset(); } catch (Throwable e) { return "err:" + e; }
        }

        @JavascriptInterface
        public String shareText(String text) {
            try {
                final String t = String.valueOf(text == null ? "" : text);
                if (t.isEmpty()) return "empty";
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            Intent i = new Intent(Intent.ACTION_SEND);
                            i.setType("text/plain");
                            i.putExtra(Intent.EXTRA_TEXT, t);
                            Intent c = Intent.createChooser(i, "用其他 App 打开");
                            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(c);
                        } catch (Throwable ignored) { }
                    }
                });
                return "ok";
            } catch (Throwable e) { return "err:" + e; }
        }

        @JavascriptInterface
        public String btPurge() {
            try { return TorrentBridge.purge(MainActivity.this); } catch (Throwable e) { return "err:" + e; }
        }

        @JavascriptInterface
        public String btAddTracker(String id, String url) {
            try { return TorrentBridge.addTracker(id, url); } catch (Throwable e) { return "err:" + e; }
        }

        @JavascriptInterface
        public String btDiag() {
            try { return TorrentBridge.diag(); } catch (Throwable e) { return "{\"error\":\"" + String.valueOf(e) + "\"}"; }
        }

        @JavascriptInterface
        public String btExport(String id) {
            try { return TorrentBridge.exportToDownloads(MainActivity.this, id); } catch (Throwable e) { return "err:" + e; }
        }

        /* 读剪贴板（Android 10+ 只有前台/可见时能读，正好符合"回到前台才检测"的用法） */
        @JavascriptInterface
        public String readClipboard() {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                android.content.ClipData cd = cm.getPrimaryClip();
                if (cd == null || cd.getItemCount() == 0) return "";
                CharSequence t = cd.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Throwable e) {
                return "";
            }
        }

        @JavascriptInterface
        public void notifyMessage(final String sid, final String title, final String body) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    MsgNotify.show(MainActivity.this, sid, title, body);
                }
            });
        }

        @JavascriptInterface
        public void cancelNotify(final String sid) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    MsgNotify.cancel(MainActivity.this, sid);
                }
            });
        }

        /** 刷新页面：用 WebView.reload()，别让 JS 的 location.reload() 走 URL 拦截逻辑 */
        @JavascriptInterface
        public void reloadPage() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try { if (web != null) web.reload(); } catch (Throwable ignored) { }
                }
            });
        }

        /** 通知诊断：开关 / 频道重要性 / 会不会弹横幅 */
        @JavascriptInterface
        public String notifInfo() {
            try { return MsgNotify.diag(MainActivity.this); } catch (Throwable e) { return "{}"; }
        }

        /** 延后发一条通知：JS 定时器进后台会被节流，验证横幅时用这个（原生 Handler 不会被节流） */
        @JavascriptInterface
        public void notifyDelay(final String sid, final String title, final String body, final int ms) {
            try {
                new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { MsgNotify.show(MainActivity.this, sid, title, body); }
                }, Math.max(0, ms));
            } catch (Throwable ignored) { }
        }

        /** 跳到系统「本应用的通知」设置页，让用户能把「横幅」打开 */
        @JavascriptInterface
        public void openNotificationSettings() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        Intent i = new Intent(Build.VERSION.SDK_INT >= 26
                                ? android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                : android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
                        i.putExtra("app_package", getPackageName());
                        i.putExtra("app_uid", getApplicationInfo().uid);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable e) {
                        try {
                            Intent s = new Intent(android.provider.Settings.ACTION_SETTINGS);
                            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(s);
                        } catch (Throwable ignored) { }
                    }
                }
            });
        }

        @JavascriptInterface
        public void setSpeaker(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        android.media.AudioManager am =
                            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                        if (am == null) return;
                        if (on) {
                            am.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
                            am.setSpeakerphoneOn(true);
                        } else {
                            am.setSpeakerphoneOn(false);
                            am.setMode(android.media.AudioManager.MODE_NORMAL);
                        }
                    } catch (Throwable ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void requestIgnoreBattery() {
            try {
                if (Build.VERSION.SDK_INT >= 23) {
                    Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Throwable ignored) { }
        }

        /* 后台待机开关：网页在「有活动连接」时开、「全部空闲」时关 */
        @JavascriptInterface
        public void setStandby(boolean on) {
            final android.content.Context c = MainActivity.this;
            Intent i = new Intent(c, KeepAliveService.class);
            try {
                if (on) {
                    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                    else c.startService(i);
                } else {
                    c.stopService(i);
                }
            } catch (Throwable ignored) { }
        }

        /* statusInfo() 是同步过桥调用：游戏里 5 秒一次、听书 20 秒、书架 15 秒。
           以前每次都 registerReceiver(ACTION_BATTERY_CHANGED) + getSignalStrength()，
           这两步跨进程、信号差时可到几十毫秒 —— 表现就是"每隔几秒固定一顿"。
           这里缓存 20 秒：同一个展示周期内只真查一次。 */
        private org.json.JSONObject statusCache = null;
        private long statusAt = 0;
        /* 当前已经给 TTS 引擎设过的语言：相同就跳过 setLanguage/getVoices（见 speak） */
        private String voiceLangSet = "";
    /** 系统里装了哪些 TTS 引擎（含系统首选），给网页列出来选 */
    @JavascriptInterface
    public String ttsEngines() {
        StringBuilder sb = new StringBuilder("[");
        try {
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
            try {
                String dflt = android.provider.Settings.Secure.getString(
                        getContentResolver(), android.provider.Settings.Secure.TTS_DEFAULT_SYNTH);
                if (dflt != null && dflt.length() > 0) seen.add(dflt);
            } catch (Throwable ignored) { }
            try {
                java.util.List<android.content.pm.ResolveInfo> list = getPackageManager().queryIntentServices(
                        new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0);
                for (android.content.pm.ResolveInfo ri : list) {
                    if (ri.serviceInfo == null) continue;
                    seen.add(ri.serviceInfo.packageName);
                }
            } catch (Throwable ignored) { }
            boolean first = true;
            for (String pkg : seen) {
                String label = pkg;
                try {
                    android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                    label = String.valueOf(getPackageManager().getApplicationLabel(ai));
                } catch (Throwable ignored) { }
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"pkg\":\"").append(escJson(pkg)).append("\",\"name\":\"").append(escJson(label)).append("\"}");
            }
        } catch (Throwable ignored) { }
        sb.append("]");
        return sb.toString();
    }

    /** 在当前引擎里按名字挑一个声音（VOICEVOX 的角色名等） */
    @JavascriptInterface
    public boolean ttsSetVoiceId(String id) {
        try {
            if (tts == null || id == null || id.length() == 0) return false;
            java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
            if (vs != null) for (android.speech.tts.Voice v : vs) {
                if (v != null && id.equals(v.getName())) {
                    int r = tts.setVoice(v);
                    voiceLangSet = "";
                    return r == TextToSpeech.SUCCESS;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** 换一个 TTS 引擎（传空 = 系统默认） */
    @JavascriptInterface
    public void setTtsEngine(String pkg) {
        try {
            if (tts != null) {
                try { tts.stop(); } catch (Throwable ignored) { }
                try { tts.shutdown(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        tts = null; ttsReady = false;
        voiceLangSet = "";
        ttsEngineName = (pkg == null) ? "" : pkg;
        try {
            tts = new TextToSpeech(MainActivity.this, ttsMakeListener(), (pkg == null || pkg.length() == 0) ? null : pkg);
        } catch (Throwable e) {
            try { tts = new TextToSpeech(MainActivity.this, ttsMakeListener()); } catch (Throwable ignored) { }
        }
    }



        /* ---- 蓝牙搭桥 ---- */
        @JavascriptInterface
        public boolean btAvailable() { try { return bt != null && bt.available() && bt.enabled(); } catch (Throwable e) { return false; } }

        @JavascriptInterface
        public boolean btPermitted() { try { return bt != null && bt.permitted(); } catch (Throwable e) { return false; } }

        @JavascriptInterface
        public void btAsk() { try { if (bt != null) bt.requestPermission(); } catch (Throwable e) { } }

        @JavascriptInterface
        public String btDevices() { try { return bt == null ? "[]" : bt.list(); } catch (Throwable e) { return "[]"; } }

        @JavascriptInterface
        public String btState() { try { return bt == null ? "idle" : bt.state(); } catch (Throwable e) { return "idle"; } }

        @JavascriptInterface
        public String btListen() { try { return bt == null ? "off" : bt.listen(); } catch (Throwable e) { return "err"; } }

        @JavascriptInterface
        public String btConnect(String address) { try { return bt == null ? "off" : bt.connect(address); } catch (Throwable e) { return "err"; } }

        @JavascriptInterface
        public boolean btSend(String text) { try { return bt != null && bt.send(text); } catch (Throwable e) { return false; } }

        @JavascriptInterface
        public void btStop() { try { if (bt != null) bt.stop(); } catch (Throwable e) { } }

        /** "1/0" + "1/0"：浏览器任务是否存活 / 是否在前台 */
        @JavascriptInterface
        public String browserTask() {
            try {
                return (BrowserActivity.ALIVE ? "1" : "0") + (BrowserActivity.FG ? "1" : "0");
            } catch (Throwable e) {
                return "00";
            }
        }

        @JavascriptInterface
        public void setStatusBar(final String color) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        getWindow().setStatusBarColor(android.graphics.Color.parseColor(color));
                    } catch (Throwable ignored) { }
                }
            });
        }

        @JavascriptInterface
        public boolean startBackground(String text) {
            try {
                Intent i = new Intent(MainActivity.this, TransferService.class);
                i.putExtra("text", text);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean stopBackground() {
            try {
                stopService(new Intent(MainActivity.this, TransferService.class));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void speak(String text, String lang, int rate, int seq) {
            try {
                if (tts == null) return;
                Locale loc;
                if ("ja".equals(lang)) loc = Locale.JAPAN;
                else if ("ko".equals(lang)) loc = Locale.KOREA;
                else if ("en".equals(lang)) loc = Locale.US;
                else loc = Locale.SIMPLIFIED_CHINESE;
                // setLanguage + getVoices() 都是跨进程调用（引擎慢时单次几十毫秒），
                // 以前每一句都做一遍，听书时就是"每读完一句顿一下"。同一种语言只选一次。
                if (!String.valueOf(lang).equals(voiceLangSet)) {
                    int lr = tts.setLanguage(loc);
                    // 显式挑一个同语言的声音，避免引擎停在中文音色上
                    try {
                        android.speech.tts.Voice best = null;
                        java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
                        if (vs != null) {
                            for (android.speech.tts.Voice v : vs) {
                                if (v.getLocale() != null
                                        && loc.getLanguage().equals(v.getLocale().getLanguage())) { best = v; break; }
                            }
                        }
                        if (best != null) tts.setVoice(best);
                    } catch (Throwable ignored) { }
                    voiceLangSet = String.valueOf(lang);
                    android.util.Log.w("HLJJS_TTS", "speak lang=" + lang + " setLanguage=" + lr
                            + " voice=" + String.valueOf(tts.getVoice()));
                }
                float r = Math.max(0.5f, Math.min(2.0f, rate / 100.0f));
                tts.setSpeechRate(r);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, String.valueOf(seq));
            } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public boolean pickBooks() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                                "text/plain", "text/html", "application/epub+zip",
                                "application/zip", "application/x-zip-compressed",
                                "application/octet-stream", "image/*"});
                        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(i, REQ_BOOKS);
                    } catch (Throwable ignored) { }
                }
            });
            return true;
        }

        @JavascriptInterface
        public void downloadMedia(final String url, final String name) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        String fn = name == null || name.length() == 0
                                ? ("media-" + System.currentTimeMillis())
                                : name.replace("/", "_").replace("\\", "_").replace("..", "_");
                        android.app.DownloadManager dm = (android.app.DownloadManager)
                                getSystemService(android.content.Context.DOWNLOAD_SERVICE);
                        android.app.DownloadManager.Request req =
                                new android.app.DownloadManager.Request(android.net.Uri.parse(url));
                        req.setTitle(fn);
                        req.setDescription("互联网精神与世长存");
                        req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                                "互联网精神与世长存/" + fn);
                        req.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                        dm.enqueue(req);
                        Toast.makeText(MainActivity.this, "已开始下载：" + fn, Toast.LENGTH_SHORT).show();
                    } catch (Throwable e) {
                        Toast.makeText(MainActivity.this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public void setBrowserSkin(String json) {
            browserSkin = json == null ? "" : json;
        }

        @JavascriptInterface
        public void openBrowser(final String url) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(MainActivity.this, BrowserActivity.class);
                        i.putExtra("url", url == null ? "" : url);
                        i.putExtra("skin", browserSkin);
                        // 浏览器跑在自己的任务里：最小化只把它收到后台，网页和标签都还在
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable ignored) { }
                }
            });
        }

        @JavascriptInterface
        public void pickImage() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_PICK,
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                        i.setType("image/*");
                        startActivityForResult(i, REQ_PICK_IMG);
                    } catch (Throwable e) {
                        try {
                            Intent i2 = new Intent(Intent.ACTION_GET_CONTENT);
                            i2.setType("image/*");
                            i2.addCategory(Intent.CATEGORY_OPENABLE);
                            startActivityForResult(i2, REQ_PICK_IMG);
                        } catch (Throwable ignored) { }
                    }
                }
            });
        }

        @JavascriptInterface
        public String lanIp() {
            try {
                java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
                while (en.hasMoreElements()) {
                    java.net.NetworkInterface ni = en.nextElement();
                    try {
                        if (!ni.isUp() || ni.isLoopback()) continue;
                    } catch (Throwable e) { continue; }
                    String name = ni.getName() == null ? "" : ni.getName();
                    if (!name.startsWith("wlan")) continue;
                    java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        java.net.InetAddress a = addrs.nextElement();
                        if (a instanceof java.net.Inet4Address) {
                            String ip = a.getHostAddress();
                            if (ip != null && !ip.startsWith("127.")) return ip;
                        }
                    }
                }
            } catch (Throwable ignored) { }
            return "";
        }

        @JavascriptInterface
        public String incomingInfo() {
            try {
                if (incoming == null) return "null";
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", incomingName);
                o.put("size", incoming.length);
                o.put("mime", incomingMime);
                return o.toString();
            } catch (Throwable e) { return "null"; }
        }

        @JavascriptInterface
        public String incomingChunk(int off, int len) {
            try {
                if (incoming == null || off < 0 || off >= incoming.length) return "";
                int n = Math.min(len, incoming.length - off);
                return android.util.Base64.encodeToString(incoming, off, n, android.util.Base64.NO_WRAP);
            } catch (Throwable e) { return ""; }
        }

        @JavascriptInterface
        public void incomingClear() {
            incoming = null;
            incomingName = "";
            incomingMime = "";
            Uri next = pickQueue.poll();
            if (next != null) readUriAsync(next, "");
        }

        @JavascriptInterface
        public boolean incomingInject(String name, String b64) {
            try {
                incoming = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                incomingName = name;
                incomingMime = "";
                return true;
            } catch (Throwable e) { return false; }
        }

        @JavascriptInterface
        public String ttsCheck(String lang) {
            try {
                if (tts == null) return "{\"ready\":false}";
                java.util.Locale loc = "ja".equals(lang) ? java.util.Locale.JAPAN
                        : ("en".equals(lang) ? java.util.Locale.US : java.util.Locale.SIMPLIFIED_CHINESE);
                int r = tts.isLanguageAvailable(loc);
                int set = tts.setLanguage(loc);
                Object v = tts.getVoice();
                return "{\"avail\":" + r + ",\"set\":" + set
                        + ",\"voice\":" + org.json.JSONObject.quote(v == null ? "" : v.toString()) + "}";
            } catch (Throwable e) {
                return "{\"err\":" + org.json.JSONObject.quote(String.valueOf(e)) + "}";
            }
        }

        @JavascriptInterface
        public String ttsVoices() {
            org.json.JSONArray arr = new org.json.JSONArray();
            try {
                if (tts != null) {
                    java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
                    if (vs != null) {
                        for (android.speech.tts.Voice v : vs) {
                            org.json.JSONObject o = new org.json.JSONObject();
                            o.put("name", v.getName());
                            o.put("lang", v.getLocale() == null ? "" : v.getLocale().toString());
                            arr.put(o);
                        }
                    }
                }
            } catch (Throwable ignored) { }
            return arr.toString();
        }

        @JavascriptInterface
        public boolean ttsSetGender(String g) {
            try {
                if (tts == null) return false;
                java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
                if (vs == null || vs.isEmpty()) return false;
                android.speech.tts.Voice pick = null;
                for (android.speech.tts.Voice v : vs) {
                    String n = (v.getName() + " " + v.getFeatures()).toLowerCase();
                    if ("f".equals(g) && (n.contains("female") || n.contains("woman"))) { pick = v; break; }
                    if ("m".equals(g) && n.contains("male") && !n.contains("female")) { pick = v; break; }
                }
                if (pick == null) {
                    java.util.ArrayList<android.speech.tts.Voice> list =
                            new java.util.ArrayList<android.speech.tts.Voice>(vs);
                    pick = list.get("f".equals(g) ? 0 : Math.min(1, list.size() - 1));
                }
                tts.setVoice(pick);
                return true;
            } catch (Throwable e) { return false; }
        }

        @JavascriptInterface
        public String statusInfo() {
            // 页面只在启动时主动拉一次，之后走 push（见 pushSysStatus）；这里的缓存是防手滑重复调用的兜底
            long nowMs = System.currentTimeMillis();
            if (statusCache != null && nowMs - statusAt < 20000) return statusCache.toString();
            statusCache = statusObject();
            statusAt = nowMs;
            return statusCache.toString();
        }

        @JavascriptInterface
        public boolean ttsReady() { return ttsReady; }

        @JavascriptInterface
        public void ttsSettings() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                    catch (Throwable e) {
                        try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Throwable ignored) { }
                    }
                }
            });
        }

        @JavascriptInterface
        public String ttsEngine() { return ttsEngineName; }

        @JavascriptInterface
        public void stopSpeak() {
            try { if (tts != null) tts.stop(); } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void mediaStart(String title) {
            try {
                Intent i = new Intent(MainActivity.this, MediaService.class);
                i.putExtra("title", title);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void mediaUpdate(String title, boolean playing, int pos, int total) {
            try { MediaService.update(title, playing, pos, total); } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void mediaStop() {
            try { stopService(new Intent(MainActivity.this, MediaService.class)); } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void orientation(String mode) {
            try {
                if ("landscape".equals(mode)) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                else if ("portrait".equals(mode)) setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                else setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void immersive(final boolean on) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            android.view.WindowInsetsController c = getWindow().getInsetsController();
                            if (c != null) {
                                if (on) {
                                    c.hide(android.view.WindowInsets.Type.statusBars()
                                         | android.view.WindowInsets.Type.navigationBars());
                                    c.setSystemBarsBehavior(
                                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                                } else {
                                    c.show(android.view.WindowInsets.Type.statusBars()
                                         | android.view.WindowInsets.Type.navigationBars());
                                }
                            }
                        } else {
                            android.view.View d = getWindow().getDecorView();
                            if (on) d.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                            else d.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_VISIBLE);
                        }
                    } catch (Throwable ignored) { }
                }
            });
        }

        @JavascriptInterface
        public boolean hasCamera() {
            return Build.VERSION.SDK_INT < 23 ||
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void askCamera() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= 23) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean begin(String name) {
            try {
                String safe = (name == null || name.length() == 0) ? "file" : name;
                safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (safe.length() > 120) safe = safe.substring(0, 120);
                pendingName = safe;
                sink = openSink(safe);
                return sink != null;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean chunk(String b64) {
            try {
                if (sink == null) return false;
                sink.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }


        /** 输入法是否已安装 */
        @JavascriptInterface
        public boolean imeInstalled() {
            try {
                getPackageManager().getPackageInfo("com.hljjs.ime", 0);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** 输入法是否已在系统里启用 */
        @JavascriptInterface
        public boolean imeEnabled() {
            try {
                String list = android.provider.Settings.Secure.getString(
                        getContentResolver(), android.provider.Settings.Secure.ENABLED_INPUT_METHODS);
                return list != null && list.contains("com.hljjs.ime");
            } catch (Exception e) {
                return false;
            }
        }

        /** 打开系统的输入法启用页 */
        @JavascriptInterface
        public boolean openImeSettings() {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** 直接弹出输入法选择器（切到我们的输入法） */
        @JavascriptInterface
        public boolean pickIme() {
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                if (imm == null) return false;
                imm.showInputMethodPicker();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** 列出输入法里收藏的表情包文件名 */
        @JavascriptInterface
        public String listImeStickers() {
            StringBuilder sb = new StringBuilder("[");
            try {
                android.database.Cursor c = getContentResolver().query(
                        android.net.Uri.parse("content://com.hljjs.ime.share"), null, null, null, null);
                if (c != null) {
                    int ni = c.getColumnIndex("name");
                    boolean first = true;
                    while (c.moveToNext()) {
                        String n = ni >= 0 ? c.getString(ni) : null;
                        if (n == null) continue;
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("\"").append(n.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                    }
                    c.close();
                }
            } catch (Exception ignored) { }
            return sb.append("]").toString();
        }


        /* ---------- 原生 TCP 直连 ---------- */
        private DirectLink direct;
        private String cachedMyId = "";

        private DirectLink direct() {
            if (direct == null) {
                direct = new DirectLink(new DirectLink.Sink() {
                    @Override public void onEvent(String json) {
                        final String js = "window.__directEvent && window.__directEvent(" + json + ")";
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                try { web.evaluateJavascript(js, null); } catch (Exception ignored) { }
                            }
                        });
                    }
                    @Override public String myId() { return cachedMyId; }
                });
            }
            return direct;
        }

        @JavascriptInterface
        public String directStart(String myId) {
            if (myId != null) cachedMyId = myId;
            try { return direct().start(); } catch (Exception e) { return "启动失败：" + e.getMessage(); }
        }

        /** 当前是否开着 VPN（开着的话 WebRTC 会绑到 VPN 网卡，局域网直连基本不通） */
        @JavascriptInterface
        public boolean vpnOn() {
            try {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                android.net.Network[] nets = cm.getAllNetworks();
                for (android.net.Network n : nets) {
                    android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    if (c != null && c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true;
                }
            } catch (Throwable e) { }
            return false;
        }

        @JavascriptInterface
        public void directScanLan() { try { direct().scanLan(); } catch (Throwable e) { } }

        @JavascriptInterface
        public String directDial(String host, int port) {
            try { return direct().dial(host, port); } catch (Exception e) { return "连接失败：" + e.getMessage(); }
        }

        @JavascriptInterface
        public int directSend(String text) {
            try { return direct().sendAll(text); } catch (Exception e) { return 0; }
        }

        @JavascriptInterface
        public String directStatus() {
            try { return direct().status(); } catch (Exception e) { return "{}"; }
        }


        /* ---------- 短信通道 ---------- */
        @JavascriptInterface
        public boolean smsAllowed() {
            try {
                return checkSelfPermission("android.permission.SEND_SMS") == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission("android.permission.RECEIVE_SMS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void smsAsk() {
            try {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            requestPermissions(new String[]{
                                "android.permission.SEND_SMS",
                                "android.permission.RECEIVE_SMS"
                            }, 9001);
                        } catch (Exception ignored) { }
                    }
                });
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String smsSend(String number, String text) {
            try {
                if (!smsAllowed()) return "没权限";
                SmsManager sm = SmsManager.getDefault();
                java.util.ArrayList<String> parts = sm.divideMessage(text);
                sm.sendMultipartTextMessage(number, null, parts, null, null);
                return "已发送 " + parts.size() + " 条";
            } catch (Exception e) {
                return "发送失败：" + e.getMessage();
            }
        }

        @JavascriptInterface
        public String end() {
            try {
                if (sink != null) { sink.flush(); sink.close(); sink = null; }
                copyToShared();
                if (pendingUri != null) {
                    lastSavedUri = pendingUri;
                    return "下载/互联网精神与世长存/" + pendingName;
                }
                if (pendingFile != null) return pendingFile.getAbsolutePath();
                return pendingName;
            } catch (Exception e) {
                return "";
            }
        }

        /** 同时存一份到 filesDir/shared/，输入法可以通过内容提供者读到 */
        private void copyToShared() {
            try {
                if (pendingName == null) return;
                File dst = new File(SharedProvider.dir(MainActivity.this), pendingName);
                if (pendingFile != null && pendingFile.exists()) {
                    java.io.FileInputStream in = new java.io.FileInputStream(pendingFile);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close(); out.close();
                    return;
                }
                if (pendingUri != null) {
                    java.io.InputStream in = getContentResolver().openInputStream(pendingUri);
                    if (in == null) return;
                    java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close(); out.close();
                }
            } catch (Exception ignored) { }
        }


        /** 打开系统文件管理器：优先 App 的下载目录，其次「下载」，最后系统下载界面 */
        @JavascriptInterface
        public boolean openFileManager() {
            try {
                String[] docs = new String[]{
                    "primary:Download/互联网精神与世长存",
                    "primary:Download"
                };
                for (String doc : docs) {
                    try {
                        Uri u = android.provider.DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents", doc);
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(u, "vnd.android.document/directory");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        return true;
                    } catch (Exception ignored) { }
                }
                try {
                    Intent k = new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS);
                    k.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(k);
                    return true;
                } catch (Exception ignored) { }
                Toast.makeText(MainActivity.this, "系统没有可用的文件管理器", Toast.LENGTH_SHORT).show();
                return false;
            } catch (Exception e) { return false; }
        }

        /** APK 的 content:// 地址：优先 SharedProvider（filesDir/shared），回退到 MediaStore */
        private Uri apkUri() {
            try {
                if (pendingName != null) {
                    File f = new File(SharedProvider.dir(MainActivity.this), pendingName);
                    if (f.exists() && f.length() > 0) return SharedProvider.uriFor(pendingName);
                }
            } catch (Exception ignored) { }
            return lastSavedUri;
        }

        /** APK：走 PackageInstaller，并在「未知来源」未授权时引导用户去设置 */
        private boolean openInstaller(Uri uri) {
            try {
                if (uri == null) return false;
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        if (!getPackageManager().canRequestPackageInstalls()) {
                            try {
                                Intent st = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + getPackageName()));
                                st.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(st);
                                Toast.makeText(MainActivity.this,
                                        "请允许「互联网精神与世长存」安装应用，然后再点一次打开",
                                        Toast.LENGTH_LONG).show();
                            } catch (Exception e) {
                                Toast.makeText(MainActivity.this, "需要在系统设置里允许安装未知应用", Toast.LENGTH_LONG).show();
                            }
                            return true;
                        }
                    } catch (Exception ignored) { }
                }
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { i.setClipData(android.content.ClipData.newRawUri("", uri)); } catch (Throwable ignored) { }
                startActivity(i);
                return true;
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(MainActivity.this, "系统没有能安装 APK 的应用", Toast.LENGTH_LONG).show();
                return false;
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "打开安装器失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                return false;
            }
        }

        private boolean isApkName(String n) {
            return n != null && n.toLowerCase().endsWith(".apk");
        }

        /** 把刚刚保存的文件交给系统，让用户选模拟器打开 */
        @JavascriptInterface
        public boolean openLast() {
            if (lastSavedUri == null) return false;
            if (isApkName(pendingName)) return openInstaller(apkUri());
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(lastSavedUri, "application/octet-stream");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** 按扩展名给对 MIME，再交给系统打开（图片/PDF/音频比 octet-stream 更容易找到应用） */
        @JavascriptInterface
        public boolean openSaved() {
            if (isApkName(pendingName)) return openInstaller(apkUri());
            if (lastSavedUri == null) return false;
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(lastSavedUri, guessMime(pendingName));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** 网页触发的下载：后台抓取，落到「下载/互联网精神与世长存」 */
    private void downloadToDownloads(final String url, final String userAgent, final String contentDisposition) {
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                InputStream in = null;
                OutputStream out = null;
                String name = fileNameFrom(contentDisposition, url);
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
                    int code = c.getResponseCode();
                    if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
                    in = new BufferedInputStream(c.getInputStream());
                    out = openSink(name);
                    if (out == null) throw new IOException("无法写入下载目录");
                    byte[] buf = new byte[65536];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                    out.close();
                    out = null;
                    in.close();
                    in = null;
                    final String msg = "已保存：" + name + "（" + (total / 1024) + " KB）";
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    try { if (out != null) out.close(); } catch (Exception ignored) { }
                    try { if (in != null) in.close(); } catch (Exception ignored) { }
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    private static String fileNameFrom(String cd, String url) {
        String name = null;
        if (cd != null) {
            Matcher m = Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) {
                try { name = URLDecoder.decode(m.group(1), "UTF-8"); } catch (Exception ignored) { }
            }
            if (name == null) {
                Matcher m2 = Pattern.compile("filename=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
                if (m2.find()) name = m2.group(1);
            }
        }
        if (name == null && url != null) {
            String p = url.split("\\?")[0];
            int i = p.lastIndexOf('/');
            name = i >= 0 ? p.substring(i + 1) : p;
        }
        if (name == null || name.isEmpty()) name = "download-" + System.currentTimeMillis();
        return name;
    }

    /* ---- 原始字节上传口（/__put/*）：页面直接把二进制 POST 进来，绕开 base64 过桥 ---- */
    boolean putBegin(String name) {
        try {
            String safe = (name == null || name.length() == 0) ? "file" : name;
            safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (safe.length() > 120) safe = safe.substring(0, 120);
            pendingName = safe;
            putTotal = 0;
            if (sink != null) { try { sink.close(); } catch (Exception ignored) { } sink = null; }
            sink = openSink(safe);
            return sink != null;
        } catch (Exception e) {
            return false;
        }
    }

    boolean putWrite(byte[] b, int n) {
        try {
            if (sink == null || n <= 0) return false;
            sink.write(b, 0, n);
            putTotal += n;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    String putEnd() {
        try {
            if (sink != null) { sink.flush(); sink.close(); sink = null; }
            // 小文件才复制一份到 filesDir/shared（输入法要读）；大文件复制等于再写一遍，跳过
            if (putTotal > 0 && putTotal < 8L * 1024 * 1024) copySharedSafe();
            if (pendingUri != null) {
                lastSavedUri = pendingUri;
                return "下载/互联网精神与世长存/" + pendingName;
            }
            if (pendingFile != null) return pendingFile.getAbsolutePath();
            return pendingName;
        } catch (Exception e) {
            return "";
        }
    }

    private void copySharedSafe() {
        try {
            if (pendingName == null) return;
            File dst = new File(SharedProvider.dir(this), pendingName);
            if (pendingFile != null && pendingFile.exists()) {
                java.io.FileInputStream in = new java.io.FileInputStream(pendingFile);
                java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close(); out.close();
                return;
            }
            if (pendingUri != null) {
                java.io.InputStream in = getContentResolver().openInputStream(pendingUri);
                if (in == null) return;
                java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close(); out.close();
            }
        } catch (Exception ignored) { }
    }

    private OutputStream openSink(String name) throws Exception {
        pendingUri = null;
        pendingFile = null;

        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, guessMime(name));
            v.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/互联网精神与世长存");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            pendingUri = uri;
            return getContentResolver().openOutputStream(uri);
        }

        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "互联网精神与世长存");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, name);
        pendingFile = f;
        return new BufferedOutputStream(new FileOutputStream(f));
    }

    private static String guessMime(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".gif")) return "image/gif";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".mp4")) return "video/mp4";
        if (l.endsWith(".webm")) return "video/webm";
        if (l.endsWith(".mkv")) return "video/x-matroska";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".m4a")) return "audio/mp4";
        if (l.endsWith(".wav")) return "audio/wav";
        if (l.endsWith(".ogg")) return "audio/ogg";
        if (l.endsWith(".flac")) return "audio/flac";
        if (l.endsWith(".pdf")) return "application/pdf";
        if (l.endsWith(".txt")) return "text/plain";
        if (l.endsWith(".zip")) return "application/zip";
        if (l.endsWith(".epub")) return "application/epub+zip";
        if (l.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    /* ==================== 极简静态服务器 ====================
       用 http://127.0.0.1 而不是 file:// —— 这样是「安全上下文」，
       摄像头 / localStorage / Service Worker 才正常。 */
    static String jsStr(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static volatile String browserSkin = "";
    static volatile WebView appWeb = null;

    /** 从浏览器页返回，打开「工具」窗口（笔记本/收藏/嗅探） */
    public static void openToolsFromBrowser() {
        final WebView w = appWeb;
        if (w == null) return;
        w.post(new Runnable() {
            public void run() {
                w.evaluateJavascript("window.__openTools && window.__openTools()", null);
            }
        });
    }

    static class AssetServer implements Runnable {
        private final AssetManager assets;
        private final MainActivity act;
        private final int port;
        private volatile boolean running = true;
        private ServerSocket ss;

        AssetServer(MainActivity act, AssetManager a, int port) {
            this.act = act;
            this.assets = a;
            this.port = port;
        }

        void start() {
            Thread t = new Thread(this);
            t.setDaemon(true);
            t.start();
        }

        void stop() {
            running = false;
            try { if (ss != null) ss.close(); } catch (Exception ignored) { }
        }

        @Override
        public void run() {
            try {
                ss = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
                while (running) {
                    final Socket c = ss.accept();
                    Thread t = new Thread(new Runnable() {
                        @Override public void run() { handle(c); }
                    });
                    t.setDaemon(true);
                    t.start();
                }
            } catch (Exception ignored) { }
        }

        private void handle(Socket c) {
            try {
                BufferedInputStream in = new BufferedInputStream(c.getInputStream());
                ByteArrayOutputStream hb = new ByteArrayOutputStream();
                int p3 = -1, p2 = -1, p1 = -1, cur;
                while ((cur = in.read()) != -1) {
                    hb.write(cur);
                    if (p3 == '\r' && p2 == '\n' && p1 == '\r' && cur == '\n') break;
                    p3 = p2; p2 = p1; p1 = cur;
                    if (hb.size() > 16384) break;
                }

                String req = new String(hb.toByteArray(), "UTF-8");
                String path = "/";
                String[] first = req.split("\r\n")[0].split(" ");
                if (first.length >= 2) path = first[1];
                String fullPath = path;
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                if (path.equals("/")) path = "/index.html";

                if (path.startsWith("/__proxy")) { proxy(c, fullPath); return; }
                if (path.startsWith("/__put")) { put(c, in, req, fullPath); return; }
                if (path.equals("/__size")) { serveSize(c, fullPath); return; }

                InputStream is;
                String assetPath = path.startsWith("/") ? path.substring(1) : path;
                try {
                    is = assets.open(assetPath);
                } catch (Exception e1) {
                    // 备用一：有的构建会把资产多套一层 assets/
                    try {
                        is = assets.open("assets/" + assetPath);
                    } catch (Exception e2) {
                        // 备用二：目录 / 子路径写法差异
                        try {
                            is = assets.open(assetPath.replace("//", "/"));
                        } catch (Exception e3) {
                            byte[] nf = ("not found: " + assetPath + " (" + e3 + ")").getBytes("UTF-8");
                            String h = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                                    "Cache-Control: no-store\r\nContent-Length: " + nf.length +
                                    "\r\nConnection: close\r\n\r\n";
                            c.getOutputStream().write(h.getBytes("UTF-8"));
                            c.getOutputStream().write(nf);
                            c.close();
                            return;
                        }
                    }
                }

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                byte[] data = bos.toByteArray();

                // 全站跨源隔离：PPSSPP 的 iframe 需要 SharedArrayBuffer
                String extra = path.startsWith("/plugins/ppsspp")
                        ? ("Cross-Origin-Opener-Policy: same-origin\r\n" +
                           "Cross-Origin-Embedder-Policy: require-corp\r\n" +
                           "Cross-Origin-Resource-Policy: cross-origin\r\n")
                        : "";
                String head = "HTTP/1.1 200 OK\r\nContent-Type: " + mime(path) +
                        "\r\nContent-Length: " + data.length +
                        "\r\nCache-Control: no-cache\r\n" + extra + "Connection: close\r\n\r\n";
                OutputStream os = c.getOutputStream();
                os.write(head.getBytes("UTF-8"));
                os.write(data);
                os.flush();
                c.close();
            } catch (Exception e) {
                try { c.close(); } catch (Exception ignored) { }
            }
        }

        /** 查媒体文件大小（HEAD） */
        private void serveSize(Socket c, String fullPath) {
            try {
                String q = fullPath.contains("?") ? fullPath.substring(fullPath.indexOf('?') + 1) : "";
                String target = null;
                for (String kv : q.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0 && "u".equals(kv.substring(0, eq))) target = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                }
                if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
                    sendProxy(c, "application/json; charset=utf-8", "{\"ok\":false}".getBytes("UTF-8"));
                    return;
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
                long len = conn.getContentLength();
                String ct = String.valueOf(conn.getContentType());
                String js = "{\"ok\":true,\"size\":" + (len > 0 ? len : 0) + ",\"type\":\"" + ct.replace("\"", "") + "\"}";
                sendProxy(c, "application/json; charset=utf-8", js.getBytes("UTF-8"));
            } catch (Throwable e) {
                try { sendProxy(c, "application/json; charset=utf-8", "{\"ok\":false}".getBytes("UTF-8")); } catch (Throwable ignored) { }
            }
        }

        /** 内置浏览器的反向代理：去掉禁止内嵌的响应头，并注入 base + 链接拦截 */
        /* 原始字节上传：页面把收到的文件块直接 POST 进来，省掉 base64 和 JS 桥 */
        private void put(Socket c, BufferedInputStream in, String req, String fullPath) {
            try {
                String op = fullPath;
                String query = "";
                int q = op.indexOf('?');
                if (q >= 0) { query = op.substring(q + 1); op = op.substring(0, q); }

                if (op.equals("/__put/begin")) {
                    String name = "file";
                    if (query.length() > 0) {
                        for (String kv : query.split("&")) {
                            int eq = kv.indexOf('=');
                            if (eq > 0 && "name".equals(kv.substring(0, eq))) {
                                name = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                            }
                        }
                    }
                    boolean ok = act.putBegin(name);
                    sendJson(c, "{\"ok\":" + ok + "}");
                    return;
                }

                int len = 0;
                String[] lines = req.split("\r\n");
                for (int i = 1; i < lines.length; i++) {
                    String ln = lines[i];
                    int colon = ln.indexOf(':');
                    if (colon > 0 && "content-length".equalsIgnoreCase(ln.substring(0, colon).trim())) {
                        try { len = Integer.parseInt(ln.substring(colon + 1).trim()); } catch (Exception ignored) { }
                    }
                }

                byte[] buf = new byte[128 * 1024];
                int left = len;
                boolean ok = true;
                while (left > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, left));
                    if (n <= 0) { ok = false; break; }
                    if (!act.putWrite(buf, n)) { ok = false; break; }
                    left -= n;
                }

                if (op.equals("/__put/end")) {
                    String p = act.putEnd();
                    sendJson(c, "{\"ok\":true,\"path\":\"" + jsonEsc(p) + "\"}");
                } else {
                    sendJson(c, "{\"ok\":" + ok + ",\"got\":" + (len - left) + "}");
                }
            } catch (Exception e) {
                try { sendJson(c, "{\"ok\":false}"); } catch (Exception ignored) { }
            }
        }

        private void sendJson(Socket c, String body) throws Exception {
            byte[] b = body.getBytes("UTF-8");
            String h = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Cache-Control: no-store\r\nContent-Length: " + b.length +
                    "\r\nConnection: close\r\n\r\n";
            OutputStream os = c.getOutputStream();
            os.write(h.getBytes("UTF-8"));
            os.write(b);
            os.flush();
            try { c.close(); } catch (Exception ignored) { }
        }

        private static String jsonEsc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private void proxy(Socket c, String fullPath) {
            try {
                String q = fullPath.contains("?") ? fullPath.substring(fullPath.indexOf('?') + 1) : "";
                String target = null;
                for (String kv : q.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0 && "u".equals(kv.substring(0, eq))) {
                        target = URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                    }
                }
                if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
                    sendProxy(c, "text/html; charset=utf-8",
                            "<!doctype html><meta charset=utf-8><body style=\"font:13px sans-serif;padding:16px\">地址不对</body>".getBytes("UTF-8"));
                    return;
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(18000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6");
                conn.setRequestProperty("Accept-Encoding", "identity");
                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (is != null) {
                    byte[] buf = new byte[8192];
                    int n; long total = 0;
                    while ((n = is.read(buf)) > 0) {
                        total += n;
                        if (total > 6L * 1024 * 1024) break;
                        bos.write(buf, 0, n);
                    }
                    is.close();
                }
                byte[] data = bos.toByteArray();
                String ctype = conn.getContentType();
                if (ctype == null || ctype.length() == 0) ctype = "text/html; charset=utf-8";
                String finalUrl = conn.getURL().toString();
                boolean wantDl = q.contains("dl=1");
                if (ctype.toLowerCase().contains("text/html")) {
                    String html = new String(data, "UTF-8");
                    html = injectProxy(html, finalUrl);
                    data = html.getBytes("UTF-8");
                    ctype = "text/html; charset=utf-8";
                }
                String disp = null;
                if (wantDl) {
                    String fn = finalUrl.split("\\?")[0];
                    int sl = fn.lastIndexOf('/');
                    if (sl >= 0) fn = fn.substring(sl + 1);
                    try { fn = URLDecoder.decode(fn, "UTF-8"); } catch (Throwable ignored) { }
                    if (fn.length() == 0) fn = "download";
                    disp = "attachment; filename=\"" + fn.replace("\"", "") + "\"";
                }
                sendProxy(c, ctype, data, disp);
            } catch (Throwable e) {
                try {
                    String msg = String.valueOf(e.getMessage()).replace("<", "&lt;");
                    sendProxy(c, "text/html; charset=utf-8",
                            ("<!doctype html><meta charset=utf-8><body style=\"font:13px sans-serif;padding:16px\">"
                                    + "<b>打不开这个网址</b><p style=\"color:#a00\">" + msg + "</p></body>").getBytes("UTF-8"));
                } catch (Throwable ignored) { }
            }
        }

        private void sendProxy(Socket c, String ctype, byte[] data) throws IOException {
            sendProxy(c, ctype, data, null);
        }

        private void sendProxy(Socket c, String ctype, byte[] data, String disp) throws IOException {
            String head = "HTTP/1.1 200 OK\r\nContent-Type: " + ctype +
                    "\r\nContent-Length: " + data.length +
                    "\r\nCache-Control: no-store" +
                    "\r\nCross-Origin-Resource-Policy: cross-origin" +
                    "\r\nCross-Origin-Embedder-Policy: require-corp" +
                    "\r\nCross-Origin-Opener-Policy: same-origin" +
                    (disp != null ? ("\r\nContent-Disposition: " + disp) : "") +
                    "\r\nConnection: close\r\n\r\n";
            OutputStream os = c.getOutputStream();
            os.write(head.getBytes("UTF-8"));
            os.write(data);
            os.flush();
            c.close();
        }

        private static String injectProxy(String html, String base) {
            String sniff = "<script>(function(){var m={};function r(u,k){try{if(!u||m[u])return;if(!/^https?:/i.test(u))return;m[u]=1;parent.postMessage({type:'hljjs-media',url:u,kind:k||''},'*');}catch(e){}}" +
                    "function is(u){return /\\.(mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|wav|flac|m3u8|mpd|ts)(\\?|$)/i.test(u||'');}" +
                    "try{var f=window.fetch;if(f){window.fetch=function(){try{var a=arguments[0];var u=typeof a==='string'?a:(a&&a.url);if(is(u))r(u,'fetch');}catch(e){}return f.apply(this,arguments);};}}catch(e){}" +
                    "try{var o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(mm,u){try{if(is(u))r(u,'xhr');}catch(e){}return o.apply(this,arguments);};}catch(e){}" +
                    "function scan(){try{document.querySelectorAll('video,audio,source').forEach(function(el){var u=el.currentSrc||el.src||el.getAttribute('src');if(is(u))r(u,el.tagName.toLowerCase());});" +
                    "document.querySelectorAll('a[href]').forEach(function(a){if(is(a.href))r(a.href,'link');});}catch(e){}}" +
                    "document.addEventListener('DOMContentLoaded',scan);setTimeout(scan,1200);setTimeout(scan,3500);" +
                    "try{new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}})();</script>";
            String inject = "<base href=\"" + base + "\">" +
                    "<script>(function(){document.addEventListener('click',function(e){" +
                    "var a=e.target.closest&&e.target.closest('a');if(!a)return;" +
                    "var h=a.getAttribute('href');if(!h||h.charAt(0)==='#'||h.indexOf('javascript:')===0)return;" +
                    "try{var abs=new URL(h,document.baseURI).href;if(!/^https?:/.test(abs))return;" +
                    "e.preventDefault();parent.postMessage({type:'hljjs-proxy',url:abs},'*');}catch(err){}},true);})();</script>";
            String low = html.toLowerCase(Locale.ROOT);
            int i = low.indexOf("<head");
            if (i >= 0) {
                int j = html.indexOf('>', i);
                if (j > 0) return html.substring(0, j + 1) + inject + html.substring(j + 1);
            }
            return inject + sniff + html;
        }

        private static String mime(String p) {
            String l = p.toLowerCase(Locale.ROOT);
            if (l.endsWith(".html")) return "text/html; charset=utf-8";
            if (l.endsWith(".js")) return "text/javascript; charset=utf-8";
            if (l.endsWith(".css")) return "text/css; charset=utf-8";
            if (l.endsWith(".json")) return "application/json; charset=utf-8";
            if (l.endsWith(".webmanifest")) return "application/manifest+json";
            if (l.endsWith(".png")) return "image/png";
            if (l.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }
    }
}
