# 唤醒锁管家 (WakeGuard)

一个用于查看和拦截 Android 应用唤醒锁 / 闹钟唤醒的工具。**Root（KernelSU）+ LSPosed 双通道**：
Root 负责查看与后台软限制，Xposed 负责在 system_server 里真正把锁丢掉。

实测设备：OPPO PKJ110 · Android 17 (API 37) · ColorOS · KernelSU · LSPosed 2.2.1 (API 102)。

当前版本 **2.5.3** · [更新日志](CHANGELOG.md) · 免编译安装包见 [release/](release/)

> 需要：Android 8.0+ · root（KernelSU / Magisk） · LSPosed（作用域勾选「系统框架」）
> 装完或更新后**必须重启一次**，否则系统进程里跑的还是旧代码。

---

## 一、功能

### 1. 唤醒锁实时查看（Root）
- **应用唤醒锁**：解析 `dumpsys power`，列出当前所有被持有的 partial wakelock，显示 tag、持有应用（uid → 应用名/包名）、已持有时长。点条目可直接 `am force-stop`。
- **系统挂起锁**：解析 `Suspend Blockers` 段（Android 17 上替代了读不到的 debugfs），显示各 `PowerManagerService.*` 挂起锁的引用计数与持有状态。若设备导出了 `/sys/kernel/debug/wakeup_sources`，会自动回退去读它。

> 注意：Android 17 / ColorOS 把锁拆成了 `Wake Locks` / `Proxyed Wake Locks` / `Restored Wake Locks` 三段，解析采用全局扫描，不依赖段落顺序。

### 2. 应用管控（每个应用四个开关）

| 开关 | 机制 | 生效条件 |
|---|---|---|
| 拦截全部唤醒锁 | Xposed 在 `PowerManagerService.acquireWakeLockInternal` 丢弃 | 模块生效，重启后 |
| 拦截闹钟唤醒 | Xposed 在 `AlarmManagerService.setImpl` 丢弃 | 模块生效，重启后 |
| 按 TAG 精确拦截 | 同上，但只丢弃 tag 命中的锁（支持 `*xxx` 包含匹配） | 模块生效，重启后 |
| 限制后台任务 | Root `am set-standby-bucket <pkg> restricted` | 立即 |
| 限制后台服务 | Root `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` + force-stop | 立即 |

**生效时机**（每个应用独立）：`始终` / `仅锁屏后` / `自定义时段`（两个时间点，跨午夜自动处理）。

判定直接读唤醒状态，**按 Android 版本自适应**（位置变过）：

| 优先级 | 位置 | 适用 |
|---|---|---|
| 1 | `PowerManagerService.mWakefulness` | Android 13 及以前 |
| 2 | `PowerManagerService.mPowerGroups[0].mWakefulness` | Android 14+（本机 Android 17 走这条） |
| 3 | `PowerManagerService.mBinderService.isInteractive()` | 兜底 |
| 4 | 扫描到的无参 int getter | 兜底 |

Android 14 起 `mWakefulness` 已从 PMS 移到 `PowerGroup`，PMS 只剩 `mPowerGroups` 容器——
所以直接读 `PMS.mWakefulness` 在新系统上会 `NoSuchFieldError`。

取值含义：

| 值 | 状态 | 视为 |
|---|---|---|
| 0 | ASLEEP | 熄屏 |
| 1 | AWAKE | **亮屏** |
| 2 | DREAMING | 熄屏 |
| 3 | DOZING (AOD) | 熄屏 |

读不到时按「亮屏」处理（宁可不拦，避免误杀正常使用）。日志会打
`screen state -> Awake(亮屏) (mode=2)`，`mode` 表示命中的是哪条路径。

> **语义边界**：时机判断发生在**申请那一刻**。熄屏只拦截新的申请，
> 不会把已经持有的锁强行收回——否则正在跑的服务会突然失去持有凭证。
> 想立刻清掉已在生效的锁，用「强制停止」。

**周期放行（维护窗口）**：解决"拦死了就收不到消息"的问题。

给某个应用配「每 30 分钟放行 3 分钟」后，熄屏期间的行为是：

```
熄屏 ──拦 30 分钟──┬──放行 3 分钟──┬──拦 27 分钟──┬──放行 3 分钟──┬── …
                  0              30             33             60
```

- **先拦满一个完整间隔再开窗**：刚锁屏时应用往往还在收尾，立刻放行等于没拦住
- 间隔从**熄屏时刻**起算，亮屏即归零，下次熄屏重新计时
- 放行窗口内申请到的锁会正常持有，窗口结束后不会被强行收回（应用自己释放）
- 可选间隔：10 分钟 / 30 分钟 / 1 小时 / 2 小时 / 3 小时；放行时长：1 / 2 / 3 / 5 分钟

这样微信这类应用在熄屏待机时既能省电，又不会彻底断联。

### 3. 唤醒统计（找出该拦谁）
按应用汇总「熄屏 / 亮屏」期间申请了多少次唤醒锁与闹钟，用来判断谁在偷偷拉活。

- 数据由 **system_server 侧记录**，所以**被拦掉的申请也计入**——这是关键：
  一个应用被拦了 200 次，说明它一直在试图唤醒，正是最该管控的
- 排序按「熄屏次数」降序，熄屏期间的活动最值得关注
- **持锁总时长**：除了次数，还累计每个 tag 从 acquire 到 release 实际占用的毫秒数，并区分熄屏/亮屏。
  「熄屏 6 小时持有 47 分钟」比「申请 678 次」更接近真相，也是唯一能量化收益的指标
- **24 小时唤醒曲线**：统计页顶部自绘柱状图，当前小时高亮，一眼看出凌晨几点的尖峰
- 点进某个应用可看逐条明细（每个 tag 各申请了多少次、持锁多久），右侧「拦截」按钮支持两种粒度：
  - **只拦这个锁**：精确匹配 tag
  - **拦同前缀的锁**：把 tag 截到最后一个 `:` 或 `/` 之前，用 `*前缀` 做包含匹配
    （对 `job/xxx:123` 这种带随机后缀的锁很有用）
- 顶部筛选：全部 / 熄屏活跃 / 有拦截

实现细节与限制：
- 落盘 `/data/system/wakeguard_stats.txt`，字段用 `\u0001` 分隔；首次或超过 512KB 全量重写，其余只追加变化的 key
- 最多 20 秒刷一次盘，只在有新增时写
- **重启后清零**（统计在 hook 内存里累积），页面顶部会标出统计起始时间
- 状态日志页有一行「唤醒统计」，显示文件是否已在写入；另有一行「Doze 休眠」显示
  `deviceidle` 的当前状态（IDLE / IDLE_MAINTENANCE / ACTIVE），用来解释「拦了为什么还能收到消息」
- 统计文件格式 3 起包含持锁时长与 24 小时分布；旧格式文件仍能读取，只是缺这两项

### 4. 智能建议（从统计直接生成规则）
统计页右上角的「建议」会按**熄屏期间**的申请量推导该拦什么，点一下就下发规则，不用自己去翻列表。

判定逻辑刻意保守：

- 只看熄屏期间的申请——亮屏时多申请几次本来正常，拿它当依据会误伤
- 系统应用、桌面、电话、输入法、厂商服务一律不推荐
- 已配过规则的应用不再重复建议
- 有明确主因的，只建议拦那几个 tag；没有主因但总量很高的，才建议整包

### 5. 防误伤：观察模式 · 临时放行 · 充电放行

| 功能 | 作用 | 入口 |
|---|---|---|
| 观察模式 | 命中规则只记账不拦截，日志记为 `DRY-BLOCK` | 状态日志 → 观察模式 |
| 临时全部放行 | 放行 15/30/60 分钟或到次日 7 点，随时取消 | 状态日志 → 临时全部放行；或快捷设置磁贴 |
| 充电时放行 | 插着充电器时跳过所有拦截 | 状态日志 → 充电时放行 |

观察模式适合新环境先跑一两天，看清谁在折腾再决定拦什么；临时放行适合半夜等一个重要消息，
不必为了放行去改规则再改回来。

### 6. 规则备份与恢复

- **备份**：状态日志 → 备份规则到下载目录，导出 JSON
- **恢复**：状态日志 → 从备份文件恢复规则，走系统文件选择器，按需覆盖同名项

换机、刷机、重装都不必重新配一遍。

### 7. 状态日志
- root 授权状态、Xposed 模块自检、规则文件可读性、唤醒统计是否在落盘、当前临时放行状态
- 事件日志（读 `/data/system/wakeguard_debug.log`，不会被 logcat 滚掉），默认收起，可展开
- 调试日志开关、一键撤销全部限制、跳转 LSPosed 管理器

### 8. 诊断导出（不用电脑也能排查）
「状态日志」→ **导出诊断报告**，生成一个自包含的 txt 到 **下载目录**，
直接发出来就能分析，不需要连 USB。

报告包含：

| 区块 | 内容 |
|---|---|
| 环境 | 机型、Android 版本、App 版本、开机时长 |
| 规则配置 | 每个被管控应用的开关、tag 规则、生效时机、周期放行 |
| hook 自检 | `hooks installed: ...`（含 `caller-uid=` 判断 uid 捕获是否装上） |
| 事件日志 | `wakeguard_debug.log`（+ 轮转出的 `.1`）尾部 |
| 统计原始数据 | `wakeguard_stats.txt` 全文 |
| 当前活跃唤醒锁 | `dumpsys power` 的 Wake Locks 段 |
| logcat 兜底 | 最近 300 条 WakeGuard 日志（hook 没重启时仍有内容） |

事件日志记什么（每行 `MM-dd HH:mm:ss.SSS 内容`）：
```
BLOCK-WL uid=10668 tag=WG_TEST_LOCK:Test rule=TAG caller=10668 screen=on
SKIP-WL  uid=10086 tag=MicroMsg.SyncService rule=WL screen=off   ← 命中规则但被时机/窗口放行
BLOCK-ALARM uid=10086 screen=off
window OPEN uid=10086 elapsed=1802s (30min/3min)
window CLOSE uid=10086 elapsed=1985s (30min/3min)
screen state -> Dozing(AOD) (mode=2)
```

`BLOCK` / `SKIP` 成对出现是刻意的：只记拦截的话，
"规则明明配了却没生效"这种情况永远看不出原因。

日志限流与轮转：同 key 10 秒一条；满 4000 行轮转为 `.1`（只留两份），
所以长期开着也不会把 `/data/system` 写爆。排查完可以在状态页关掉开关。

---

## 二、安装步骤

**方式一：直接装成品包**

下载 [release/WakeGuard-2.5.3.apk](release/WakeGuard-2.5.3.apk)，传到手机安装即可。
包是用 debug 证书签的，自用没问题；想长期使用建议自己重新签名，否则改签名后需要先卸载旧版。

**方式二：自己编译**

见下方「构建」。

**装好后必做**

1. 打开应用 → root 管理器弹窗授权
2. LSPosed → 模块 → 唤醒锁管家 → **启用模块** + 勾选作用域 **系统框架**
3. **重启手机**
4. 回到「状态日志」页，应显示 `Xposed 模块：已加载 · ... hooks installed:`
5. 打开「统计」页，顶部若出现 `模块 vX ≠ App vY`，说明系统进程里仍是旧代码，需再重启

> 部分 ROM（如 ColorOS / HyperOS）上 `pm install` 会被第三方安装器接管并挂住，
> 此时改用 `adb install`，或在手机上直接点开 APK 安装。

---

## 三、实现要点

### Hook 点（全部按方法名扫描，不写死签名）
```
com.android.server.power.PowerManagerService#acquireWakeLockInternal  → 丢弃
com.android.server.power.PowerManagerService#releaseWakeLockInternal  → 吞掉配对的 release
com.android.server.alarm.AlarmManagerService#setImpl(void, 含 PendingIntent) → 丢弃
```

三个关键设计：

1. **判断 uid 不看参数下标**：遍历所有参数，凡是命中规则集合的 `int`/`long` 值就认定为该 uid，再兜底用 `Binder.getCallingUid()`。这样跨版本、跨 ROM 改签名也不会失效。
2. **丢弃 acquire 时记住 binder token**，release 时一并吞掉，避免 system_server 走到 `Wakelock released but not found` 的异常路径。
3. **规则下发用 LSPosed 的 New XSharedPreferences**：模块声明 `xposedminversion=93` + `xposedsharedprefs=true`，App 侧用 `MODE_WORLD_READABLE(1)` 打开 prefs，system_server 侧用 `XSharedPreferences("com.wakeguard.ctrl","wg_rules")` 读取，带 1.5s 缓存，改开关后无需重启即可生效。

### 统计为什么必须在 hook 里做
同一个 hook 点承担两件事：**先记录，再判定**。

被拦掉的申请不会进 `dumpsys power`，App 侧再怎么轮询也看不见它。而"这个应用被拦了 500 次"恰恰是最有信息量的数据。
所以顺序是：拿调用方 uid + tag + 当前屏幕状态 → 先 `noteStat()` → 再走拦截判定 → 被拦时补一次 `noteStatBlocked()`。
统计按 `kind+uid+tag` 聚合在内存里，最多 20 秒刷一次盘，重启清零。

### 一个花了很久才定位的坑：`clearCallingIdentity`
第一版统计里，微信的 `MicroMsg.Alarm`、`MicroMsg.SyncService` 全部被记到 **uid 1000**，
唤醒锁统计整体失效（闹钟却正常，因为那用的是 `PendingIntent.getCreatorUid()`）。

原因是 AOSP 的 `PowerManagerService$BinderService.acquireWakeLock` 长这样：

```java
final long ident = Binder.clearCallingIdentity();
try { acquireWakeLockInternal(...); }   // ← 我们的 hook 点
finally { Binder.restoreCallingIdentity(ident); }
```

进到 `acquireWakeLockInternal` 时 identity 已经被清掉，`Binder.getCallingUid()` 只会返回
system_server 自己的 1000。拦截逻辑没受影响，是因为它从**参数里**匹配 uid 值。

修法：额外 hook 一层 `BinderService.acquireWakeLock`，在它的 before 里把真实 uid 存进
`ThreadLocal`，`acquireWakeLockInternal` 的 before 取出即用；后者为空时（内部调用路径）
退回从参数推断应用 uid（`10000 ~ 199999` 范围）。`acquireWakeLock` 的 after 再清一次
ThreadLocal，避免 binder 线程复用时拿到脏值。

**教训**：hook 系统内部方法时，`Binder.getCallingUid()` 不一定是调用方——
上游可能已经 `clearCallingIdentity`。判断依据要多留一条路。

### 构建（无需 Gradle）
`aapt2 compile → aapt2 link → javac → d8 → 塞 classes.dex → zipalign → apksigner`

```powershell
# 只用环境变量（ANDROID_SDK_ROOT / JAVA_HOME）
.\build.ps1

# 显式指定路径
.\build.ps1 -Sdk "D:\android-sdk" -Jdk "C:\jdk17"

# 指定版本号
.\build.ps1 -VersionName "2.5.4" -VersionCode 20504
```

产物在 `build\WakeGuard-<版本>.apk`。

依赖：

| 依赖 | 说明 |
|---|---|
| Android SDK | 需要 build-tools（aapt2 / d8 / zipalign / apksigner）与 platforms/android-35 |
| JDK 17 | javac / jar / keytool |
| Python 3 | 仅用于把 dex 注入 aapt2 产物，无第三方库 |
| `libs/api-82.jar` | Xposed API，用于编译 hook 代码；来自 LSPosed / `de.robv.android.xposed:api:82` |

脚本会自动挑选已安装的最新 build-tools 与 platform，并在首次运行时生成 `debug.keystore`（仅供自测）。

---

## 四、已验证（实测数据）

用测试桩 `WakeTest`（自持 `WG_TEST_LOCK` + 每 3 秒精确闹钟）验证。
该工具已升级为对照实验式自检面板：同时申请一把**规则内**的目标锁
（`WG_TEST_LOCK:Test`）和一把**规则外**的对照锁（`WG_TEST_KEEP:Keep`），
只有「目标没了、对照还在、闹钟没响」才算精确拦截。

| 项目 | 拦截前 | 拦截后 |
|---|---|---|
| `dumpsys power` 中的 WG_TEST_LOCK | 存在，`ACQ=-4s238ms` | 不存在 |
| 对照锁 WG_TEST_KEEP（只配 tag 规则时） | 存在 | 仍存在（未误伤） |
| 闹钟触发次数（12 秒窗口） | 2 次 | 0 次 |
| system_server 日志 | — | `blocked TAG uid=10668 tag=WG_TEST_LOCK:Test`<br>`blocked ALARM uid=10668` |
| Root 限制 | — | `am get-standby-bucket` = 45 (restricted)，`appops get` = ignore |

**生效时机（仅锁屏后）实测**，同一套规则三态切换：

| 屏幕状态 | 目标锁 | 对照锁 | 日志 |
|---|---|---|---|
| Awake（亮屏） | 存在 | 存在 | `screen state -> Awake(亮屏) (mode=2)` |
| Dozing（AOD 熄屏） | **被丢弃** | 存在 | `screen state -> Dozing(AOD) (mode=2)` + `blocked TAG` |
| Awake（再点亮） | 存在 | 存在 | `screen state -> Awake(亮屏) (mode=2)` |

状态是实时读取的，不是一次性的；`mode=2` 表示走了 `mPowerGroups` 那条读取路径。

**周期放行实测**（间隔 10 分钟、放行 3 分钟，全部为实机采样）：

| 采样时刻 | 距熄屏 | 目标锁 | 日志 |
|---|---|---|---|
| 22:50:55 | t0 | 被丢弃 | `熄屏计时开始，周期放行以此为基准` |
| 22:55:51 | +4.9 分 | 被丢弃 | `blocked TAG` |
| 23:00:23 | +9.5 分 | 被丢弃 | `blocked TAG` ← 证明"先拦满一个间隔" |
| 23:01:43 | +10.8 分 | **出现** | 无 `blocked` ← 窗口打开 |
| 23:04:30 | +13.6 分 | 被丢弃 | `blocked TAG` ← 窗口关闭 |
| 23:11:24 | +20.6 分 | **出现** | 闹钟实测触发 `ALARM FIRED #1 #2` |

最后一行的意义：窗口内**闹钟也被放行**，这才是微信能收到消息的前提。

---

## 五、已知限制

- 更新模块 APK 后，system_server 里跑的还是旧代码，**hook 侧的改动需要重启才生效**；App 界面改动不用。
- 部分内核不导出 `/sys/kernel/debug/wakeup_sources`（本机就是），此时「系统挂起锁」读的是 `Suspend Blockers`。
- 拦截粒度可选「整包（uid）」或「单个 tag」；整包模式下同一 uid 下的共享 uid 应用会一起被拦。
- 已存在于系统中的拦截规则不会自动清理：换机或换规则时用「撤销全部限制」。
- **LSPosed 把本模块标记为 `legacy`**，并提示「使用了已废弃且即将移除的功能」。
  原因是配置下发用了 `XSharedPreferences` + `xposedsharedprefs`（旧 Xposed API）。
  当前实测可用，但未来 LSPosed 若移除该 API，规则会读不到。
  迁移方向：把规则改由模块 App 侧写成世界可读的普通文件，或通过 `ContentProvider` 提供，
  hook 侧轮询/mtime 监听读取，不再依赖 `XSharedPreferences`。
- 「生效时机」只在**申请那一刻**判断，熄屏不会回收已经持有的锁。
- 建议只对确认的耗电大户动手，误拦系统组件可能导致通知延迟、后台任务异常。

---

## 六、目录

```
WakeGuard/
  app/src/main/
    java/com/wakeguard/ctrl/
      MainActivity.java   四标签外壳
      LocksPage.java      唤醒锁 / 挂起锁列表
      AppsPage.java       应用管控（四类开关 + 时机 + 周期放行）
      StatsPage.java      唤醒统计 + 智能建议
      StatusPage.java     状态、日志、观察模式、临时放行、备份恢复
      Stats.java          统计文件解析（增量追加，后写覆盖）
      Suggestions.java    建议引擎（保守策略，跳过系统应用）
      WakeData.java       dumpsys 解析
      Rules.java          规则存储（MODE_WORLD_READABLE）+ 导入导出
      RootShell.java      su -c 执行器（单次调用 + 超时）
      Hooks.java          system_server 侧 hook 与统计落盘
      QsTile.java         快捷设置磁贴
      Seg.java / Ui.java  COUI 风格控件与主题
      XposedMain.java     模块入口
    res/                  资源（含浅色/深色两套）
    assets/xposed_init
  libs/api-82.jar         Xposed API（编译期依赖）
  build.ps1               构建脚本
  inject_dex.py           把 classes.dex 塞回 aapt2 产物
  release/                预编译 APK
  CHANGELOG.md            更新日志
```

WakeTest 是开发期用来验证拦截的测试桩（自持一把测试锁 + 精确闹钟），不属于主程序，未包含在本仓库。

---

## 七、免责声明

这个工具会修改 Android 系统进程的行为，属于「用好了很省电、用错了会出问题」的那类工具：

- 拦截系统应用或共用 uid 的组件，可能连带影响通知、来电、闹钟、定位等功能
- 拦截闹钟会同时挡掉依赖闹钟拉活的消息推送，这是原理决定的，不是 bug
- 不同 ROM 的电源管理实现差异很大，本机验证通过不代表所有机型都正常

建议先用**观察模式**跑一两天，看清谁在折腾再动手；改动前先备份规则。作者不对使用后果负责。

## 八、许可证

[MIT](LICENSE) © 周一丶

## 九、致谢

思路来自 Android 电源管理里几个长期被厂商和第三方应用反复利用的机制：
`PowerManagerService` 的唤醒锁、`AlarmManagerService` 的精确闹钟、以及 Doze / standby bucket 的分级后台策略。
LSPosed 提供的 `XSharedPreferences` 让配置能在重启后依然免重启生效，省掉了大量调试成本。
