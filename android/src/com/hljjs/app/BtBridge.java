package com.hljjs.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

/**
 * 蓝牙搭桥：只把 WebRTC 的暗号通过蓝牙（RFCOMM/SPP）送过去，
 * 真正的数据仍然走 WebRTC 直连。
 */
public class BtBridge {

    private static final UUID SPP = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");

    private final Activity act;
    private BluetoothAdapter adapter;
    private BluetoothServerSocket server;
    private BluetoothSocket socket;
    private volatile boolean reading = false;

    public BtBridge(Activity a) {
        act = a;
        try { adapter = BluetoothAdapter.getDefaultAdapter(); } catch (Throwable ignored) { }
    }

    public boolean available() {
        try {
            if (adapter == null) adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter != null;
        } catch (Throwable e) { return false; }
    }

    public boolean enabled() {
        try { return adapter != null && adapter.isEnabled(); } catch (Throwable e) { return false; }
    }

    public boolean permitted() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                return act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        } catch (Throwable e) { return false; }
    }

    public void requestPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !permitted()) {
                act.requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 4711);
            }
        } catch (Throwable ignored) { }
    }

    /** 已配对设备列表 */
    public String list() {
        try {
            if (!permitted()) { requestPermission(); return "[]"; }
            JSONArray arr = new JSONArray();
            Set<BluetoothDevice> set = adapter.getBondedDevices();
            if (set != null) {
                for (BluetoothDevice d : set) {
                    JSONObject o = new JSONObject();
                    o.put("name", d.getName() == null ? "" : d.getName());
                    o.put("address", d.getAddress());
                    arr.put(o);
                }
            }
            return arr.toString();
        } catch (Throwable e) {
            return "[]";
        }
    }

    /** 本机当接收方：监听，等对方连进来 */
    public String listen() {
        try {
            if (!permitted()) { requestPermission(); return "need-permission"; }
            if (!enabled()) return "off";
            stop();
            server = adapter.listenUsingRfcommWithServiceRecord("hljjs", SPP);
            new Thread(new Runnable() {
                public void run() {
                    try {
                        BluetoothSocket s = server.accept();
                        try { server.close(); } catch (Throwable ignored) { }
                        server = null;
                        attach(s);
                    } catch (Throwable ignored) { }
                }
            }).start();
            return "listening";
        } catch (Throwable e) {
            return "err:" + e.getMessage();
        }
    }

    /** 本机当发起方：连到已配对的设备 */
    public String connect(String address) {
        try {
            if (!permitted()) { requestPermission(); return "need-permission"; }
            if (!enabled()) return "off";
            BluetoothDevice d = adapter.getRemoteDevice(address);
            stop();
            try { adapter.cancelDiscovery(); } catch (Throwable ignored) { }
            BluetoothSocket s = d.createRfcommSocketToServiceRecord(SPP);
            s.connect();
            attach(s);
            return "ok";
        } catch (Throwable e) {
            return "err:" + e.getMessage();
        }
    }

    private void attach(final BluetoothSocket s) {
        socket = s;
        reading = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    String line;
                    while (reading && (line = r.readLine()) != null) {
                        final String L = line.trim();
                        if (L.length() == 0) continue;
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                try {
                                    MainActivity.appWeb.evaluateJavascript(
                                            "window.__btLine && window.__btLine(" + JSONObject.quote(L) + ")", null);
                                } catch (Throwable ignored) { }
                            }
                        });
                    }
                } catch (Throwable ignored) { }
            }
        }).start();
    }

    public boolean send(String text) {
        try {
            if (socket == null) return false;
            OutputStream os = socket.getOutputStream();
            os.write((text + "\n").getBytes("UTF-8"));
            os.flush();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public void stop() {
        reading = false;
        try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        socket = null;
        server = null;
    }

    public String state() {
        try {
            if (socket != null && socket.isConnected()) return "connected";
            if (server != null) return "listening";
            return "idle";
        } catch (Throwable e) {
            return "idle";
        }
    }
}
