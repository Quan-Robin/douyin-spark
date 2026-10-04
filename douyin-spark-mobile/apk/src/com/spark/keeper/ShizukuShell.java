package com.spark.keeper;

import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Shizuku 通道:以 shell(uid 2000)身份执行命令。
 *
 * 背景:Shizuku 13.x 的公开 API 里 {@code Shizuku.newProcess} 是 private,真正执行命令的
 * {@code moe.shizuku.server.IShizukuService} 也没打进 api artifact —— 但它**官方发布在**
 * {@code dev.rikka.shizuku:aidl}(我们直接引用这个编译好的 stub,不必自己跑 aidl 编译器)。
 * 这样 {@code input} / {@code settings put} / {@code wm dismiss-keyguard} 就能和 root 一样跑,
 * 区别只是身份是 shell 而不是 uid 0。
 *
 * ⚠️ 代价:这是**未公开的内部接口**,Shizuku 升级若改了 AIDL 事务号就会失效。
 * 所以调用方必须把它当"尽力而为"的通道,失败要能干净回落到其它路径,并且全程写日志。
 */
public final class ShizukuShell {

    /** 请求授权的 requestCode(结果通过 addRequestPermissionResultListener 回调)。 */
    public static final int REQUEST_CODE = 4213;

    private ShizukuShell() {
    }

    /** Shizuku 管理器是否装着(用于区分"没装"和"装了没授权")。 */
    public static boolean installed(android.content.Context c) {
        try {
            return c.getPackageManager().getPackageInfo("moe.shizuku.manager", 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 服务是否在线。 */
    public static boolean available() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已授予本应用权限。 */
    public static boolean granted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 请求授权(需在 Activity 里调用,结果异步回调)。 */
    public static void requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable ignored) {
        }
    }

    /** Shizuku 服务版本号,取不到返回 -1。 */
    public static int version() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 供状态文案使用的一句话描述。 */
    public static String statusText(android.content.Context c) {
        if (!installed(c)) {
            return "未安装(需自行安装 Shizuku 并启动)";
        }
        if (!available()) {
            return "已安装但服务未运行(重启后需要重新启动 Shizuku)";
        }
        if (!granted()) {
            return "服务在线,尚未授权本应用";
        }
        return "已授权(v" + version() + "),可以执行命令";
    }

    /**
     * 以 shell 身份执行命令。
     *
     * @return 长度 2 的数组 {退出码(Integer), 输出文本(String)};通道不可用返回 null。
     */
    public static Object[] run(String cmd, long timeoutMs) {
        try {
            if (!available()) {
                return null;
            }
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (service == null) {
                return null;
            }
            final IRemoteProcess proc = service.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            if (proc == null) {
                return null;
            }
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread t1 = pipe(proc.getInputStream(), buf);
            Thread t2 = pipe(proc.getErrorStream(), buf);
            boolean finished;
            try {
                finished = proc.waitForTimeout(timeoutMs, "ms");
            } catch (Throwable t) {
                finished = false;
            }
            if (!finished) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
            join(t1);
            join(t2);
            int code = -1;
            try {
                code = proc.exitValue();
            } catch (Throwable ignored) {
            }
            String out;
            synchronized (buf) {
                out = buf.toString("UTF-8");
            }
            return new Object[]{code, out};
        } catch (Throwable t) {
            // 内部接口失效(Shizuku 升级)会走到这里:交给调用方回落
            return null;
        }
    }

    private static Thread pipe(final ParcelFileDescriptor pfd, final ByteArrayOutputStream buf) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in = null;
                try {
                    in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        synchronized (buf) {
                            buf.write(b, 0, n);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void join(Thread t) {
        try {
            t.join(600);
        } catch (InterruptedException ignored) {
        }
    }
}
