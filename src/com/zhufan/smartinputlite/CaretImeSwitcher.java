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
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.Timer;
import java.awt.Color;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 光标在注释里 -> 中文输入法（光标染成注释色）；在代码里 -> 英文输入法（光标还原）。
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
                apply(e.getEditor(), 0);
            }
        }, plugin);
        apply(editor, 0);
    }

    private static void apply(Editor editor, int depth) {
        if (editor.isDisposed()) return;
        Project project = editor.getProject();
        if (project == null) return;
        // IDEA 2025.2 起访问编辑器/PSI 模型必须在 read action 里。
        // 光标事件本身带了隐式 read access，但定时器回调（recheckLater）没有 —— 不包会抛异常。
        ReadAction.run(() -> applyInReadAction(editor, project, depth));
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
        boolean inComment = isComment(file, offset);
        // 光标停在注释末尾（后面就是换行）时，offset 落在换行符上，回头看一眼前一个字符
        if (!inComment && offset > 0 && atLineEnd(document, offset)) {
            inComment = isComment(file, offset - 1);
        }

        if (diagBudget > 0) {
            diagBudget--;
            LOG.info("Caret IME: " + vf.getExtension() + " offset=" + offset
                    + " 注释=" + inComment + " -> 目标=" + (inComment ? "中文" : "英文")
                    + " | 本行=\"" + lineText(document, offset) + "\"");
        }
        Ime.setChinese(inComment);
        applyCaretColor(editor, inComment);
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

    private static boolean isComment(PsiFile file, int offset) {
        if (offset < 0 || offset > file.getTextLength()) return false;
        PsiElement el = file.findElementAt(offset);
        while (el != null && !(el instanceof PsiFile)) {
            if (el instanceof PsiComment) return true;
            el = el.getParent();
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
            LOG.warn("Smart Input Lite: 设置光标颜色失败，已停用该功能", t);
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
}
