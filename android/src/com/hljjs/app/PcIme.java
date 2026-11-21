package com.hljjs.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 互联网精神与世长存 · 输入法
 *
 * 样式照 Windows 95 的物理键盘：灰底、立体边框、深色字。
 * 布局：QWERTY + 数字符号页，Shift / 退格 / 回车 / 空格 / 中文标点。
 */
public class PcIme extends InputMethodService {

    private static final int BG      = 0xFFC0C0C0;
    private static final int LIGHT   = 0xFFFFFFFF;
    private static final int MID     = 0xFFDFDFDF;
    private static final int DARK    = 0xFF808080;
    private static final int SHADOW  = 0xFF0A0A0A;

    private boolean shifted = false;
    private boolean symbols = false;
    private LinearLayout root;

    /* ---------- Win95 立体边框 ---------- */
    static class Win95Bg extends Drawable {
        private final Paint p = new Paint();
        private boolean pressed;

        void setPressed(boolean b) { pressed = b; }

        @Override public void draw(Canvas c) {
            Rect r = getBounds();
            p.setStyle(Paint.Style.FILL);
            p.setColor(BG);
            c.drawRect(r, p);

            p.setStrokeWidth(2f);
            int tl = pressed ? DARK : LIGHT;
            int br = pressed ? LIGHT : SHADOW;

            p.setColor(tl);
            c.drawLine(r.left + 1, r.top + 1, r.right - 1, r.top + 1, p);
            c.drawLine(r.left + 1, r.top + 1, r.left + 1, r.bottom - 1, p);

            p.setColor(br);
            c.drawLine(r.left + 1, r.bottom - 1, r.right - 1, r.bottom - 1, p);
            c.drawLine(r.right - 1, r.top + 1, r.right - 1, r.bottom - 1, p);

            p.setStrokeWidth(1f);
            p.setColor(pressed ? SHADOW : MID);
            c.drawLine(r.left + 3, r.top + 3, r.right - 3, r.top + 3, p);
            c.drawLine(r.left + 3, r.top + 3, r.left + 3, r.bottom - 3, p);

            p.setColor(pressed ? MID : DARK);
            c.drawLine(r.left + 3, r.bottom - 3, r.right - 3, r.bottom - 3, p);
            c.drawLine(r.right - 3, r.top + 3, r.right - 3, r.bottom - 3, p);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    /* ---------- 键盘 ---------- */
    @Override
    public View onCreateInputView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(3), dp(5), dp(3), dp(5));

        if (symbols) buildSymbols(); else buildLetters();
        return root;
    }

    private void buildLetters() {
        addRow(new String[]{"q","w","e","r","t","y","u","i","o","p"}, 1f);
        addRow(new String[]{"a","s","d","f","g","h","j","k","l"}, 1f);

        LinearLayout r3 = newRow();
        r3.addView(key("⇧", 1.4f, new Runnable() { public void run() { shifted = !shifted; refresh(); } }), lp(1.4f));
        for (String c : new String[]{"z","x","c","v","b","n","m"}) r3.addView(letterKey(c), lp(1f));
        r3.addView(key("⌫", 1.4f, new Runnable() { public void run() { del(); } }), lp(1.4f));
        root.addView(r3);

        LinearLayout r4 = newRow();
        r4.addView(key("123", 1.2f, new Runnable() { public void run() { symbols = true; refresh(); } }), lp(1.2f));
        r4.addView(key("，", 1f, new Runnable() { public void run() { commit("，"); } }), lp(1f));
        r4.addView(key("空格", 4f, new Runnable() { public void run() { commit(" "); } }), lp(4f));
        r4.addView(key("。", 1f, new Runnable() { public void run() { commit("。"); } }), lp(1f));
        r4.addView(key("↵", 1.2f, new Runnable() { public void run() { enter(); } }), lp(1.2f));
        root.addView(r4);
    }

    private void buildSymbols() {
        addRow(new String[]{"1","2","3","4","5","6","7","8","9","0"}, 1f);
        addRow(new String[]{"@","#","$","%","&","*","-","+","(",")"}, 1f);

        LinearLayout r3 = newRow();
        r3.addView(key("ABC", 1.4f, new Runnable() { public void run() { symbols = false; refresh(); } }), lp(1.4f));
        for (String c : new String[]{"!","?",":",";","'","\"","/"}) r3.addView(symKey(c), lp(1f));
        r3.addView(key("⌫", 1.4f, new Runnable() { public void run() { del(); } }), lp(1.4f));
        root.addView(r3);

        LinearLayout r4 = newRow();
        r4.addView(key("，", 1f, new Runnable() { public void run() { commit("，"); } }), lp(1f));
        r4.addView(key("。", 1f, new Runnable() { public void run() { commit("。"); } }), lp(1f));
        r4.addView(key("空格", 4f, new Runnable() { public void run() { commit(" "); } }), lp(4f));
        r4.addView(key("_", 1f, new Runnable() { public void run() { commit("_"); } }), lp(1f));
        r4.addView(key("↵", 1.2f, new Runnable() { public void run() { enter(); } }), lp(1.2f));
        root.addView(r4);
    }

    private void refresh() {
        if (root == null) return;
        root.removeAllViews();
        if (symbols) buildSymbols(); else buildLetters();
    }

    private void addRow(String[] chars, float w) {
        LinearLayout row = newRow();
        for (String c : chars) row.addView(letterKey(c), lp(w));
        root.addView(row);
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        pp.bottomMargin = dp(3);
        row.setLayoutParams(pp);
        return row;
    }

    private LinearLayout.LayoutParams lp(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        p.setMargins(dp(1), 0, dp(1), 0);
        return p;
    }

    private TextView letterKey(final String c) {
        final String out = shifted ? c.toUpperCase() : c;
        return key(shifted ? c.toUpperCase() : c, 1f, new Runnable() {
            public void run() { commit(out); }
        });
    }

    private TextView symKey(final String c) {
        return key(c, 1f, new Runnable() { public void run() { commit(c); } });
    }

    private TextView key(String label, float weight, final Runnable action) {
        final TextView v = new TextView(this);
        final Win95Bg bg = new Win95Bg();
        v.setBackground(bg);
        v.setText(label);
        v.setTextColor(Color.BLACK);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, label.length() > 1 ? 12 : 17);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setPadding(0, 0, 0, dp(1));

        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        bg.setPressed(true);
                        v.invalidate();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        bg.setPressed(false);
                        v.invalidate();
                        v.performClick();
                        action.run();
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        bg.setPressed(false);
                        v.invalidate();
                        return true;
                }
                return false;
            }
        });
        return v;
    }

    /* ---------- 输入动作 ---------- */
    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(s, 1);
    }

    private void del() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) { ic.commitText("", 1); return; }
        ic.deleteSurroundingText(1, 0);
    }

    private void enter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
