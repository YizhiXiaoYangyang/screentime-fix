# v4.0「我在玩手机啊」实现契约（并行开发的唯一真相源）

反向功能：让「设置 → 屏幕使用时长」显示得**比真实更多**：总量可加、单个 App 可加、解锁次数可加。
与现有「藏」共存，顺序固定：**先裁（窗口 + 每日上限）→ 再注入加成**。

已由探针实测确认（`docs/probe-2026-11-02.md`）：

- `xa.g#b` 返回 `List<DeviceAppUsage>`，**一天一个元素**；日 n=1 / 周 n=7 / 月 n≈30，每个元素 `detail` 都是 24 格。
- `appDetail` 是 `Map<String, AppUsage>`，**key == `AppUsage.getPkgName()`**。
- `AppUsage.detail` 也是 24 格，与同一元素的 `DeviceAppUsage.detail` 同域。
- 分类字段：`categoryId = "usagestats_app_category_miui_<type>"`，`categoryType = "<type>"`；未定义的是
  `usagestats_app_category_miui_undefined` / `undefined`。分类表的 key 也是 `categoryId`。
- 构造函数（反射已在用）：`DeviceAppUsage(long, List, Map, Map, long)`、
  `AppUsage(String pkg, long useTime, String catId, String catType, List detail, long lastCycle)`、
  `CategoryUsage(String catId, String catType, long useTime, List detail, long lastCycle, List<AppUsage> apps)`、
  `UnlockUsage(int unlockTimes, List unlocks, int lastCycle, long firstTime)`。

## 文件分工（谁都不许越界）

| 角色 | 只许改这些文件 |
|---|---|
| A 配置层 | `src/com/eta/lab/screentime/Cfg.java`、`Store.java`、`tools/CoreTest.java` |
| B 注入层 | `src/com/eta/lab/screentime/HookEntry.java` |
| C 界面层 | `src/com/eta/lab/screentime/MainActivity.java`、`Ui.java`、新增 `AddActivity.java`、`AndroidManifest.xml`、`res/` |

`Channel.java`、`ConfigProvider.java` 不动。

## A：Cfg 与 Store 的接口（B、C 都按这个写）

```java
// --- 新键 ---
public static final String KEY_ADD_TOTAL   = "stfix_add_total";    // 每天加的总时长（分钟，0=不加）
public static final String KEY_ADD_APPS    = "stfix_add_apps";     // "pkg=30,pkg2=15"
public static final String KEY_ADD_UNLOCK  = "stfix_add_unlock";   // 每天加的解锁次数（0=不加）
public static final String KEY_ADD_WINDOWS = "stfix_add_windows";  // 加成时段 "480-720"，空=按真实分布
public static final String KEY_ADD_CARRIER = "stfix_add_carrier";  // 承载 App 的包名，空=自动挑
public static final String KEY_ADD_SPREAD  = "stfix_add_spread";   // carrier | weight | fake

public static final String SPREAD_CARRIER = "carrier";
public static final String SPREAD_WEIGHT  = "weight";
public static final String SPREAD_FAKE    = "fake";
public static final String FAKE_PKG       = "com.android.settings";  // fake 模式承载条目用它
```

`Cfg.Snapshot` 追加字段（默认值要能直接 `new` 出来，hook 端不配置时等于不生效）：

```java
public int addTotal = 0;
public java.util.Map<String,Integer> addApps = new java.util.LinkedHashMap<>();
public int addUnlock = 0;
public java.util.List<int[]> addWindows = new java.util.ArrayList<>();
public String addCarrier = "";
public String addSpread = SPREAD_CARRIER;
/** 有没有任何加成配置 */
public boolean hasAdd() { return addTotal > 0 || !addApps.isEmpty() || addUnlock > 0; }
```

`Cfg` 追加的纯函数（全部要有 CoreTest 用例）：

```java
/** "pkg=30, pkg2=-5" → {pkg:30, pkg2:0}；非法项跳过，重复取后者，分钟夹到 0..1440。 */
public static java.util.Map<String,Integer> parseAppMinutes(String raw);

/** 反向序列化，键排序稳定，方便 CoreTest 比较。 */
public static String joinAppMinutes(java.util.Map<String,Integer> m);

/** 按权重把 deltaMs 摊到 n 桶，最大余数法：结果和 == deltaMs（n>0）。权重全 0/null → 均匀。 */
public static long[] distribute(long deltaMs, long[] weights);

/** 同上，摊的是整数次数。 */
public static int[] distributeInt(int count, long[] weights);

/** 摊进指定时段（分钟表示，支持跨零点）；窗外为 0。wins 空 → 交给 distribute 均匀。 */
public static long[] distributeInWindows(long deltaMs, java.util.List<int[]> wins, int n);

/** "480-720,1140-1200" 复用 parse() 的格式即可（addWindows 也用它解析）。 */
```

`fromSource` 追加解析（不改动现有逻辑）：
`addTotal = clamp(parseInt(KEY_ADD_TOTAL,0), 0, 1440)`；`addApps = parseAppMinutes(...)`；
`addUnlock = clamp(parseInt(KEY_ADD_UNLOCK,0), 0, 480)`；`addWindows = parse(KEY_ADD_WINDOWS)`；
`addCarrier = trim(KEY_ADD_CARRIER)`；`addSpread` 不在三选里就回落到 `SPREAD_CARRIER`。

`hasConfigValues` 要把 6 个新键也算进去（否则只设了加成会被当成「没配过」走老值迁移）。

`Store` 追加一个**独立方法**，不动现有 `save(...)`：

```java
/** 只写加成相关键，commit 同步落盘。 */
static void saveAdd(android.content.Context c, int addTotal, java.util.Map<String,Integer> addApps,
                    int addUnlock, String addWindows, String addCarrier, String addSpread);
```

## B：HookEntry 注入

`loadConfig()` 里把 `snap.addTotal/addApps/addUnlock/addWindows/addCarrier/addSpread` 落到新静态字段，
并且早退条件改成「窗口、隐藏 App、上限、`hasAdd()` 全空才不动」。

`trimDeviceAppUsage(...)`：**在每日上限那一步之后**调用新的 `applyAdd(...)`，把
`(total, detail, apps, cats)` 换成一版加了量的；`cap` 不得再削加成。

要满足的语义（都会被实测检查）：

1. 增量集合 = `addApps` 的每项（分钟/天）+ `addTotal` 按 `addSpread` 处理的结果：
   - `carrier`：`addTotal` 全给 `addCarrier`；`addCarrier` 为空就取当前 `appDetail` 里 `useTime` 最大的一条。
   - `weight`：按各 App 现有 `useTime` 比例摊 `addTotal`（最大余数法，总和精确）。`appDetail` 空就用 `FAKE_PKG`。
   - `fake`：`addTotal` 全给 `FAKE_PKG`，没有这条就造出来。
2. 每个目标 pkg 的增量 X（毫秒）要同时落到四处，保证任何页面都自洽：
   - `appDetail[pkg].useTime += X`；`appDetail[pkg].detail` 按分布加；`lastCycle += X`。
   - `appDetail` 里没有该 pkg → 造 `AppUsage(pkg, X, catId, catType, detail, X)`，`put(pkg, ...)`。
     `catId/catType` 先在该元素的 `appDetail`/`categoryDetail` 里找同名包；找不到用
     `usagestats_app_category_miui_undefined` / `undefined`。
   - `categoryDetail`：找 `categoryId` 相同的分类，`useTime += X`、`detail` 同分布加、
     `lastCycle += X`，内层 `List<AppUsage>` 里更新或补上这条 App。没有这个分类 →
     新建 `CategoryUsage(catId, catType, X, detail, X, [app])` 并按 catId 放进表里。
   - `DeviceAppUsage.detail` 同分布加；`total += X`。
3. 分布算法：权重优先用**该 App 现有的 detail**（全 0 则跳过）；为空则用
   `addWindows`（`Cfg.distributeInWindows`）；再空则用 `Cfg.distribute` 均匀。
   一律走 `Cfg` 的纯函数，保证「桶之和 == 增量」。
4. 幂等：所有计算基于本次查询拿到的真实对象，构造新对象返回，**不改数据源**。同一个元素被处理两次
   不会叠加（因为每次都是重新从原始值算）。
5. 解锁（`trimUnlockUsage`）：`addUnlock > 0` 时——
   - `unlocks` 是 24 的整数倍（小时桶，日视图）→ 用 `Cfg.distributeInt(addUnlock, 真实 unlocks 权重)` 逐桶加；
   - 否则（周/月，每天一个数）→ 每个元素 `+addUnlock`；
   - `unlockTimes += 实际加的总数`；`lastCycle += 实际加的总数`；`firstTime` 不动。
6. 异常一律吞掉：加成的任何失败都不能影响原有「藏」的逻辑（单元素 try/catch，失败就返回原对象）。

## C：界面

1. `MainActivity.buildUi()` 末尾（或合适位置）加一张卡片：标题 **「我在玩手机啊」**，副标题
   「反向：把时长加上去」，点开 `AddActivity`。
2. 新增 `AddActivity`（`extends Activity`，无第三方库，风格照 `MainActivity`/`Ui`）：
   - 每天加总时长：分钟输入（默认 0），0 = 不加
   - 承载方式三选：`carrier`（指定 App）/ `weight`（按现有使用比例摊）/ `fake`（造「设置」条目）
     —— 选 `carrier` 时才显示「承载 App」行，点击复用 `MainActivity.showAppPicker()` 那套
     （`PackageManager` 列表 + 搜索框），选完显示应用名
   - 「单独加的 App」列表：每行 `应用名  +N 分钟  [删除]`，底部「＋ 添加应用」→ 选 App → 输入分钟
   - 每天加解锁次数：整数输入（默认 0）
   - 加成时段：可选，空 = 按真实活跃分布；填了就走 `Cfg.parse` 的 `"480-720"` 格式
   - 「保存」→ `Store.saveAdd(...)` → toast「已保存，退出统计页再进就生效」→ finish
3. `AndroidManifest.xml` 注册 `AddActivity`（`android:exported="false"`，`android:theme` 跟 MainActivity 一致）。
4. 只能调 A 定义的键与 `Store.saveAdd(...)`；不许改 `Cfg.java` / `Store.java` / `HookEntry.java`。

## 自检（各自在提交前做）

- `javac -encoding UTF-8 -d /tmp/<自己名字> $(find src -name '*.java' | grep -v HookEntry)` 之外的组合自己看着办；
  **不要跑 `build.sh`**（并发构建会互相踩 `build/` 目录），集成构建由指挥官统一跑。
- 纯算法（A）必须让 `java -cp /tmp/coretest CoreTest` 通过。
- 不要改 `README.md`、不要动 `docs/`。
