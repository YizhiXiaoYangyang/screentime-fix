# 我真没玩手机

一个 LSPosed 模块，改「设置 → 屏幕使用时长」里显示的数字：指定时间段的时长和解锁次数直接抹掉，也能反向往上加。

## 功能简介

- 时段抹除：按你设的时段（可多段、可跨零点）扣掉使用时长和解锁次数
- 单应用隐藏：选中的 App 不计入总量，排行榜、分类列表里也不出现
- 生效范围：每天 / 仅工作日 / 仅周末 / 自定义星期，也能只让某一天生效
- 每日上限：时段裁完还超上限，就从最活跃的小时往下扣，排行榜、分类一起缩
- 数字抖动：每段窗口起止各偏 ±0~5 分钟，同一天刷新几次看到的都一样
- 反向加成：给总时长、单个 App、解锁次数加数字（v4.0「我在玩手机啊」）
- 覆盖范围：统计详情页（日 / 周 / 月）、首页「健康使用手机」卡片、单应用详情、桌面和负一屏小组件
- 不留破绽：首次解锁时间跟着挪，不会出现「0 分钟却解锁 12 次」
- 不动数据：只改「设置」读取时显示的数字，关开关或卸载就恢复原样

## 运行要求

- 小米 HyperOS / MIUI 的「设置」（hook 的是它的屏幕使用时长）
- Android 7.0 及以上（minSdk 24）
- LSPosed 或兼容的免 Root 框架，作用域只勾「设置」
- 实测：Redmi / HyperOS 3.0 / 设置 16.3.260227.00 / LSPosed(Vector)；别的版本没测过，不保证能用

## 反向：我在玩手机啊（v4.0）

- 每天给总时长加 N 分钟，落点三选一：指定某个 App / 按现有使用比例摊到各 App / 造一条「设置」的记录
- 单独给某个 App 每天加 N 分钟，可以加多个，数值各自独立
- 每天给解锁次数加 N 次
- 加出来的量怎么排进 24 小时：优先按那天真实的活跃分布摊；填了「加成时段」就只落在该时段；都没填就均匀
- 先裁（时间段 + 每日上限）再注入加成，两者可以同时开，加成不会被上限削掉
- `appDetail`、`categoryDetail`（含分类里再挂的一层）、小时桶、总时长四处一起加，上期同量加
- 加成是查询时实时算的，不落库；改回 0 就恢复真实显示

## 安装

> v4.2 起包名是 `io.github.yizhixiaoyangyang.screentime`（LSPosed 官方仓库要求 `io.github.<用户名>.<模块>`）。它跟旧的 `com.eta.lab.screentime` 是两个应用，只能留一个，同时装统计会被裁两次。从旧版升上来：装新包 → LSPosed 里重新启用并勾「设置」→ 打开新模块把时间段重设一遍，旧配置不会自动搬。

装法两种：

- 在 LSPosed 管理器的「模块仓库」里搜「我真没玩手机」，或直接开 [官方仓库页](https://modules.lsposed.org/module/io.github.yizhixiaoyangyang.screentime/)，以后有新版本会提示更新
- 或者去 [Releases](https://github.com/YizhiXiaoYangyang/screentime-fix/releases/latest) 下最新的 `stfix-vX.Y.apk`，不用自己编译

装完三步：

1. 安装 APK
2. LSPosed 里启用模块，作用域只勾「设置」
3. 打开「我真没玩手机」，设好时间段、选好要隐藏的 App → 保存 → 退出「屏幕使用时长」再进一次

不需要任何 adb 授权（v3.1 起）。配置存在模块自己的 SharedPreferences 里，hook 端走 Xposed 自有通道读，见下面「配置存在哪」。

## 配置存在哪

不再写 `Settings.Global`，也不再申请 `WRITE_SECURE_SETTINGS`。模块 App 把配置写进自己的 SharedPreferences（文件名 `stfix`，`MODE_PRIVATE`），`commit()` 同步落盘。

hook 端跑在「设置」进程里，按顺序读两条通道，谁先读到数据就锁定谁：

1. `de.robv.android.xposed.XSharedPreferences("io.github.yizhixiaoyangyang.screentime", "stfix")`，每次 `reload()`，改完保存立刻能读到新值；
2. 读不到就退到模块 App 里那个只读 ContentProvider（`content://io.github.yizhixiaoyangyang.screentime.config/conf`，`exported`、无自定义权限、只暴露 `query`），每次查询都是实时值。

实测（Redmi / HyperOS 3 / LSPosed(Vector)）XSharedPreferences 在这个环境下读不到配置，返回空，所以实际生效的是 provider 通道；xprefs 那条留着，换到能读的环境会自动用回去。判别看 logcat：`[ScreenTimeFix] cfg via=xprefs` 或 `cfg via=provider`。

老用户升级：第一次读配置时，如果新通道里一条配置都没有、而 `Settings.Global` 里还有 v3.0 的老值（读它不需要权限），会把这批老值一次性搬进来，配置不丢。之后系统设置里的 `stfix_*` 就再也不会被读写。

## 使用

- 时间段可多段、可跨零点（23:00 — 07:00 这种）
- 「单独隐藏的 App」里点「＋ 选择要隐藏的应用」，搜索并选中；被隐藏的 App 按小时桶从总时长里扣掉，所以总数、排行、时间轴三者是自洽的
- 顶部状态卡只讲开关状态和「怎么才生效」，不显示「几分钟前命中过 / 还没命中」这类命中回显（v3.1 砍掉状态灯，因为那需要往系统设置里写状态）
- 改完保存后，退出统计页再进就生效，不用重启设置，也不用重启手机

### 生效范围

| 设置项 | 键 | 取值 |
| --- | --- | --- |
| 生效范围 | `stfix_scope` | `all`（默认）/ `weekday` / `weekend` / `custom` |
| 自定义星期 | `stfix_weekdays` | `1,2,3,4,5`（1=周一 … 7=周日），仅 `custom` 用 |
| 仅指定日期 | `stfix_today` | `2026-10-31`；非空时只有那天生效 |
| 每日上限 | `stfix_cap` | 分钟，0=不限（默认 0） |
| 数字抖动 | `stfix_jitter` | 0–5 分钟，默认 0 |
| 每天加总时长 | `stfix_add_total` | 分钟，0=不加（默认 0） |
| 单独加的 App | `stfix_add_apps` | `包名=分钟`，逗号分隔 |
| 每天加解锁 | `stfix_add_unlock` | 次，0=不加（默认 0） |
| 加成时段 | `stfix_add_windows` | `480-720` 这种；空=按真实活跃分布 |
| 承载 App | `stfix_add_carrier` | 包名；空=自动挑当时用得最多的 |
| 承载方式 | `stfix_add_spread` | `carrier`（默认）/ `weight` / `fake` |

两条规则是「都要满足」的关系：`stfix_today` 设了过去的日期 → 整轮不生效（界面显示「今天不生效」），清空就恢复；`scope` 之外的日子同理不生效，数字显示真实的。

已知边界：周视图、月视图那一串数据里没有「哪一天」的信息（只有全天数的小时桶），所以生效范围只能按今天判断——今天是工作日就整周都按你设的时段和上限处理。日视图不受影响。

### 每日上限怎么扣

裁完时间段后如果当日总时长还超 `stfix_cap`，从最活跃的小时桶开始往下扣（一个扣到 0 再扣下一个），同时把 `appDetail`、`categoryDetail`（含分类里再挂的一层应用）按同一比例缩小并截到上限，所以「总时长 ≥ 排行榜最大项」和「点开分类的总和」不会被拆穿。周 / 月视图按「上限 × 天数」换算成该视图的额度。

### 数字抖动

窗口起止各偏移 `±stfix_jitter` 分钟，种子 = 当天日期 + 第几段窗口 + 起点 / 终点，所以同一天里反复进页面看到的都一样，换一天才会变；窗口不会被抖成「起=止」，也不会因为抖动变成无效段。

### 解锁次数怎么裁

日视图的 `unlocks` 是 24 个小时桶（周 / 月聚合后是 24 的整数倍），按每个小时被窗口覆盖的比例逐个折减，整小时被盖住就归零；首次解锁时间跟着挪到第一个非零小时。

周 / 月视图系统聚合（`UnlockUsageKt.aggregate`）之后 `unlocks` 只剩每天一个数（7 或 30 个元素），拿不到小时分布，所以按一天里被隐藏的分钟占比折算：`占比 = coveredMinutesPerDay / 1440`，每个元素、总数和「上期（lastCycle）」都按 `×(1-占比)` 四舍五入。

举例：窗口 `30-420` 盖住 390/1440 = 27.08%，周视图 316 次解锁折成 230 次，「较上周期」的差值同比例缩小；窗口 `0-1440` 盖满全天则比例是 1，当期、上期、首次解锁时间全部归零。因为一天只有一个数，周 / 月的折算只能按比例，会和真实分布的精确值差个几次——这是数据本身的信息量上限，不是漏裁。

上期（`lastCycle`）也会折减：日视图按当期实际被扣掉的比例 `(before-after)/before` 折，周 / 月视图按同一天的覆盖占比折，这样「当期 vs 上期」的差值同比例缩小，不会出现「当期 9 次、较昨日 -16 次」。

## 已知问题

- 系统升级后可能失效（页面照开，数字变回真的）。模块是按这版系统的内部结构找的入口；失效时 hook 会尽量发一条系统通知（半小时最多一条，需要「设置」有通知权限）
- 周 / 月视图拿不到「哪一天」，生效范围按今天判断（见上）

## 构建

无 Gradle，纯 `javac + d8 + aapt2 + apksigner`：

```
sh build.sh
```

需要 Android SDK（android.jar）、Xposed api-82.jar，以及一个自己的签名密钥（build.sh 里缺失时会自动生成 `eta.jks`）。

`tools/CoreTest.java` 是纯算法用例（跨零点、多段并集、边界、生效范围判定、抖动定种、上限扣减），构建前会先跑一遍。

当前版本 v4.2（versionCode 18）。

## 作者

- 酷安：[@羊羊羊_](https://www.coolapk.com/u/3977579)
- 项目：https://github.com/YizhiXiaoYangyang/screentime-fix

## 许可

[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/legalcode.zh-Hans)（知识共享 署名 4.0）。随便用、随便改、随便发、可商用，保留署名即可（写上作者酷安主页 + 项目地址）。
