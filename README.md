# Riddle Diary for BOOX Note X3 Pro

*(中文在下方 / Chinese below)*

A fork of **[billtt/boox-riddle-diary](https://github.com/billtt/boox-riddle-diary)**,
retargeted from the Note X2 to the **BOOX Note X3 Pro** (Android 12 / API 32, 1860×2480 @
~304 PPI).

Upstream is itself a port of **[MaximeRivest/riddle](https://github.com/MaximeRivest/riddle)**
(Rust, reMarkable Paper Pro). Both are MIT licensed; the original copyright notice is kept in
[LICENSE](LICENSE).

---

## English

An enchanted diary for BOOX e-ink devices, recreating the Tom Riddle's Diary effect:

1. **Write on the blank page.** The ink appears under the nib.
2. **Rest the pen** (1.5 s by default) — or tap with two fingers to send at once — and the
   whole page slowly fades away.
3. The **diary writes back**, its reply rising out of the page word by word in a
   handwriting font (Ma Shan Zheng for Chinese, Dancing Script for Latin).
4. The reply lingers, fades, and the page is blank again.

A vision model reads the handwritten page and plays the diary. Any OpenAI-compatible
endpoint works — Zhipu, Aliyun DashScope compatible-mode, Moonshot, SiliconFlow,
Volcengine, OpenAI, or Anthropic's compatibility endpoint.

### What this fork changes

**Hardware ink.** Upstream concluded that on the Note X2 only `FEATURE_APP_TOUCH_RENDER`
delivers pen callbacks, that hardware rendering is silent, and therefore that live ink must
be drawn in software — ending with the note that true hardware-latency ink "would need a
lower-level Onyx path … beyond `onyxsdk-pen`".

That is not true on the X3 Pro. Measured with a standalone probe:

```
create(view, FEATURE_ALL_TOUCH_RENDER, cb, false) + setRawDrawingRenderEnabled(false)
  -> hardware live ink AND full callbacks
  -> ~491 Hz sample rate, pressure 0..4095, tilt populated, hover delivered
```

So the pen chip paints the stroke and the app only persists points, committing on pen-up.
The per-move software redraw is gone.

**`hiddenapibypass` is mandatory on Android 12.** This one is load-bearing and its absence
is very hard to diagnose. The Onyx SDK bootstraps its own hidden-API access by reflecting
into `VMRuntime.setHiddenApiExemptions`, which Android 12 blocks. The failure cascades
silently:

```
ReflectUtil.<clinit> fails
  -> Device.getBoardPlatform() fails
    -> EpdController static init fails
      -> EpdController.mapToRawTouchPoint cannot map view coords to the digitiser
        -> RawInputReader logs "Empty region detected when mapping"
          -> TouchHelper reports success and no callback ever fires
```

`TouchHelper.create()` returns a live object and every log line looks healthy. Install the
exemptions in `Application.onCreate`, before anything touches the SDK.

**Memory, restored.** The upstream Rust project keeps every finished turn — the pen strokes,
a transcription, and the reply — so a later "show me what I wrote about the garden" can
conjure the page back *in your own handwriting*. billtt's port dropped this; it is back, as
plain files under `getExternalFilesDir()/memories`. Recall is conversational: there is no
search UI, you ask the diary.

**One call, two products.** A single streaming vision call returns the reply *and* a verbatim
transcription, separated by a `⁂` line. Asking for a trailing marked line is far more
reliable with vision models than asking for JSON.

**Mixed Chinese/English.** The sentence splitter breaks on `。！？` without requiring a
following space (upstream's rule never fires on Chinese), the typesetter tokenises per script
run instead of deciding once for the whole reply, and each token is drawn in its own
typeface.

**Other.** Physical units instead of X2 pixel constants (0.5 mm stroke, 2.7 mm eraser,
pressure ceiling read from the device); multiple profiles, each pairing an endpoint with its
own persona; a guard against this firmware's spurious erase callbacks; only one backend
(OpenAI-compatible), so the `anthropic-java` dependency is gone.

### Security notes

The API key is encrypted at rest with a non-exportable Android Keystore key, excluded from
backup, and the settings dialog is `FLAG_SECURE`. The Onyx Maven repo is https (upstream
used http with `allowInsecureProtocol`) and the Gradle distribution is pinned by checksum.
Requests are genuinely cancellable and response sizes are bounded.

This app does **not** require `hidden_api_policy=1`; it uses `hiddenapibypass`, which is
per-process. Do not weaken that device-wide setting on its account.

Still missing: dependency verification metadata, tests, CI, and a release signing setup.
Build it yourself rather than trusting a debug-signed APK.

### Known issues

- **The transcription postscript is unreliable.** Across 28 archived turns the model
  omitted the marker line on 12 of them, leaving those pages with a reply but no
  searchable text and nothing for a later recall to match on. The instruction has been
  moved into the per-turn user message, and misses are now logged, but the improvement
  has not been measured yet.
- **Replies run long.** The persona asks for one to three short sentences; observed
  replies were nearer a hundred characters. Same fix, same caveat.
- `onEndRawDrawing` → `onPenUpRefresh` averages ~541 ms. Not yet tuned.
- The reply is revealed once the stream completes rather than sentence by sentence.
- Erase is whole-stroke, not pixel-level.

### Build & install

JDK 17–21 and Android SDK 34. **JDK 25 will not work** (Gradle 8.7 / AGP 8.5.2 cap at 21).
Point `local.properties` at your SDK (`sdk.dir=…`); it is gitignored.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# BOOX freezes newly installed apps; unfreeze before first launch:
adb shell pm enable com.billtt.riddle
```

Long-press with a finger to open settings and enter a Base URL, model and API key. Nothing
is committed to this repo; the key is stored on the device encrypted under an Android
Keystore key that cannot be exported and is destroyed when the app is uninstalled.

See [CLAUDE.md](CLAUDE.md) for the engineering notes.

---

## 中文

BOOX 电子墨水设备上的「Tom Riddle 日记本」——

1. **在空白页上写字**，墨迹在笔尖下实时出现
2. **停笔**（默认 1.5 秒），或**双指轻点**立刻发送，整页缓慢淡出消失
3. **日记回信**，字迹一个个从纸页深处浮上来（中文马善政，英文 Dancing Script）
4. 回信停留片刻后淡去，纸页重新空白

由视觉模型直接读取你的手写页并扮演日记本。任何 OpenAI 兼容接口都可以用 —— 智谱、阿里云百炼
兼容模式、月之暗面、硅基流动、火山方舟、OpenAI，或 Anthropic 的兼容端点。

### 这个 fork 改了什么

- **硬件墨迹**：上游因 X2 固件限制只能软件绘制实时笔迹，并认为硬件级延迟"需要 `onyxsdk-pen`
  之外的底层通路"。在 X3 Pro 上不成立 —— `FEATURE_ALL_TOUCH_RENDER` +
  `setRawDrawingRenderEnabled(false)` 同时给出硬件墨迹和完整回调（约 491 Hz 采样、4095 级
  压感、倾斜可用）
- **Android 12 必须加 `hiddenapibypass`**：Onyx SDK 通过反射 `VMRuntime.setHiddenApiExemptions`
  自举隐藏 API，A12 会拦截。之后一路静默崩塌到坐标映射失效，而 `TouchHelper.create()` 照常
  返回成功、日志全部正常 —— 这个坑极难定位
- **记忆层**：原项目会保存每一轮的笔画、转写和回信，日后可以让日记**用你自己的字迹**翻出旧页。
  billtt 移植时砍掉了，这里补回来了。检索是对话式的，没有搜索界面，你直接问日记
- **一次调用两样产出**：回信 + `⁂` 分隔的逐字转写。让模型末尾加一行标记，比要求它输出 JSON
  可靠得多
- **中英混写**：断句支持 `。！？`（上游那条规则在中文上永远不触发），排版按语种分段切分并逐词
  选字体
- 其余：物理单位替代 X2 像素常数、多档案（各带独立人设）、误擦保护

### 安全说明

API Key 用 Android Keystore 里不可导出的密钥加密后落盘，并排除在系统备份之外，设置窗口设了
`FLAG_SECURE`（截图和录屏拍不到）。Onyx 仓库改用 https（上游是 http + `allowInsecureProtocol`），
Gradle 发行版按校验和固定。请求可真正取消，响应大小有上限。

**本项目不需要 `hidden_api_policy=1`** —— 用的是只对本进程生效的 `hiddenapibypass`，不要为它
去改系统全局设置。

仍然缺少：依赖校验元数据、测试、CI、正式签名。建议自行构建，不要直接信任 debug 签名的 APK。

### 已知问题

- **转写后记不可靠**：28 条存档里有 12 条模型没有输出 `⁂` 标记行，那些页面只有回信、没有可
  搜索的原文，也无法作为调页的依据。已把该指令移到每轮的 user 消息里，并对缺失打日志，但
  改善程度尚未实测
- **回信偏长**：persona 要求一到三句，实测约一百字。同一处修改，同样尚未验证
- 抬笔到刷新平均约 541 ms，未调优
- 回信是整段浮现，还不是逐句
- 橡皮是整笔删除，不是像素级

### 构建

需要 JDK 17–21 和 Android SDK 34，**JDK 25 用不了**。`local.properties` 指向你自己的 SDK
（该文件已被 gitignore）。

用手指长按屏幕打开设置，填 Base URL、模型和 API Key。**仓库里不包含任何密钥**；key 存在设备
本地，并用 Android Keystore 中一把不可导出、卸载即销毁的密钥加密后落盘。

工程笔记见 [CLAUDE.md](CLAUDE.md)。
