# Caret IME

<!-- TODO: 在此放演示 GIF -->

[中文](#中文) | [English](#english)

---

## 中文

光标落在**注释**或**要写中文的字符串**里，自动切到中文输入法；落在**代码**里，自动切回英文。光标还会染成注释色，一眼就能看出当前是哪种模式。

一个极简的、Windows 专用的 IntelliJ IDEA 插件。

### 功能

- **注释里 → 中文输入法**，光标染成注释色
- **字符串字面量里 → 看内容**：
  - 已经写了中文的（`"你好"`）→ 中文
  - 刚打出一对空引号、光标夹在中间（`"|"`）→ 中文，摆明是要往里面写东西
  - 纯 ASCII 的串（`"userId"`、SQL 片段）→ 按代码处理，用英文，免得写英文串时被反复掰回中文
  - 文本块（`"""…"""`）同理
- **代码里 → 英文输入法**，光标恢复原色
- **你手动切过输入法，插件会让位**：在当前这条注释 / 这个字符串里不再抢，等你移到别处再恢复自动切换
- **打字打到一半不插手**：输入法候选串还没上屏时，插件不会注入任何按键，不会打断你正在敲的字
- 生效的文件类型：`java` / `kt` / `kts` / `groovy` / `yml` / `yaml` / `properties` / `sql`

### 安装

1. 到 [Releases](../../releases) 下载最新的 `.zip`
2. IDEA 里打开 `Settings` → `Plugins` → 右上角齿轮 ⚙ → `Install Plugin from Disk...`
3. 选中刚下载的 zip，重启 IDEA

### 测试环境

只在下面这一种组合上实测通过：

- Windows 11
- IntelliJ IDEA 2025.2
- 微软拼音（Windows 内置）

其他操作系统、其他输入法均**未测试**。

### 它是怎么切的

微软拼音这类 TSF 输入法没有可用的写入 API，所以插件只能**模拟按键**：先垫一个空键 `F24`，再发一对 `Shift` 按下 / 抬起。

垫 `F24` 是为了不让 IDEA 把你拖选时连发的 `Shift` 误判成「双击 Shift」（会弹出 Search Everywhere）。`F24` 现实中不存在、也没人会绑定，输入法那边完全不受影响。

### 副作用与风险

- 插件会向系统**注入 F24 和 Shift 按键**。如果你的 **F24 被 AutoHotkey 或其他软件绑定了**，可能产生冲突（比如误触发对方定义的动作）。本机测试时 F24 无任何绑定。
- 极少数情况下，注入的按键可能与你自己正在按的 Shift 在极短时间窗内重叠，但窗口只有约 100 毫秒，实际很难撞上。

### 已知限制

- **仅 Windows**。非 Windows 上插件会跳过全部初始化，不报错、不弹通知，等于不启用。
- 只验证过**微软拼音**。其他输入法（尤其是非 TSF 的老式 IME）行为未知。
- 如果你把输入法的**中英切换键改过**（改成了不是默认 Shift 的键），插件不会工作。
- 只对上面列出的文件类型生效。

### 从源码构建

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
jar cfM CaretIME-1.1.0.zip -C dist CaretIME
```

（用 JDK 自带的 `jar` 打包，产出的就是标准 zip，省得依赖 `zip` 命令。）

---

## English

Caret IME switches the input method automatically based on where the caret is: **Chinese inside comments and inside Chinese string literals, English inside code.** The caret is tinted with the comment colour, so you can tell the current mode at a glance.

A minimal, Windows-only IntelliJ IDEA plugin.

### Features

- **In a comment → Chinese**, and the caret takes the comment colour.
- **In a string literal → judged by content:**
  - already contains Chinese (`"你好"`) → Chinese
  - a freshly typed empty `""` with the caret between the quotes → Chinese (you are clearly about to type into it)
  - ASCII-only (`"userId"`, SQL snippets) → treated as code and stays English, so writing English strings never fights you
  - text blocks (`"""…"""`) work the same way
- **In code → English**, and the caret goes back to its normal colour.
- **Switch the IME yourself and the plugin backs off** — it stops switching inside that comment or string, and resumes once the caret moves elsewhere.
- **Never interferes mid-typing** — while an IME composition is still on screen, the plugin injects nothing and will not break the word you are typing.
- File types: `java` / `kt` / `kts` / `groovy` / `yml` / `yaml` / `properties` / `sql`

### Install

1. Download the latest `.zip` from [Releases](../../releases).
2. In IDEA: `Settings` → `Plugins` → gear icon ⚙ → `Install Plugin from Disk...`
3. Pick the zip and restart IDEA.

### Tested on

Only this one combination has been tested:

- Windows 11
- IntelliJ IDEA 2025.2
- Microsoft Pinyin (built into Windows)

Other operating systems and input methods are **untested**.

### How it switches

Input methods such as Microsoft Pinyin are TSF-based and expose no usable write API, so the plugin can only **simulate keystrokes**: a decoy `F24` press/release followed by a `Shift` press/release.

The `F24` decoy keeps IDEA from mistaking the `Shift` keystrokes — which fire rapidly when you drag-select across a comment boundary — for a "double Shift" (the Search Everywhere shortcut). `F24` does not exist on real keyboards and is bound by nobody, and the input method itself is unaffected.

### Side effects and risks

- The plugin **injects F24 and Shift keystrokes** into the system. If your **F24 is bound by AutoHotkey or another tool**, there may be a conflict (for example, it could trigger whatever that tool defines). On the machine this was tested on, F24 is unbound.
- In rare cases an injected keystroke may overlap with a Shift you are pressing yourself, but the window is about 100 ms and is hard to hit in practice.

### Known limitations

- **Windows only.** On other platforms the plugin skips all initialisation — no error, no notification, effectively disabled.
- Only **Microsoft Pinyin** has been verified. Behaviour with other input methods (especially older, non-TSF IMEs) is unknown.
- If you have **changed your IME's Chinese/English toggle key** away from the default Shift, the plugin will not work.
- Only the file types listed above are supported.

### Build from source

Requires **JDK 21** and an **IntelliJ IDEA 2025.2** installation directory (compiling uses the `lib/*.jar` it ships with).

```bash
export IDEA_HOME="/path/to/IntelliJ IDEA 2025.2"   # point this at your own IDEA install
bash build.sh
```

The output is `build/CaretIME.jar`.

To package a release zip:

```bash
mkdir -p dist/CaretIME/lib
cp build/CaretIME.jar dist/CaretIME/lib/
jar cfM CaretIME-1.1.0.zip -C dist CaretIME
```

(Uses the `jar` tool bundled with the JDK, which produces a standard zip — no need for the `zip` command.)
