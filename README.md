# Caret IME

<!-- TODO: 在此放演示 GIF -->

光标落在**注释**里自动切到中文输入法，落在**代码**里自动切回英文输入法，同时把光标染成注释色，一眼就能看出当前是哪种模式。

一个极简的、Windows 专用的 IntelliJ IDEA 插件。

## 功能

- 光标在注释里 → 自动切中文输入法，并把光标染成注释色
- 光标在代码里 → 自动切英文输入法，光标恢复原色
- 生效的文件类型：`java` / `kt` / `kts` / `groovy` / `yml` / `yaml` / `properties` / `sql`
- 切换后会回读校验；万一没生效会降级到备选切换方式，并在一段时间后自动重试

## 测试环境

只在下面这一种组合上实测通过：

- Windows 11
- IntelliJ IDEA 2025.2
- 微软拼音（Windows 内置，属于 TSF 输入法）

其他操作系统、其他输入法均**未测试**。

## 工作原理

### 为什么必须模拟按键

Windows 输入法的中/英状态，正统写法是用 IMM32 的 `ImmSetOpenStatus` / `ImmSetConversionStatus` 直接设置。但对微软拼音这类 **TSF 输入法**，这两个写入接口一律返回 `FALSE`、状态纹丝不动（已用探针复核确认）。

唯一有效的办法是**模拟按键**。微软拼音默认用「单独按一下 Shift」切换中英，所以插件用 `keybd_event` 注入一对 Shift 按下 / 抬起。

### 为什么要先垫一个 F24

只注入 Shift 会撞上 IDEA 的另一个机制：**双击 Shift 会弹出 Search Everywhere**。

用鼠标拖选、光标快速扫过注释与代码的交界时，插件会连着注入很多次 Shift，两次之间只隔几十毫秒。IDEA 分不清这是人按的还是程序打的，就把它判成了「双击 Shift」。

修法是每次注入 Shift 之前，先垫一个完整的 **F24 按下 / 抬起**：

```
F24↓ F24↑ Shift↓ Shift↑
```

F24 是个现实中不存在、也没人会绑定的空键码。IDEA 判定双击 Shift 的规则是「两次 Shift 之间不能夹别的键」，F24 一插进去，这个条件就永远凑不齐了。

输入法那边完全不受影响 —— 按键是先经过输入法、再传给应用的。输入法照样收到干净的 Shift，中英切换照做。**F24 只用来骗 IDEA，不打扰输入法。**

## 副作用与风险

- 插件会向系统**注入 F24 和 Shift 按键**。如果你的 **F24 被 AutoHotkey 或其他软件绑定了**，可能产生冲突（比如误触发对方定义的动作）。本机测试时 F24 无任何绑定。
- 极少数情况下，注入的按键可能与你自己正在按的 Shift 在极短时间窗内重叠，但窗口只有约 100 毫秒，实际很难撞上。

## 已知限制

- **仅 Windows**。非 Windows 上插件会跳过全部初始化，不报错、不弹通知，等于不启用。
- 只验证过**微软拼音**。其他输入法（尤其是非 TSF 的老式 IME）行为未知。
- 如果你把输入法的**中英切换键改过**（改成了不是默认 Shift 的键），插件不会工作。
- 只对上面列出的文件类型生效。
- 切换用「模拟按键」实现，所以严格来说不是零副作用，见上面的风险说明。

## 安装

1. 到 [Releases](../../releases) 下载最新的 `.zip`
2. IDEA 里打开 `Settings` → `Plugins` → 右上角齿轮 ⚙ → `Install Plugin from Disk...`
3. 选中刚下载的 zip，重启 IDEA

## 从源码构建

需要 **JDK 21**，以及一个 **IntelliJ IDEA 2025.2** 的安装目录（编译要用它自带的 `lib/*.jar`）。

```bash
export IDEA_HOME="/path/to/IntelliJ IDEA 2025.2"   # 换成你自己的 IDEA 安装目录
bash build.sh
```

产物在 `build/CaretIME.jar`。

打包成可发布的 zip：

```bash
mkdir -p dist/CaretIME/lib
cp build/CaretIME.jar dist/CaretIME/lib/
jar cfM CaretIME-1.0.0.zip -C dist CaretIME
```

（用 JDK 自带的 `jar` 打包，产出的就是标准 zip，省得依赖 `zip` 命令。）

## 反馈问题

提 Issue 时请附上：

- IntelliJ IDEA 版本（`Help` → `About`）
- 你用的输入法名称（如「微软拼音」「搜狗拼音」……）
- 该输入法的**中英切换键**设置（默认是 Shift，改过的话请说明）
- 相关日志：`Help` → `Show Log in Explorer`，搜 `Caret IME`

---

## English Summary

**Caret IME** is a minimal, Windows-only IntelliJ IDEA plugin that switches the input method based on the caret position — Chinese inside comments, English inside code — and tints the caret with the comment colour.

It switches the IME by injecting keystrokes, because the Windows IMM32 write API has no effect on TSF-based input methods such as Microsoft Pinyin. To avoid triggering IDEA's "double Shift" (Search Everywhere) when the caret sweeps rapidly across a comment boundary, every switch first taps a decoy `F24` key, which resets IDEA's double-modifier-click detector without affecting the IME.

Tested only on Windows 11 + IntelliJ IDEA 2025.2 + Microsoft Pinyin. Licensed under MIT.
