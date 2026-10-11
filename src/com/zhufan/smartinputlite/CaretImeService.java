package com.zhufan.smartinputlite;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.util.SystemInfo;

/**
 * 插件的应用级生命周期锚点，只负责清理。
 *
 * 平台保证插件 service 在插件被禁用/卸载时收到 dispose()，而 EditorFactoryListener
 * 这类扩展的实现类没有这个保证 —— 所以把平台级清理挂在这里。
 * {@link CaretImeSwitcher} 在首个编辑器创建时 getService 本类，既保证它被实例化，
 * 也让挂出去的 caret listener 有一个可靠的 parentDisposable。
 */
@Service(Service.Level.APP)
public final class CaretImeService implements Disposable {

    @Override
    public void dispose() {
        // 非 Windows 下从未初始化（也就没碰过 Ime），别去触达它
        if (!SystemInfo.isWindows) return;
        Ime.shutdown();
        CaretImeSwitcher.disposeAll();
    }
}
