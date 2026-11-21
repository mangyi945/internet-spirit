package com.hljjs.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用内浏览器：顶部工具条用网页（browser_chrome.html，和主界面同一套 CSS），
 * 下面放真正的 WebView 显示网页，两边通过 Bridge 通信。
 */
public class BrowserActivity extends Activity {

    private static final String HOME = "https://www.baidu.com/";
    /** 供主界面任务栏查询：浏览器任务是否还活着 / 是否在前台 */
    static volatile boolean ALIVE = false;
    static volatile boolean FG = false;

    private static final String CHROME_URL = "http://127.0.0.1:8642/browser_chrome.html";

    private WebView chrome;
    private WebView statusBar;
    private FrameLayout webBox;
    private boolean chromeReady = false;
    private boolean statusReady = false;

    private final List<Tab> tabs = new ArrayList<Tab>();
    private int active = -1;
    private String home = HOME;
    private boolean incog = false;
    private String skinJson = "";

    private int cFace = Color.rgb(192, 192, 192);
    private int cSh = Color.rgb(128, 128, 128);
    private final List<String[]> media = new ArrayList<String[]>();

    static class Tab {
        WebView web;
        String url = "";
        String title = "新标签页";
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ALIVE = true;
        String url = getIntent() == null ? "" : getIntent().getStringExtra("url");
        if (url == null) url = "";
        skinJson = getIntent() == null ? "" : getIntent().getStringExtra("skin");
        if (skinJson == null) skinJson = "";
        applySkin(skinJson);
        setSystemBars(cFace);
        home = getSharedPreferences("hljjs", MODE_PRIVATE).getString("home", HOME);
        if (home == null || home.trim().length() == 0) home = HOME;

        final boolean hasUrl = url.trim().length() > 0;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;
        root.setBackgroundDrawable(new WindowFrame(cFace, density));
        root.setPadding(dp(2), dp(2), dp(2), dp(2));

        chrome = new WebView(this);
        chrome.setBackgroundColor(cFace);
        chrome.setVerticalScrollBarEnabled(false);
        chrome.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings cs = chrome.getSettings();
        cs.setJavaScriptEnabled(true);
        cs.setDomStorageEnabled(true);
        cs.setCacheMode(WebSettings.LOAD_NO_CACHE);
        chrome.addJavascriptInterface(new Bridge(), "Bridge");
        chrome.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                chromeReady = true;
                fitBar(view, 104);
                view.postDelayed(new Runnable() {
                    public void run() { fitBar(chrome, 104); }
                }, 250);
                syncChrome();
            }
        });
        chrome.loadUrl(CHROME_URL);
        root.addView(chrome, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)));

        webBox = new FrameLayout(this);
        webBox.setBackgroundDrawable(new InsetBorder(density));
        webBox.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wlp.setMargins(0, 0, 0, 0);
        root.addView(webBox, wlp);

        statusBar = new WebView(this);
        statusBar.setBackgroundColor(cFace);
        statusBar.setVerticalScrollBarEnabled(false);
        statusBar.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings ss = statusBar.getSettings();
        ss.setJavaScriptEnabled(true);
        ss.setDomStorageEnabled(true);
        ss.setCacheMode(WebSettings.LOAD_NO_CACHE);
        statusBar.addJavascriptInterface(new Bridge(), "Bridge");
        statusBar.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String u) {
                statusReady = true;
                fitBar(view, 26);
                view.postDelayed(new Runnable() {
                    public void run() { fitBar(statusBar, 26); }
                }, 250);
                syncChrome();
            }
        });
        statusBar.loadUrl(CHROME_URL + "?m=status");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
        slp.setMargins(0, 0, 0, 0);
        root.addView(statusBar, slp);

        setContentView(root);

        if (!hasUrl) {
            String raw = getSharedPreferences("hljjs", MODE_PRIVATE).getString("tabs", "[]");
            int act = getSharedPreferences("hljjs", MODE_PRIVATE).getInt("tabsActive", 0);
            boolean restored = false;
            try {
                JSONArray a = new JSONArray(raw);
                for (int i = 0; i < a.length() && i < 8; i++) {
                    JSONObject o = a.getJSONObject(i);
                    String u = o.optString("url", "");
                    if (u.length() == 0) continue;
                    newTab(u);
                    restored = true;
                }
                if (restored) switchTab(Math.max(0, Math.min(act, tabs.size() - 1)));
            } catch (Throwable ignored) { }
            if (!restored) newTab(home);
        } else {
            newTab(url);
        }
    }

    /** 从主界面再次点「站点」时走这里：已有实例直接回到前台，页面不重建 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        setIntent(intent);
        String skin = intent.getStringExtra("skin");
        if (skin != null && skin.length() > 0 && !skin.equals(skinJson)) {
            skinJson = skin;
            applySkin(skin);
            setSystemBars(cFace);
            syncChrome();
        }
        String url = intent.getStringExtra("url");
        if (url != null && url.trim().length() > 0) newTab(url.trim());
    }

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private Tab cur() {
        if (active < 0 || active >= tabs.size()) return null;
        return tabs.get(active);
    }

    class Bridge {
        @JavascriptInterface
        public void cmd(final String line) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try { handleCmd(line); } catch (Throwable ignored) { }
                }
            });
        }
    }

    private void handleCmd(String line) {
        if (line == null) return;
        int bar = line.indexOf('|');
        String c = bar >= 0 ? line.substring(0, bar) : line;
        String a = bar >= 0 ? line.substring(bar + 1) : "";
        Tab t = cur();
        switch (c) {
            case "back": if (t != null && t.web.canGoBack()) t.web.goBack(); break;
            case "fwd": if (t != null && t.web.canGoForward()) t.web.goForward(); break;
            case "reload": if (t != null) t.web.reload(); break;
            case "home": if (t != null) t.web.loadUrl(home); break;
            case "go": openInput(a); break;
            case "newtab": newTab(home); break;
            case "tab": try { switchTab(Integer.parseInt(a)); } catch (Throwable e) { } break;
            case "closetab": try { closeTab(Integer.parseInt(a)); } catch (Throwable e) { } break;
            case "fav": addFavorite(); break;
            case "favlist": favoritesDialog(); break;
            case "sniff": sniffDialog(); break;
            case "dl":
                try { startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)); } catch (Throwable ignored) { }
                break;
            case "manage": manageDialog(); break;
            case "slot": try { openSlot(Integer.parseInt(a)); } catch (Throwable e) { } break;
            case "incog":
                incog = !incog;
                syncChrome();
                Toast.makeText(this, incog ? "无痕模式已开" : "无痕模式已关", Toast.LENGTH_SHORT).show();
                break;
            case "min":
                // 只是把浏览器这个任务收到后台：网页、标签、播放都不销毁
                moveTaskToBack(true);
                break;
            case "max":
                try {
                    if ((getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0) {
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    } else {
                        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    }
                } catch (Throwable ignored) { }
                break;
            case "close":
                // 关闭才真的结束并清掉标签
                if (android.os.Build.VERSION.SDK_INT >= 21) finishAndRemoveTask();
                else finish();
                break;
        }
    }

    private void openInput(String v) {
        Tab t = cur();
        if (t == null) return;
        String u = v == null ? "" : v.trim();
        if (u.length() == 0) { t.web.loadUrl(home); return; }
        if (!u.startsWith("http://") && !u.startsWith("https://") && u.indexOf(" ") < 0 && u.indexOf(".") >= 0) {
            u = "http://" + u;
        }
        if (u.startsWith("http://") || u.startsWith("https://")) t.web.loadUrl(u);
        else t.web.loadUrl("https://cn.bing.com/search?q=" + Uri.encode(u));
    }

    private static String jsq(String s) {
        if (s == null) s = "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
    }

    private void js(String code) {
        if (chrome == null) return;
        try { chrome.evaluateJavascript(code, null); } catch (Throwable ignored) { }
    }

    private void jsStatus(String code) {
        if (statusBar == null) return;
        try { statusBar.evaluateJavascript(code, null); } catch (Throwable ignored) { }
    }

    /** 网页工具条按内容自适应高度；viewport 下 CSS px 就是 dp */
    private void fitBar(final WebView v, final int fallbackDp) {
        if (v == null) return;
        try {
            v.evaluateJavascript(
                "(function(){var b=document.body;" +
                "if(!b) return 0;" +
                "return Math.ceil(b.getBoundingClientRect().height || b.offsetHeight || 0);})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String s) {
                        try {
                            if (s == null) return;
                            s = s.replace("\"", "").trim();
                            int h = (int) Math.ceil(Double.parseDouble(s));
                            if (h < 12 || h > 260) h = fallbackDp;
                            ViewGroup.LayoutParams lp = v.getLayoutParams();
                            if (lp != null && lp.height != dp(h)) {
                                lp.height = dp(h);
                                v.setLayoutParams(lp);
                            }
                        } catch (Throwable ignored) { }
                    }
                });
        } catch (Throwable ignored) { }
    }

    private void syncChrome() {
        if (!chromeReady || chrome == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Tab t : tabs) arr.put(t.title.length() > 0 ? t.title : "新标签页");
            js("Chrome.setTabs(" + arr.toString() + "," + Math.max(0, active) + ");");
            Tab t = cur();
            String url = t == null ? "" : t.url;
            js("Chrome.setUrl('" + jsq(url) + "');");
            js("Chrome.setTitle('" + jsq(t == null || t.title.length() == 0 ? "浏览器" : t.title) + "');");
            js("Chrome.setStatus('" + jsq(url.length() == 0 ? "就绪" : url) + "');");
            js("Chrome.setIncog(" + (incog ? "true" : "false") + ");");
            js("Chrome.setSkin('" + jsq(skinJson) + "');");
            jsStatus("Chrome.setUrl('" + jsq(url) + "');");
            jsStatus("Chrome.setIncog(" + (incog ? "true" : "false") + ");");
            jsStatus("Chrome.setSkin('" + jsq(skinJson) + "');");
            renderSlots();
        } catch (Throwable ignored) { }
    }

    private void renderSlots() {
        List<String[]> favs = loadFavs();
        JSONArray arr = new JSONArray();
        for (int i = 0; i < 4; i++) {
            String label = "常用";
            if (i < favs.size()) {
                label = favs.get(i)[0];
                if (label == null || label.length() == 0) label = favs.get(i)[1];
                if (label.length() > 6) label = label.substring(0, 6) + "…";
            }
            arr.put(label);
        }
        js("Chrome.setSlots(" + arr.toString() + ");");
    }

    private void newTab(String url) {
        final Tab t = new Tab();
        t.url = url == null ? "" : url;

        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new MediaBridge(), "HljjsMedia");
        w.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String u, String ua, String cd, String mime, long len) {
                downloadUrl(u);
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title != null && title.length() > 0) {
                    t.title = title;
                    if (t == cur()) syncChrome();
                }
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                return handleUrl(t, u);
            }
            @Override
            public void onPageStarted(WebView view, String u, android.graphics.Bitmap favicon) {
                if (u != null) { t.url = u; if (t == cur()) syncChrome(); }
            }
            @Override
            public void onPageFinished(WebView view, String u) {
                if (u != null) { t.url = u; if (!incog) saveTabs(); if (t == cur()) syncChrome(); }
                try { view.evaluateJavascript(SNIFF_JS, null); } catch (Throwable ignored) { }
            }
        });
        t.web = w;
        w.setVisibility(View.GONE);
        webBox.addView(w, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tabs.add(t);
        switchTab(tabs.size() - 1);
        if (t.url.length() > 0) w.loadUrl(t.url);
    }

    private boolean handleUrl(Tab t, String u) {
        if (u == null) return false;
        String low = u.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")) return false;
        try {
            if (low.startsWith("bilibili://video/")) {
                String tail = u.substring("bilibili://video/".length());
                int q = tail.indexOf('?');
                if (q > 0) tail = tail.substring(0, q);
                t.web.loadUrl("https://www.bilibili.com/video/" + tail);
                return true;
            }
            if (low.startsWith("bilibili://")) {
                t.web.loadUrl("https://www.bilibili.com/");
                return true;
            }
            if (low.startsWith("intent://")) {
                try { startActivity(Intent.parseUri(u, Intent.URI_INTENT_SCHEME)); } catch (Throwable ignored) { }
            }
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    private void switchTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        active = i;
        for (int k = 0; k < tabs.size(); k++) tabs.get(k).web.setVisibility(k == i ? View.VISIBLE : View.GONE);
        if (!incog) saveTabs();
        syncChrome();
    }

    private void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        Tab t = tabs.get(i);
        try { webBox.removeView(t.web); t.web.destroy(); } catch (Throwable ignored) { }
        tabs.remove(i);
        if (tabs.isEmpty()) { newTab(home); return; }
        switchTab(Math.max(0, Math.min(i, tabs.size() - 1)));
    }

    private void saveTabs() {
        if (incog) return;
        try {
            JSONArray a = new JSONArray();
            for (Tab t : tabs) {
                if (t.url == null || t.url.length() == 0) continue;
                JSONObject o = new JSONObject();
                o.put("url", t.url);
                o.put("title", t.title);
                a.put(o);
            }
            getSharedPreferences("hljjs", MODE_PRIVATE).edit()
                    .putString("tabs", a.toString())
                    .putInt("tabsActive", Math.max(0, active))
                    .commit();
        } catch (Throwable ignored) { }
    }

    private void clearTabs() {
        try {
            getSharedPreferences("hljjs", MODE_PRIVATE).edit()
                    .remove("tabs").remove("tabsActive").commit();
        } catch (Throwable ignored) { }
    }

    private List<String[]> loadFavs() {
        List<String[]> out = new ArrayList<String[]>();
        try {
            String raw = getSharedPreferences("hljjs", MODE_PRIVATE).getString("favs", "[]");
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("title", ""), o.optString("url", "")});
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private void saveFavs(List<String[]> list) {
        try {
            JSONArray a = new JSONArray();
            for (String[] f : list) {
                JSONObject o = new JSONObject();
                o.put("title", f[0]);
                o.put("url", f[1]);
                a.put(o);
            }
            getSharedPreferences("hljjs", MODE_PRIVATE).edit().putString("favs", a.toString()).commit();
        } catch (Throwable ignored) { }
    }

    private void addFavorite() {
        Tab t = cur();
        if (t == null) return;
        String u = t.url;
        if (u == null || u.trim().length() == 0) {
            Toast.makeText(this, "当前页没有网址", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String[]> l = loadFavs();
        for (String[] f : l) if (f[1].equals(u)) {
            Toast.makeText(this, "已经在收藏夹里了", Toast.LENGTH_SHORT).show();
            return;
        }
        l.add(0, new String[]{t.title, u});
        saveFavs(l);
        renderSlots();
        Toast.makeText(this, "已收藏", Toast.LENGTH_SHORT).show();
    }

    private void openSlot(int i) {
        List<String[]> favs = loadFavs();
        if (i >= favs.size()) { favoritesDialog(); return; }
        Tab t = cur();
        if (t != null && favs.get(i)[1].length() > 0) t.web.loadUrl(favs.get(i)[1]);
    }

    private void favoritesDialog() {
        final List<String[]> favs = loadFavs();
        if (favs.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("收藏夹")
                    .setMessage("收藏夹是空的。\n点工具条的「收藏」把当前页加进来。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] labels = new String[favs.size()];
        for (int i = 0; i < favs.size(); i++) labels[i] = favs.get(i)[0].length() > 0 ? favs.get(i)[0] : favs.get(i)[1];
        new AlertDialog.Builder(this).setTitle("收藏")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Tab t = cur();
                        if (t != null) t.web.loadUrl(favs.get(which)[1]);
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    private void manageDialog() {
        final String[] items = {"设为默认页（当前页）", "恢复默认页（百度）", "收藏夹", "无痕模式：" + (incog ? "开" : "关")};
        new AlertDialog.Builder(this).setTitle("管理")
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            Tab t = cur();
                            if (t == null || t.url == null || t.url.length() == 0) {
                                Toast.makeText(BrowserActivity.this, "当前页没有网址", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            home = t.url;
                            getSharedPreferences("hljjs", MODE_PRIVATE).edit().putString("home", home).commit();
                            Toast.makeText(BrowserActivity.this, "已设为默认页", Toast.LENGTH_SHORT).show();
                        } else if (which == 1) {
                            home = HOME;
                            getSharedPreferences("hljjs", MODE_PRIVATE).edit().putString("home", home).commit();
                            Toast.makeText(BrowserActivity.this, "默认页已恢复为百度", Toast.LENGTH_SHORT).show();
                        } else if (which == 2) {
                            favoritesDialog();
                        } else {
                            incog = !incog;
                            syncChrome();
                            Toast.makeText(BrowserActivity.this, incog ? "无痕模式已开" : "无痕模式已关", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    public class MediaBridge {
        @JavascriptInterface
        public void found(final String url, final String kind) {
            if (url == null || url.length() == 0) return;
            synchronized (media) {
                for (String[] m : media) if (m[0].equals(url)) return;
                media.add(new String[]{url, kind == null ? "" : kind});
            }
        }
    }

    private void sniffDialog() {
        final Tab t = cur();
        if (t == null) return;
        t.web.evaluateJavascript(SCAN_JS, new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String v) {
                try {
                    if (v != null && v.startsWith("[")) {
                        JSONArray a = new JSONArray(v);
                        for (int i = 0; i < a.length(); i++) {
                            JSONArray pair = a.getJSONArray(i);
                            String url = pair.optString(0, "");
                            String kind = pair.optString(1, "");
                            if (url.length() == 0) continue;
                            synchronized (media) {
                                boolean have = false;
                                for (String[] m : media) if (m[0].equals(url)) { have = true; break; }
                                if (!have) media.add(new String[]{url, kind});
                            }
                        }
                    }
                } catch (Throwable ignored) { }
                showSniffList();
            }
        });
    }

    private void showSniffList() {
        final List<String[]> list;
        synchronized (media) { list = new ArrayList<String[]>(media); }
        if (list.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("媒体嗅探")
                    .setMessage("这个页面里没找到媒体地址。\n可以先播放页面里的视频/音频，再点一次嗅探。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            String u = list.get(i)[0];
            int slash = u.lastIndexOf('/');
            String name = slash >= 0 ? u.substring(slash + 1) : u;
            labels[i] = (list.get(i)[1].length() > 0 ? ("[" + list.get(i)[1] + "] ") : "") + name;
        }
        new AlertDialog.Builder(this).setTitle("媒体嗅探（" + list.size() + "）")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) { downloadUrl(list.get(which)[0]); }
                })
                .setNegativeButton("关闭", null).show();
    }

    private void downloadUrl(String u) {
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager)
                    getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request req =
                    new android.app.DownloadManager.Request(Uri.parse(u));
            String fn = android.webkit.URLUtil.guessFileName(u, null, null);
            req.setTitle(fn);
            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS,
                    "互联网精神与世长存/" + fn);
            Tab t = cur();
            if (t != null) req.addRequestHeader("User-Agent", t.web.getSettings().getUserAgentString());
            req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(u));
            dm.enqueue(req);
            Toast.makeText(this, "开始下载：" + fn, Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static final String SCAN_JS =
            "(function(){var out=[],seen={};" +
            "function add(u,k){try{if(!u||seen[u])return;if(!/^https?:/i.test(u))return;seen[u]=1;out.push([u,k||'']);}catch(e){}}" +
            "function is(u){return /\\.(mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|wav|flac|m3u8|mpd|ts)(\\?|$)/i.test(u||'');}" +
            "try{document.querySelectorAll('video,audio,source').forEach(function(el){var u=el.currentSrc||el.src||el.getAttribute('src');if(u&&is(u))add(u,el.tagName.toLowerCase());});}catch(e){}" +
            "try{performance.getEntriesByType('resource').forEach(function(e){if(is(e.name))add(e.name,'net');});}catch(e){}" +
            "try{document.querySelectorAll('a[href]').forEach(function(a){if(is(a.href))add(a.href,'link');});}catch(e){}" +
            "return out;})()";

    private static final String SNIFF_JS =
            "(function(){if(window.__hljjsHook)return;window.__hljjsHook=1;var seen={};" +
            "function rep(u,k){try{if(!u||seen[u])return;if(!/^https?:/i.test(u))return;seen[u]=1;" +
            "if(window.HljjsMedia)HljjsMedia.found(u,k||'');}catch(e){}}" +
            "function is(u){return /\\.(mp4|m4v|webm|mkv|mov|mp3|m4a|aac|ogg|wav|flac|m3u8|mpd|ts)(\\?|$)/i.test(u||'');}" +
            "try{var f=window.fetch;if(f){window.fetch=function(){try{var a=arguments[0];var u=typeof a==='string'?a:(a&&a.url);if(is(u))rep(u,'fetch');}catch(e){}return f.apply(this,arguments);};}}catch(e){}" +
            "try{var o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){try{if(is(u))rep(u,'xhr');}catch(e){}return o.apply(this,arguments);};}catch(e){}" +
            "function scan(){try{document.querySelectorAll('video,audio,source').forEach(function(el){var u=el.currentSrc||el.src||el.getAttribute('src');if(is(u))rep(u,el.tagName.toUpperCase());});}catch(e){}}" +
            "document.addEventListener('DOMContentLoaded',scan);setTimeout(scan,1200);setTimeout(scan,3500);" +
            "try{new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}})();";

    private void applySkin(String json) {
        if (json == null || json.length() == 0) return;
        try {
            JSONObject o = new JSONObject(json);
            cFace = Color.parseColor(o.optString("face", "#c0c0c0"));
            cSh = Color.parseColor(o.optString("sh", "#808080"));
        } catch (Throwable ignored) { }
    }

    /** 系统状态栏 / 导航栏用皮肤底色，别让系统做半透明毛玻璃 */
    private void setSystemBars(int color) {
        try {
            android.view.Window w = getWindow();
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(color);
            w.setNavigationBarColor(color);
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        FG = true;
        ALIVE = true;
    }

    @Override
    protected void onPause() {
        FG = false;
        if (!incog) saveTabs();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        Tab t = cur();
        if (t != null && t.web.canGoBack()) { t.web.goBack(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        ALIVE = false;
        FG = false;
        for (Tab t : tabs) {
            try { t.web.stopLoading(); t.web.destroy(); } catch (Throwable ignored) { }
        }
        tabs.clear();
        try { if (statusBar != null) statusBar.destroy(); } catch (Throwable ignored) { }
        try { if (chrome != null) chrome.destroy(); } catch (Throwable ignored) { }
        if (isFinishing()) {
            if (incog) {
                try {
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                } catch (Throwable ignored) { }
            }
            clearTabs();
        }
        super.onDestroy();
    }

    static class WindowFrame extends Drawable {
        private final Paint p = new Paint();
        private final int face;
        private final float t;
        WindowFrame(int face, float density) { this.face = face; this.t = 3f + density; }
        public void draw(Canvas c) {
            Rect r = getBounds();
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(face);
            c.drawRect(r, p);
            p.setColor(0xFFDFDFDF);
            c.drawRect(r.left, r.top, r.right, r.top + t, p);
            c.drawRect(r.left, r.top, r.left + t, r.bottom, p);
            p.setColor(0xFF808080);
            c.drawRect(r.left, r.bottom - t, r.right, r.bottom, p);
            c.drawRect(r.right - t, r.top, r.right, r.bottom, p);
        }
        public void setAlpha(int alpha) { }
        public void setColorFilter(android.graphics.ColorFilter f) { }
        public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    static class InsetBorder extends Drawable {
        private final Paint p = new Paint();
        private final float t;
        InsetBorder(float density) { this.t = 2f + density * 0.5f; }
        public void draw(Canvas c) {
            Rect r = getBounds();
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            c.drawRect(r, p);
            p.setColor(0xFF808080);
            c.drawRect(r.left, r.top, r.right, r.top + t, p);
            c.drawRect(r.left, r.top, r.left + t, r.bottom, p);
            p.setColor(0xFFFFFFFF);
            c.drawRect(r.left, r.bottom - t, r.right, r.bottom, p);
            c.drawRect(r.right - t, r.top, r.right, r.bottom, p);
        }
        public void setAlpha(int alpha) { }
        public void setColorFilter(android.graphics.ColorFilter f) { }
        public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}
