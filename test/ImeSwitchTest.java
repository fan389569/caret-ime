package com.zhufan.smartinputlite;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;

import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * 端到端自检：直接调用插件里真实的 Ime.setChinese()，验证整条链路。
 * 注意本文件在 com.zhufan.smartinputlite 包下，用的就是插件那份代码。
 *
 * 前提：窗口必须真正拿到前台焦点 —— 注入的按键只会送到前台窗口。
 * Windows 会阻止后台进程抢焦点，拿不到就报「本次无效」并退出码 2，不能当成失败。
 */
public class ImeSwitchTest {

    /** 拿不到焦点时的退出码，区别于断言失败。 */
    private static final int INVALID_RUN = 2;

    /** 只给自检用，不污染插件代码。 */
    interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);
        Pointer GetForegroundWindow();
        boolean SetForegroundWindow(Pointer hwnd);
    }

    public static void main(String[] args) throws Exception {
        final JFrame[] fh = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea();
            JFrame f = new JFrame("Caret IME 端到端自检 - 需要窗口保持前台，约 10 秒");
            f.add(area);
            f.setSize(460, 130);
            f.setLocationRelativeTo(null);
            f.setAlwaysOnTop(true);
            f.setVisible(true);
            // 最小化再还原，是 Windows 下抢前台的常用手法
            f.setState(JFrame.ICONIFIED);
            f.setState(JFrame.NORMAL);
            f.toFront();
            area.requestFocusInWindow();
            fh[0] = f;
        });
        JFrame f = fh[0];

        Pointer hwnd = Native.getWindowPointer(f);
        if (!awaitFocus(hwnd, 10000)) {
            System.out.println("【本次无效】窗口 10 秒内没能拿到前台焦点，注入的按键送不到，结果不可信。");
            System.out.println("请让这个窗口保持在前台（别点别处）后重跑。");
            SwingUtilities.invokeLater(f::dispose);
            Thread.sleep(200);
            System.exit(INVALID_RUN);
        }

        System.out.println("起始      : " + read(hwnd));

        System.out.println(">>> 目标=英文");
        Ime.setChinese(false);
        Thread.sleep(1600);
        System.out.println("结果      : " + read(hwnd));

        System.out.println(">>> 目标=中文");
        Ime.setChinese(true);
        Thread.sleep(1600);
        System.out.println("结果      : " + read(hwnd));

        System.out.println(">>> 目标=英文");
        Ime.setChinese(false);
        Thread.sleep(1600);
        System.out.println("结果      : " + read(hwnd));

        System.out.println(">>> 再设一次英文（应该什么都不做）");
        Ime.setChinese(false);
        Thread.sleep(800);
        System.out.println("结果      : " + read(hwnd));

        SwingUtilities.invokeLater(f::dispose);
        Thread.sleep(200);
        System.exit(0);
    }

    /** 轮询等窗口成为前台窗口；顺带反复请求前台，Windows 有时会拒绝。 */
    private static boolean awaitFocus(Pointer hwnd, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (User32.INSTANCE.GetForegroundWindow() == hwnd) return true;
            User32.INSTANCE.SetForegroundWindow(hwnd);
            Thread.sleep(200);
        }
        return User32.INSTANCE.GetForegroundWindow() == hwnd;
    }

    static String read(Pointer hwnd) {
        Pointer himc = Ime.Imm32.INSTANCE.ImmGetContext(hwnd);
        if (himc == null) return "拿不到上下文";
        try {
            return "open=" + Ime.Imm32.INSTANCE.ImmGetOpenStatus(himc);
        } finally {
            Ime.Imm32.INSTANCE.ImmReleaseContext(hwnd, himc);
        }
    }
}
