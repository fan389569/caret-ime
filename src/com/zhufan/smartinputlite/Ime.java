package com.zhufan.smartinputlite;

import com.intellij.openapi.diagnostic.Logger;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import javax.swing.Timer;
import java.awt.KeyboardFocusManager;
import java.awt.Window;

/**
 * 切换当前输入法的中/英模式。
 *
 * 微软拼音（TSF 输入法）实测结论，2026-10-01 用探针验证：
 *   1. ImmGetConversionStatus 的 flags 恒为 0x1，是假值，不能用它判断中英；
 *      只有 ImmGetOpenStatus 反映真实状态 —— true=中文，false=英文。
 *   2. ImmSetConversionStatus / ImmSetOpenStatus 一律返回 FALSE，写不进去。
 *   3. 模拟 Shift 按键能切，且 ImmGetOpenStatus 会跟着变。
 *   4. 切换后读值有几十毫秒滞后 —— 所以刚切完的一小段时间内不读，沿用自己记录的状态，
 *      否则连续的光标事件会读到旧值、再切一次，来回弹。
 */
final class Ime {

    private static final Logger LOG = Logger.getInstance(Ime.class);

    interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.UNICODE_OPTIONS);
        Pointer GetForegroundWindow();
        Pointer GetFocus();
        int GetClassName(Pointer hwnd, char[] buf, int max);
        int GetWindowText(Pointer hwnd, char[] buf, int max);
        void keybd_event(byte vk, byte scan, int flags, Pointer extra);
    }

    interface Imm32 extends StdCallLibrary {
        Imm32 INSTANCE = Native.load("imm32", Imm32.class);
        Pointer ImmGetContext(Pointer hwnd);
        boolean ImmReleaseContext(Pointer hwnd, Pointer himc);
        boolean ImmGetOpenStatus(Pointer himc);
    }

    private static final byte VK_SHIFT = 0x10;
    private static final byte VK_CONTROL = 0x11;
    private static final byte VK_SPACE = 0x20;
    private static final int KEYEVENTF_KEYUP = 0x0002;
    /** 垫键：注入切换键前先发一个完整的 F24 按下/抬起，打断 IDEA 的双击修饰键检测。 */
    private static final byte VK_F24 = (byte) 0x87;

    /** 切换机制，按顺序降级。Shift 是微软拼音默认的中英切换键。 */
    private static final String[] MECHS = {"Shift键", "Ctrl+Space"};
    /** 所有机制都失败后，隔这么久再从头试一遍 —— 否则一次瞬时失败会让插件整个会话失效。 */
    private static final long MECH_COOLDOWN_MS = 3000;
    private static int mech;
    private static long mechRetryAfter;
    private static Timer verifyTimer;
    private static boolean broken;

    /** 最近一次请求的目标。回读验证按它判，不能被先到/后到的反向请求带偏。 */
    private static Boolean desired;

    /** 刚切完后的一段时间内读值不可信，用这个记录顶着。 */
    private static final long SETTLE_MS = 600;
    private static Boolean assumedState;
    private static long assumedUntil;

    /** 我们最后一次认可的中英状态（切成功的，或本来就已经达标的），以及最后一次真正注入按键的时刻。 */
    private static Boolean enforced;
    private static long enforcedAt;

    /**
     * 注入按键后要等这么久，才允许把"状态和记录对不上"解读成用户手动切换。
     * 必须留够回读验证的时间（350ms / 700ms 各一轮），否则一次切换失败会被误判成
     * "用户自己切的"，插件就再也不切了。
     */
    private static final long USER_GRACE_MS = 1200;

    /** 最近一次读状态走的是哪个 HWND，只给日志用。 */
    private static String lastHow;

    private Ime() {}

    /**
     * 插件被禁用/卸载时调用：停掉回读验证定时器，清空切换状态。
     * 由 {@link CaretImeService} 的 dispose 触发。
     */
    static void shutdown() {
        if (verifyTimer != null) {
            verifyTimer.stop();
            verifyTimer = null;
        }
        desired = null;
        assumedState = null;
        assumedUntil = 0;
        enforced = null;
        enforcedAt = 0;
        mech = 0;
        mechRetryAfter = 0;
    }

    /**
     * 用户是不是自己手动切了输入法、而且切到了和 target 相反的一边 —— 是的话插件该让位。
     *
     * 判据：当前状态既不是我们认可的（enforced），也不是我们刚设完还没生效的。
     * 中英只有两种状态，而我们注入一次按键只可能有两种结果 —— 变成目标、或者压根没变，
     * 所以"状态变了又不是我们干的"只可能是用户干的。（切换失败的情况由回读验证兜底：
     * 机制全挂时会把 enforced 清空，回到"不知道"而不是"认定是用户"。）
     */
    static boolean userTookOver(boolean target) {
        if (broken || enforced == null) return false;
        if (System.currentTimeMillis() - enforcedAt < USER_GRACE_MS) return false;
        Boolean cur = currentChinese();
        return cur != null && !cur.equals(enforced) && cur != target;
    }

    /** 直接问系统当前是不是中文模式；拿不到输入法上下文返回 null。 */
    private static Boolean currentChinese() {
        try {
            Ctx ctx = findContext();
            if (ctx == null) {
                LOG.warn("Caret IME: 拿不到输入法上下文 | " + dumpHwnds());
                return null;
            }
            lastHow = ctx.how;
            try {
                return Imm32.INSTANCE.ImmGetOpenStatus(ctx.himc);
            } finally {
                Imm32.INSTANCE.ImmReleaseContext(ctx.hwnd, ctx.himc);
            }
        } catch (Throwable t) {
            LOG.warn("Caret IME: 读输入法状态异常", t);
            return null;
        }
    }

    /** chinese=true 切中文模式；false 切英文模式（英文标点）。 */
    static void setChinese(boolean chinese) {
        if (broken) return;
        try {
            long now = System.currentTimeMillis();
            boolean cur;
            String how;
            if (assumedState != null && now < assumedUntil) {
                cur = assumedState;               // 输入法还没跟上，以自己记录为准
                how = "沿用上次记录";
            } else {
                Boolean read = currentChinese();
                if (read == null) return;
                cur = read;
                assumedState = cur;
                how = lastHow;
            }

            desired = chinese;                     // 回读验证以此为准
            if (cur == chinese) {
                enforced = chinese;                // 这个状态是我们认可的
                return;                            // 已达标，什么都不做
            }

            if (mech >= MECHS.length) {            // 上次全挂了：等冷却结束再从头试，不能永久放弃
                if (now < mechRetryAfter) return;
                mech = 0;
            }

            tapKey(mech == 0 ? new byte[]{VK_SHIFT} : new byte[]{VK_CONTROL, VK_SPACE});
            assumedState = chinese;
            assumedUntil = now + SETTLE_MS;
            enforced = chinese;
            enforcedAt = now;
            LOG.info("Caret IME: [" + MECHS[mech] + "] "
                    + (cur ? "中" : "英") + " -> " + (chinese ? "中" : "英") + " | " + how);
            scheduleVerify(0);
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            broken = true;
            LOG.warn("Caret IME: JNA/IMM32 不可用，插件停用", e);
        } catch (Throwable t) {
            LOG.warn("Caret IME: 切换异常", t);
        }
    }

    /**
     * 切换后回读验证：350ms 一次，共两轮，确认没生效才降级到下一个机制。
     *
     * 2026-10-02 修的两个坑：
     *   1. 验证的是「最新目标」desired，不是发起这次切换时的目标。光标在注释末尾来回跳会
     *      连发两个反向请求（两次 Shift 正好抵消），旧代码拿过期目标回读必然判失败，于是误
     *      降级、最后「所有机制均无效」。
     *   2. 每次切换都重置计时器，只有最后一次的验证算数。
     */
    private static void scheduleVerify(int round) {
        if (verifyTimer != null) verifyTimer.stop();
        Timer t = new Timer(350, e -> {
            ((Timer) e.getSource()).stop();
            verifyTimer = null;
            if (desired == null) return;
            Ctx ctx = findContext();
            if (ctx == null) return;
            boolean ok;
            try {
                ok = Imm32.INSTANCE.ImmGetOpenStatus(ctx.himc) == desired;
            } finally {
                Imm32.INSTANCE.ImmReleaseContext(ctx.hwnd, ctx.himc);
            }
            if (ok) {
                // 生效了：回到首选机制，免得一次瞬时失败把 Shift 永久降级掉
                if (mech != 0) {
                    mech = 0;
                    LOG.info("Caret IME: 切换已生效，机制回到 [" + MECHS[0] + "]");
                }
                return;
            }
            if (round < 1) {
                scheduleVerify(round + 1);
                return;
            }
            LOG.warn("Caret IME: [" + MECHS[mech] + "] 没生效");
            assumedState = null;                   // 自己记的也不可信了，下次重新读
            assumedUntil = 0;
            enforced = null;                       // 状态对不上是我们没切成功，别赖到用户头上
            mech++;
            if (mech < MECHS.length) {
                LOG.info("Caret IME: 降级为 [" + MECHS[mech] + "]");
                setChinese(desired);
            } else {
                mechRetryAfter = System.currentTimeMillis() + MECH_COOLDOWN_MS;
                LOG.warn("Caret IME: 所有切换机制均无效，" + MECH_COOLDOWN_MS / 1000 + " 秒后自动重试");
            }
        });
        t.setRepeats(false);
        verifyTimer = t;
        t.start();
    }

    /**
     * 模拟按下一组键（依次按下，再逆序松开）。
     *
     * 注入前先垫一个完整的 F24 按下/抬起，用来打断 IDEA 的"双击修饰键"检测。
     * 依据（IDEA 2025.2.2 反编译 ModifierKeyDoubleClickHandler$MyDispatcher.dispatch）：
     *   1. 收到非修饰键 -> ourOtherKeyWasPressed.set(true); resetState(); 双击计数清零；
     *   2. 随后 100ms 内按下的修饰键 -> resetState(); return false; 整个被忽略。
     * 两条路径互为备份：即使调度抖动让 F24 和 Shift 间隔超过 100ms，第 1 条仍生效。
     * 不垫的话，拖选时快速穿越注释边界连发的 Shift 会被判成双击 Shift，弹出 Search Everywhere。
     * 输入法切换在 TSF 层已完成，与 IDEA 是否看到这个 Shift 无关，所以垫键不影响切换本身。
     */
    private static void tapKey(byte[] vks) {
        User32.INSTANCE.keybd_event(VK_F24, (byte) 0, 0, null);
        User32.INSTANCE.keybd_event(VK_F24, (byte) 0, KEYEVENTF_KEYUP, null);
        for (byte vk : vks) User32.INSTANCE.keybd_event(vk, (byte) 0, 0, null);
        for (int i = vks.length - 1; i >= 0; i--) {
            User32.INSTANCE.keybd_event(vks[i], (byte) 0, KEYEVENTF_KEYUP, null);
        }
    }

    /** 依次尝试几种 HWND 拿法，返回第一个能拿到 IMC 的。 */
    private static Ctx findContext() {
        Pointer focus = User32.INSTANCE.GetFocus();
        Pointer awt = awtWindowHandle();
        Pointer fg = User32.INSTANCE.GetForegroundWindow();

        Pointer[] cands = {focus, awt, fg, null};
        String[] names = {"GetFocus", "AWT活动窗口", "前台窗口", "本线程(NULL)"};
        for (int i = 0; i < cands.length; i++) {
            Pointer himc = Imm32.INSTANCE.ImmGetContext(cands[i]);
            if (himc != null) return new Ctx(cands[i], himc, names[i] + " " + hex(cands[i]));
        }
        return null;
    }

    /** IDEA 主窗口(AWT Window)的真实 HWND。 */
    private static Pointer awtWindowHandle() {
        try {
            Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            return w == null ? null : Native.getWindowPointer(w);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String dumpHwnds() {
        Pointer fg = User32.INSTANCE.GetForegroundWindow();
        return "GetFocus=" + hex(User32.INSTANCE.GetFocus()) + " AWT=" + hex(awtWindowHandle())
                + " 前台=" + hex(fg) + "[" + className(fg) + " \"" + title(fg) + "\"]";
    }

    private static String className(Pointer hwnd) {
        if (hwnd == null) return "-";
        char[] buf = new char[256];
        int n = User32.INSTANCE.GetClassName(hwnd, buf, buf.length);
        return n > 0 ? new String(buf, 0, n) : "-";
    }

    private static String title(Pointer hwnd) {
        if (hwnd == null) return "-";
        char[] buf = new char[256];
        int n = User32.INSTANCE.GetWindowText(hwnd, buf, buf.length);
        return n > 0 ? new String(buf, 0, n) : "-";
    }

    private static String hex(Pointer p) {
        return p == null ? "null" : "0x" + Long.toHexString(Pointer.nativeValue(p));
    }

    private static final class Ctx {
        final Pointer hwnd;
        final Pointer himc;
        final String how;

        Ctx(Pointer hwnd, Pointer himc, String how) {
            this.hwnd = hwnd;
            this.himc = himc;
            this.how = how;
        }
    }
}
