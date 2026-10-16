package com.zhufan.smartinputlite;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretVisualAttributes;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import org.jetbrains.annotations.NotNull;

import javax.swing.Timer;
import java.awt.Color;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 光标在注释、或在"该写中文"的字符串字面量里 -> 中文输入法（光标染成注释色）；
 * 在代码里 -> 英文输入法（光标还原）。
 */
public class CaretImeSwitcher implements EditorFactoryListener {

    /** 只对这几类文件生效（代码=ASCII、注释=中文 的场合）。其他文件一概不碰输入法。 */
    private static final Set<String> LANGS = new HashSet<>(Arrays.asList(
            "java", "kt", "kts", "groovy", "yml", "yaml", "properties", "sql"));

    private static final Logger LOG = Logger.getInstance(CaretImeSwitcher.class);
    private static int diagBudget = 25;
    private static boolean recheckScheduled;
    private static boolean colorBroken;

    /** 每个光标原本的样式，切回英文时还原。 */
    private static final Key<CaretVisualAttributes> ORIGINAL = Key.create("SmartInputLite.originalCaret");
    private static final Key<Boolean> SAVED = Key.create("SmartInputLite.caretSaved");

    /** 光标事件已登记、等下一个 EDT 轮次判定（每个编辑器一个，用来合并连发的事件）。 */
    private static final Key<Boolean> PENDING = Key.create("SmartInputLite.applyPending");

    /** 用户在这个语境（这条注释 / 这个字符串）里自己手动切过输入法，插件暂时不抢。 */
    private static final Key<String> USER_OVERRIDE = Key.create("SmartInputLite.userOverride");

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        // 平台守卫：非 Windows 上不加载 JNA 的 user32/imm32，什么也不做。
        // （Ime 里的 Native.load 在静态初始化里，一旦触达非 Windows 会抛
        //   ExceptionInInitializerError，现有的 catch 接不住，所以必须在这里就挡住。）
        if (!SystemInfo.isWindows) return;
        Editor editor = event.getEditor();
        // listener 挂到插件 service 的 disposable 上，卸载/禁用时随它一起释放；
        // getService 同时保证 service 被实例化，其 dispose() 才会在卸载时被平台调用。
        Disposable plugin = ApplicationManager.getApplication().getService(CaretImeService.class);
        editor.getCaretModel().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent e) {
                scheduleApply(e.getEditor());
            }
        }, plugin);
        apply(editor, 0);
    }

    /**
     * 光标事件只登记，判定推迟到下一个 EDT 轮次 —— 这样连发的光标事件会自动合并成一次判定。
     *
     * 更要紧的是躲开"打字打到一半"：输入法每次更新候选串，IntelliJ 都是先 dispose 掉旧候选串的
     * 标记、写入新候选串、最后才挂上新标记（反编译 EditorImpl$MyInputMethodHandler.replaceInputMethodText
     * 确认）。在光标事件里当场查 composedTextRange 只会拿到 null，判定就会在用户打字中途注入 Shift、
     * 把候选串打断。等一轮再看，标记已经就位，才认得出"正在打字"。
     */
    private static void scheduleApply(Editor editor) {
        if (editor.isDisposed() || Boolean.TRUE.equals(editor.getUserData(PENDING))) return;
        editor.putUserData(PENDING, Boolean.TRUE);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed()) return;
            editor.putUserData(PENDING, null);
            apply(editor, 0);
        });
    }

    private static void apply(Editor editor, int depth) {
        if (editor.isDisposed()) return;
        // 输入法正在打字（候选串还没上屏）：这时注入切换键会把候选串打断、顺手切回英文，
        // 用户看到的就是"我想打中文，插件硬把我掰成英文"。等这轮打完，下一个光标事件自然会重判。
        if (isComposing(editor)) return;
        Project project = editor.getProject();
        if (project == null) return;
        // IDEA 2025.2 起访问编辑器/PSI 模型必须在 read action 里。
        // 光标事件本身带了隐式 read access，但定时器回调（recheckLater）没有 —— 不包会抛异常。
        ReadAction.run(() -> applyInReadAction(editor, project, depth));
    }

    /**
     * 输入法有未上屏的候选串时返回 true。
     *
     * getComposedTextRange 挂在 EditorImpl 上，是内部 API：拿不到就当作没在打字
     * （宁可少挡一次，也不能因为这个报错）。调用方必须已经推迟到下一个 EDT 轮次，
     * 否则这里看到的永远是 null。
     */
    private static boolean isComposing(Editor editor) {
        try {
            return editor instanceof EditorImpl && ((EditorImpl) editor).getComposedTextRange() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyInReadAction(Editor editor, Project project, int depth) {
        Document document = editor.getDocument();
        VirtualFile vf = FileDocumentManager.getInstance().getFile(document);
        if (vf == null || !LANGS.contains(vf.getExtension())) return;

        PsiDocumentManager pdm = PsiDocumentManager.getInstance(project);
        // 敲字瞬间 PSI 树还没跟上文档，这时按偏移找元素会错位（注释末尾会被误判成代码）。
        // 不硬提交（提交要写意图，容易踩线程模型），等 IDEA 自己提交后重判一次。
        if (!pdm.isCommitted(document)) {
            recheckLater(editor, depth);
            return;
        }

        PsiFile file = pdm.getPsiFile(document);
        if (file == null) return;

        int offset = editor.getCaretModel().getOffset();
        Where where = whereAt(file, offset, document);
        boolean target = where.chinese;

        if (diagBudget > 0) {
            diagBudget--;
            LOG.info("Caret IME: " + vf.getExtension() + " offset=" + offset
                    + " 语境=" + where.kind + " -> 目标=" + (target ? "中文" : "英文")
                    + " | 本行=\"" + lineText(document, offset) + "\"");
        }

        // 用户在这处自己切过输入法，让位：不碰输入法，也不动光标色，等他离开这处再说
        if (where.key != null && where.key.equals(editor.getUserData(USER_OVERRIDE))) return;
        editor.putUserData(USER_OVERRIDE, null);          // 换地方了，让位作废

        if (where.key != null && Ime.userTookOver(target)) {
            editor.putUserData(USER_OVERRIDE, where.key);
            LOG.info("Caret IME: 你手动切过输入法，这处不抢（" + where.key + "）");
            return;
        }

        Ime.setChinese(target);
        applyCaretColor(editor, target);
    }

    /** PSI 还没跟上文档时，等一会儿再判一次。 */
    private static void recheckLater(Editor editor, int depth) {
        if (recheckScheduled || depth >= 5) return;
        recheckScheduled = true;
        Timer t = new Timer(80, e -> {
            recheckScheduled = false;
            ((Timer) e.getSource()).stop();
            apply(editor, depth + 1);   // apply 自己会包 read action
        });
        t.setRepeats(false);
        t.start();
    }

    /**
     * 光标处该用什么输入法。
     *
     * 注释里用中文。字符串字面量按内容判：已经写了中文的、或者刚打出一对空引号（光标正夹在中间，
     * 摆明是要往里面写东西）的，也用中文；纯 ASCII 的串（"userId"、SQL 片段）按代码处理，
     * 免得写英文串的时候被反复掰回中文。
     */
    private static Where whereAt(PsiFile file, int offset, Document document) {
        Where w = whereAtOffset(file, offset);
        // 光标停在注释末尾（后面就是换行）时，offset 落在换行符上，回头看一眼前一个字符。
        // 只对注释做这个回退：字符串后面接换行多半是要接着写代码，不该算在串里。
        if (w.kind == Ctx.CODE && offset > 0 && atLineEnd(document, offset)) {
            Where prev = whereAtOffset(file, offset - 1);
            if (prev.kind == Ctx.COMMENT) return prev;
        }
        return w;
    }

    private static Where whereAtOffset(PsiFile file, int offset) {
        if (offset < 0 || offset > file.getTextLength()) return Where.CODE;
        PsiElement el = file.findElementAt(offset);
        while (el != null && !(el instanceof PsiFile)) {
            if (el instanceof PsiComment) {
                return new Where(Ctx.COMMENT, "C" + el.getTextRange().getStartOffset(), true);
            }
            // 引号字面量一律实现 PsiLanguageInjectionHost：Java 的 PsiLiteralExpression、
            // Kotlin 的 KtStringTemplateExpression 都是。用这个接口判，编译期不用引 Java/Kotlin 插件的 jar。
            if (el instanceof PsiLanguageInjectionHost && isQuoted(el.getText())) {
                return inString((PsiLanguageInjectionHost) el, offset);
            }
            el = el.getParent();
        }
        return Where.CODE;
    }

    /** 字符串字面量里的判定：串里有中文，或者光标正夹在一对空引号中间（刚打出来的 ""），都算写中文。 */
    private static Where inString(PsiLanguageInjectionHost literal, int offset) {
        TextRange r = literal.getTextRange();
        String body = unquote(literal.getText());
        boolean chinese = hasCjk(body)
                || (body.trim().isEmpty() && offset > r.getStartOffset() && offset < r.getEndOffset());
        return new Where(Ctx.STRING, "S" + r.getStartOffset(), chinese);
    }

    /** 引号包起来的才算字符串字面量 —— 借此排掉数字、true/false 这些同样实现该接口的字面量。 */
    private static boolean isQuoted(String text) {
        return text.startsWith("\"") || text.startsWith("'");
    }

    /** 剥掉外层引号："""文本块""" / "串" / '串'。 */
    private static String unquote(String text) {
        if (text.startsWith("\"\"\"")) {
            return text.length() >= 6 ? text.substring(3, text.length() - 3) : "";
        }
        if (isQuoted(text)) {
            return text.length() >= 2 ? text.substring(1, text.length() - 1) : "";
        }
        return text;
    }

    /** 有没有中文字符（含中文标点和全角符号）。 */
    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3000 && c <= 0x303F)      // 中文标点
                    || (c >= 0x3400 && c <= 0x4DBF)   // 扩展 A
                    || (c >= 0x4E00 && c <= 0x9FFF)   // 基本汉字
                    || (c >= 0xF900 && c <= 0xFAFF)   // 兼容汉字
                    || (c >= 0xFF00 && c <= 0xFFEF)) { // 全角符号
                return true;
            }
        }
        return false;
    }

    private static boolean atLineEnd(Document document, int offset) {
        return offset >= document.getTextLength() || document.getCharsSequence().charAt(offset) == '\n';
    }

    /**
     * 中文时把光标染成注释文本色，英文时还原。
     * 颜色是从当前配色方案里读的，所以和注释文字天然一致。
     */
    private static void applyCaretColor(Editor editor, boolean chinese) {
        if (colorBroken) return;
        try {
            Caret caret = editor.getCaretModel().getPrimaryCaret();
            if (!Boolean.TRUE.equals(caret.getUserData(SAVED))) {
                caret.putUserData(ORIGINAL, caret.getVisualAttributes());  // 可能是 null = 用默认
                caret.putUserData(SAVED, Boolean.TRUE);
            }
            CaretVisualAttributes orig = caret.getUserData(ORIGINAL);

            CaretVisualAttributes want;
            if (chinese) {
                Color c = commentColor(editor);
                if (c == null) return;
                CaretVisualAttributes.Weight w = orig != null
                        ? orig.getWeight() : CaretVisualAttributes.Weight.NORMAL;
                want = new CaretVisualAttributes(c, w);
            } else {
                want = orig != null ? orig : CaretVisualAttributes.DEFAULT;
            }
            if (!same(want, caret.getVisualAttributes())) caret.setVisualAttributes(want);
        } catch (Throwable t) {
            colorBroken = true;   // 光标颜色只是锦上添花，坏了不影响切换
            LOG.warn("Caret IME: 设置光标颜色失败，已停用该功能", t);
        }
    }

    /** 当前配色方案里注释文字的前景色。 */
    private static Color commentColor(Editor editor) {
        EditorColorsScheme scheme = editor.getColorsScheme();
        TextAttributesKey[] keys = {
                DefaultLanguageHighlighterColors.LINE_COMMENT,
                DefaultLanguageHighlighterColors.BLOCK_COMMENT,
                DefaultLanguageHighlighterColors.DOC_COMMENT};
        for (TextAttributesKey key : keys) {
            TextAttributes a = scheme.getAttributes(key);
            if (a != null && a.getForegroundColor() != null) return a.getForegroundColor();
        }
        return null;
    }

    private static boolean same(CaretVisualAttributes a, CaretVisualAttributes b) {
        if (a == null || b == null) return a == b;
        return Objects.equals(a.getColor(), b.getColor()) && a.getWeight() == b.getWeight();
    }

    /** 诊断用：光标所在行的内容。 */
    private static String lineText(Document document, int offset) {
        int at = Math.min(offset, document.getTextLength());
        int line = document.getLineNumber(at);
        String s = document.getCharsSequence()
                .subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line)).toString();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    /**
     * 插件被禁用/卸载时调用（由 {@link CaretImeService} 的 dispose 触发）：
     * 把每个编辑器的光标颜色还原成插件接管前的样式，并清空插件级静态状态。
     * 挂掉的 caret listener 会随 service 的 disposable 一并释放，这里不用管。
     */
    static void disposeAll() {
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            try {
                restoreCaretColor(editor);
            } catch (Throwable ignored) {
                // 清理阶段尽力而为，单个编辑器出错不影响其余
            }
        }
        recheckScheduled = false;
        colorBroken = false;
        diagBudget = 0;
    }

    /** 把光标颜色还原成插件接管前的样式（没接管过就什么都不做）。 */
    private static void restoreCaretColor(Editor editor) {
        Caret caret = editor.getCaretModel().getPrimaryCaret();
        if (!Boolean.TRUE.equals(caret.getUserData(SAVED))) return;
        CaretVisualAttributes orig = caret.getUserData(ORIGINAL);
        caret.putUserData(SAVED, null);
        caret.putUserData(ORIGINAL, null);
        caret.setVisualAttributes(orig != null ? orig : CaretVisualAttributes.DEFAULT);
    }

    /** 光标所处的语境。 */
    private enum Ctx { CODE, COMMENT, STRING }

    private static final class Where {
        static final Where CODE = new Where(Ctx.CODE, null, false);

        /** 语境种类，只用于日志。 */
        final Ctx kind;
        /** "让位"用的标识：同一条注释 / 同一个字符串算同一处；代码里为 null（代码不让位）。 */
        final String key;
        /** 这个语境该不该用中文。 */
        final boolean chinese;

        Where(Ctx kind, String key, boolean chinese) {
            this.kind = kind;
            this.key = key;
            this.chinese = chinese;
        }
    }
}
