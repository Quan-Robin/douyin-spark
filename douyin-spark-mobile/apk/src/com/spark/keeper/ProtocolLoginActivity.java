package com.spark.keeper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 一次性网页登录:登录态存入系统 CookieManager,协议模式长期复用。 */
public class ProtocolLoginActivity extends Activity {

    private WebView webView;
    /** Activity 已销毁:用于让 Cookie 轮询线程及时退出(跨线程可见,必须 volatile)。 */
    private volatile boolean destroyed;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView tip = new TextView(this);
        tip.setText("v0.2.9 · 用抖音 App 扫码 / 验证码登录网页版(登录态长期保存,完成后按返回键)");
        tip.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(tip);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 桌面 UA:抖音对手机 UA 返回移动推广页(无登录入口),桌面 UA 才有完整登录
        s.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36");
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        webView.setWebViewClient(new WebViewClient() {
            private int retry = 0;

            @Override
            public void onPageFinished(WebView v, String url) {
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                // 完整记录错误 URL 与错误码(定位 CACHE_MISS 实际发生位置)
                Prefs.appendLog(ProtocolLoginActivity.this,
                        "WebView 错误: code=" + code + " desc=" + desc + " url=" + failingUrl);
                if (desc != null && desc.contains("CACHE_MISS") && retry < 2) {
                    retry++;
                    v.reload();
                }
            }

        });
        webView.setWebChromeClient(new WebChromeClient());
        // 记录实际内核版本(诊断协议模式兼容性)
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                android.content.pm.PackageInfo pkg = android.webkit.WebView.getCurrentWebViewPackage();
                if (pkg != null) {
                    Prefs.appendLog(this, "登录页 WebView 内核: " + pkg.packageName + " v" + pkg.versionName);
                }
            } catch (Throwable ignored) {
            }
        }
        // 首页为纯 GET 加载,不触发 chat 页的 ERR_CACHE_MISS 问题;登录入口在页面右上角
        webView.loadUrl("https://www.douyin.com/");

        // 后台轮询 Cookie:出现 sessionid 即登录成功(网页登录态长期有效)
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 600; i++) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }
                    // 以前判断的是 webView == null,而该字段从不置空(死判断):
                    // Activity 销毁后这个线程还会继续轮询 30 分钟并往已销毁的界面弹 Toast
                    if (destroyed || isFinishing()) {
                        return;
                    }
                    final String cookie = CookieManager.getInstance().getCookie("https://www.douyin.com");
                    if (cookie != null && cookie.contains("sessionid=")) {
                        CookieManager.getInstance().flush();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(ProtocolLoginActivity.this,
                                        "✅ 登录成功!Cookie 已长期保存,按返回键退出", Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                }
            }
        }, "login-watch").start();
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true; // 通知 Cookie 轮询线程退出
        CookieManager.getInstance().flush();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private static void deleteTree(java.io.File d) {
        if (d == null || !d.exists()) return;
        java.io.File[] fs = d.listFiles();
        if (fs != null) for (java.io.File f : fs) {
            if (f.isDirectory()) deleteTree(f); else f.delete();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
