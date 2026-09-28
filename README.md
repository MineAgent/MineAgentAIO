# MineAgentAIO — Minecraft 客户端全能模组（Fabric / Minecraft 26.2）

把 MineAgent 的一整套客户端模组**合并成一个 jar**：在 `127.0.0.1:3420` 上起一个 HTTP 服务，
把「操作」「状态」「合成」变成三组接口，LLM（或任何脚本）只要会发 HTTP 请求，就能从空手玩到击败末影龙。

```
GET  :3420/               当前可用的 endpoint 列表（服务自己提供）
GET  :3420/ctl/           控制：使用说明        POST :3420/ctl/           执行命令
GET  :3420/ctl/prtsc      当前帧 PNG            GET  :3420/ctl/mouse      当前光标位置
GET  :3420/aif/           状态：使用说明        GET  :3420/aif/info       坐标/朝向/生命/饱食/效果
GET  :3420/aif/inventory  背包/副手/盔甲/熔炉/箱子
GET  :3420/aif/world      维度/时间/天气        GET  :3420/aif/msg        上次读取后的聊天回显
GET  :3420/aif/sound      上次读取后的声音      GET  :3420/aif/keysnd     上次读取后的重要声音
GET  :3420/op/            容器：使用说明        POST :3420/op/            合成/背包/熔炉/箱子/转向
```

```bash
curl http://127.0.0.1:3420/                          # 我有哪些接口
curl http://127.0.0.1:3420/aif/info                  # 我在哪、面朝哪、多少血
curl -X POST --data-binary 'W 500'  http://127.0.0.1:3420/ctl     # 往前走半秒
curl -X POST --data-binary 'bt mine iron_ore' http://127.0.0.1:3420/ctl   # Baritone 去挖铁矿（装了 Baritone 才有效）
curl -X POST --data-binary 'craft stick 4'   http://127.0.0.1:3420/op    # 用背包里的材料合成 4 根木棍
curl -X POST --data-binary 'look yaw 90'     http://127.0.0.1:3420/op    # 转视角到 yaw=90
curl -o shot.png http://127.0.0.1:3420/ctl/prtsc                     # 截一张当前画面
```

仓库里另有三个命令行封装脚本（内部就是 curl）：[`mcctl`](mcctl)、[`craftcmd`](craftcmd)、[`aifetch`](aifetch)。

## 包含了哪些模组

**本模组 = 下面这些模组的代码合并（同一份 jar、同一个模组 id `mineagentaio`）+ 一个可选的 Baritone。**
版本号是合并时的上游版本，合并后的代码都在本仓库里（包名统一改成 `com.mineagent.aio.*`）。

| 上游模组 | 版本 | 合并到 | 说明 |
| --- | --- | --- | --- |
| [MGHttpdProvider](https://github.com/MineAgent/HttpdProvider) | 1.0 | `com.mineagent.aio.http` | 原来是一个**独立的 lib 模组**（别的模组靠 `compileOnly` 依赖它注册前缀）。合并后不再需要它：HTTP 服务、前缀路由、`GET /` 索引、关游戏时的退出处理都变成了本模组内部的类（`Httpd` / `Http` / `PathHandler` / `ClientExitWatcher`），接口语义不变（同样是 `/ctl`、`/aif`、`/op` 三个前缀 + `GET /` 索引），但少了一个 jar、一次 `depends`、以及入口点顺序问题 |
| [mcctl](https://github.com/MineAgent/mcctl) | 1.6.2 | `com.mineagent.aio.ctl` | `/ctl`：按键 / 鼠标 / 视角 / 滚轮 / 截图 / 光标位置 / 聊天 / `type` 打字 / Baritone |
| [AdvancedInfoFetcher](https://github.com/MineAgent/AdvancedInfoFetcher) | 1.6.3 | `com.mineagent.aio.aif` | `/aif`：坐标 / 朝向 / 生命 / 饱食 / 状态效果 / 背包 / 容器 / 维度 / 时间 / 天气 / 聊天回显 / 声音回显 |
| [cmdCraft](https://github.com/MineAgent/cmdCraft) | 1.3.2 | `com.mineagent.aio.op` | `/op`：`craft` 合成 / `inventory` 换快捷栏 / `furnace` 熔炉 / `chest` 箱子 / `look` 转视角 |
| [noautopause](https://github.com/Armandukx/noautopause) | 1.0.2 | `com.mineagent.aio.NoAutoPause` | 窗口失去焦点时不暂停游戏（否则后台跑 HTTP 时游戏就冻住了）。上游用 Fabric API 的 tick 事件，这里不依赖 Fabric API，改成从 `MinecraftMixin` 的客户端 tick 调用，行为完全一致（只关掉 `pauseOnLostFocus`，单人存档里按 ESC 打开菜单仍然照常暂停） |

**没有包含的模组：**

| 上游模组 | 为什么不在里面 |
| --- | --- |
| [**unlockRecipe**](https://github.com/MineAgent/unlockRecipe) | **它是服务端模组**（Fabric server side）。它做的是「谁加入世界就替谁执行 `/recipe give <玩家> *`」，让新存档的配方书一进来就是满的——这件事必须在**服务端**做，客户端做不到，所以**本模组不含它，也不可能含它**。要用 cmdCraft 的 `craft`，就把它单独装进服务端（单人存档就是 `mods/`：集成服务器会加载它），或者按原版节奏自己解锁配方 |
| [Baritone](https://github.com/cabaletta/baritone) | **可选、单独安装**，不打包进本模组（见下一节）。装了它 `bt` 就能用；没装 `bt` 会返回 400 报错 |

### Baritone：可选，不内置

Baritone（LGPL-3.0，实测 **1.19.0**，即 `baritone-api-fabric-1.19.0.jar`）**保持独立模组**，本模组不打包它：

* **装了 Baritone**：`POST /ctl` 的 `bt <命令>` / `#<命令>` 照常执行——直接调用 Baritone 的 API
  （`BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(...)`，等价于聊天框输入 `#<命令>`，但不发聊天包）。
* **没装 Baritone**：含 `bt`/`#` 的请求**整个返回 400**（`bt failed: Baritone is not installed (mod id 'baritone')`），
  **不会**退化成一条聊天消息，请求里其它命令也不会执行——调用方一眼就能看出问题。
  其余接口（按键、截图、`/aif`、`/op`）完全不受影响。
* 因为 Baritone 是可选的，它的类型在 `BaritoneBridge` 里**反射**调用（`fabric.mod.json` 用 `recommends` 声明），
  所以没装、装旧版本、API 变了都只是 `bt` 报错，不会让模组加载失败。

## 安装

1. Minecraft **26.2** + Fabric Loader **0.19.5+**（不需要 Fabric API）。
2. 把 `mineagentaio-1.0.0.jar` 放进 `.minecraft/mods/`。
3. 要用 `bt` 再放一个 [Baritone](https://github.com/cabaletta/baritone)（1.19.0 实测）。
4. 服务只绑定 `127.0.0.1:3420`，启动后 `curl http://127.0.0.1:3420/` 就能看到接口列表。

## 各部分做什么

* **`/ctl` = 身体（来自 mcctl）**：把游戏变成可编程接口。输入走原版管线（`KeyMapping` / `Screen` 事件），
  视角旋转照常同步给服务器，不抢占真实键鼠；`bt` 走 Baritone API；`GET /ctl/prtsc` 用和 F2 同一套取帧逻辑
  直接返回 PNG（不写 `screenshots/`）；`GET /ctl/mouse` + `mouse goto` 给 GUI 点击兜底。
* **`/aif` = 眼睛 + 耳朵（来自 AdvancedInfoFetcher）**：坐标/朝向/血量/饱食/效果、背包/副手/盔甲/熔炉/箱子、
  维度/时间/天气全部是纯文本；`/msg` 把聊天栏（指令输出、Baritone 输出、报错）变成增量文本；
  `/sound` / `/keysnd` 把播放过的声音 ID 变成增量文本，"刚才发生了什么"不用截图就能判断。
* **`/op` = 手（来自 cmdCraft）**：GUI 对 LLM 不友好，所以把合成、冶炼、开箱子这些高频动作变成一条命令，
  直接发 `ServerboundContainerClickPacket`，和玩家亲手点格子完全等价（服务端照常校验，不需要服务端模组）；
  `look` 把「转视角」从鼠标像素换算变成精确的角度命令。
* **noautopause = 后台可跑**：窗口失去焦点时游戏不停，HTTP 注入的按键才会真的生效。

## 与合并前相比，接口有什么变化

**没有变化**——三个前缀、每个 endpoint 的路径/方法/状态码/正文格式都与合并前一致（同一个 3420 端口），
`GET /` 的索引格式也一致。原来的模组各自注册自己；现在一个入口点按顺序挂载 `/ctl`、`/aif`、`/op`。

变的只有这些：

* 不再需要 `httpdprovider-1.0.jar`（HTTP 服务是本模组内部的一部分）。
* `/op` 的报错文案从 `craftcmd.*` 翻译键改成了 `mineagentaio.*`（纯文本内容不变）。
* 日志：所有部分都写在 `mineagentaio` 这一个 logger 下（`/op` 的行前缀是 `[mineagentaio:op]`）。
* `/op` 的文案**不再依赖 Fabric API**：模组自己的 `Messages` 直接读 jar 里的
  `assets/mineagentaio/lang/*.json`（按游戏语言，回退 `en_us`），所以装不装 Fabric API 都是中文/英文正常显示。
* `bt` 的可用性判断从「Baritone 反射调用」原样保留（见上文）。

## 构建

需要 JDK 25（Minecraft 26.2 要求）。26.1 起官方代码不再混淆，所以 Loom 不需要任何 mappings 配置。

```bash
./gradlew build          # 产物: build/libs/mineagentaio-1.0.0.jar
```

### 不依赖游戏验证 HTTP 层

`tools/` 下有两个脱离游戏的小工具（只编译模组里不碰 Minecraft 的类）：

```bash
mkdir -p build/verify
javac --release 25 -encoding UTF-8 -d build/verify \
  src/main/java/com/mineagent/aio/http/*.java \
  src/main/java/com/mineagent/aio/ctl/{Action,CommandException,CommandParser,CommandRunner,ControlEndpoint,Help,InputExecutor,Keys}.java \
  tools/VerifyCtl.java
java -cp build/verify VerifyCtl     # 43 个解析用例 + /ctl 传输层用例，然后停在 3420 供 curl

javac --release 25 -encoding UTF-8 -d build/verify \
  src/main/java/com/mineagent/aio/http/*.java \
  src/main/java/com/mineagent/aio/aif/{InfoEndpoint,InfoProvider,Help}.java tools/VerifyAif.java
java -cp build/verify VerifyAif     # 用假 provider 挂起 /aif，停在 3420 供 curl
```

## 实测（Minecraft 26.2 + Fabric Loader 0.19.5，Linux，窗口 854×480）

用 `~/Documents/spMC/TestSave.sh`（`--quickPlaySingleplayer test`）启动，`mods/` 里只放本模组
（**没有 Fabric API**）跑通了下面这些；`bt` 分别在**装 Baritone 1.19.0** 和**临时拿掉 Baritone** 两种情况下验证：

| 测试 | 结果 |
| --- | --- |
| 启动 | 日志 `mounted /ctl`、`mounted /aif`、`mounted /op`、`MineAgentAIO listening on http://127.0.0.1:3420` |
| `GET /` | 200，列出三组 endpoint |
| `GET /ctl/` `GET /aif/` `GET /op/` | 200，三份中文说明书 |
| `GET /aif/info` | 坐标/朝向/生命/饱食与游戏内一致 |
| `GET /aif/inventory` | 背包聚合正确（`minecraft:oak_planks 208`、`minecraft:stick 8` …） |
| `GET /aif/world` | `维度：minecraft:overworld`、`时间：12875`、`天气：clear` |
| `GET /ctl/prtsc` | 200 `image/png`，854×480，281–407 KB，画面就是存档里的丛林场景 |
| `GET /ctl/mouse` | `光标：427.0 240.0`、`抓取：是`、`界面：无` |
| `POST /ctl 'W 300'` | 200 JSON |
| `POST /ctl 'chat MineAgentAIO test ok'` | 聊天栏出现 `<DSH> MineAgentAIO test ok`，`GET /aif/msg` 读得到 |
| `POST /ctl 'type hello'`（没开聊天框） | 400 `type failed: no focused text box ...` |
| `GET /aif/msg` | 增量：读完再读为空；聊天、Baritone 输出、报错都在里面 |
| `GET /aif/sound` / `keysnd` | `minecraft:music.overworld.jungle 0.40 1.00`；`keysnd` 过滤掉音乐后为空 |
| `POST /ctl 'bt proc'`（装了 Baritone） | 200，`/aif/msg` 出现 `[Baritone] No process in control` |
| `POST /ctl 'bt goal ~ ~ ~5'` | 200，`[Baritone] Goal: GoalBlock{x=-234,y=104,z=107}` |
| `POST /ctl 'bt stop'` | 200，`[Baritone] ok canceled` |
| `POST /ctl 'bt help'`（**拿掉 Baritone**） | **400** `bt failed: Baritone is not installed (mod id 'baritone')`，没有发出任何聊天消息 |
| `POST /ctl 'W 100'`（没装 Baritone） | 200，普通输入不受影响 |
| `POST /op 'look yaw 90'` | 200 `已转向 yaw：90.0（原 -90.3）`，`/aif/info` 的 `yaw` 变成 90.0 |
| `POST /op 'craft stick 4'` | 200 `已合成 木棍 ×4`；`/aif/inventory` 里木板 208 → 206、木棍 8 → 12（合成任务按 tick 推进，请求等它结束才返回） |
| `POST /op 'inventory minecraft:stick 3'` | 200 `已对调：快捷栏第 3 格 ← 木棍 ×12（换出 橡木木板 ×14）`，截图里快捷栏第 3 格确实是木棍 |
| `POST /op 'craft'`（缺参数） | 400，附带出错位置和插入符 `^`，文案是中文（不依赖 Fabric API） |
| noautopause | 日志 `noautopause: pauseOnLostFocus=false (the client keeps running while unfocused)` |
| 正常退出（关窗口） | 日志 `client exited, stopping the HTTP server` → `exiting the JVM so the post-main shutdown watchdog cannot fire`；**进程退出码 0，`crash-reports/` 不新增文件**（没有这一层的话 15 秒后必现 `Client shutdown from post-main`） |
| 不依赖游戏 | `VerifyCtl`：43 个解析用例全过 + `/ctl` 传输层（含 `bt` 可用/不可用两条路径）全过；`VerifyAif`：`/aif` 传输层可 curl |

## 已知限制

* `type` / `typeEnter` 只支持**聚焦的 `EditBox`**（聊天框可以；铁砧命名、告示牌、书与笔不支持），
  没有可输入的文本框时返回 400。
* `F3` 单独按可以切换调试信息，`F3+X` 组合键（如 `F3+G`）不生效。
* 滚轮（`mouse scroll`）与 `mouse goto` 各自依赖一处 `MouseHandler` 的反射（`onScroll`、`xpos/ypos`），
  将来版本改名后这两个功能可能静默失效，其余功能不受影响。
* `look` 只改客户端旋转，下一个 tick 由客户端同步给服务器（和鼠标转视角同一条路径）。
* 只有**打开容器界面时**客户端才知道容器内容，所以 `furnace` / `chest` 必须先右键打开界面。
* 平台：Linux 上的 Minecraft 永远跑在 X11/Xwayland 下（26.2 在 GLX 里写死 GLFW 平台为 X11），
  所以 `/ctl/mouse`、`mouse goto` 实际只有这一条路径；Windows/macOS 未实机验证。
* 服务只绑定 `127.0.0.1`，没有鉴权（本机任何时候都能控制游戏）。请求体上限：`/ctl` 64KB，`/op` 16KB。

## 目录

```
src/main/java/com/mineagent/aio/
  MineAgentAIOMod.java      唯一的客户端入口点：挂载 /ctl /aif /op，起服务，装退出处理
  MineAgentAIO.java         模组 id / 名称 / 共享 logger / 版本
  Messages.java             模组自己的文案表（读 jar 内的 lang json，不需要 Fabric API）
  NoAutoPause.java          失去焦点不暂停（合并自 noautopause）
  ClientExitWatcher.java    客户端退出后停服务并结束 JVM（不写崩溃报告）
  http/                     HTTP 服务：Httpd（服务/前缀路由/GET / 索引）、Http（响应与请求体工具）、PathHandler
  ctl/                      来自 mcctl：命令解析、单线程执行器、真实输入注入、截图、光标
  aif/                      来自 AdvancedInfoFetcher：只读快照 + 聊天/声音增量队列（含两个 mixin）
  op/                       来自 cmdCraft：Brigadier 命令树、合成计划/任务、容器点击
  baritone/BaritoneBridge.java  唯一调用 Baritone 的地方（反射，Baritone 是可选的独立模组）
  mixin/MinecraftMixin.java 每个客户端 tick：推进合成任务 + noautopause
  mixin/ChatComponentMixin.java   聊天栏 → /aif/msg
  mixin/SoundEngineMixin.java     声音引擎 → /aif/sound
tools/VerifyCtl.java        脱离游戏验证解析层 + /ctl 传输层
tools/VerifyAif.java        脱离游戏验证 /aif 传输层
mcctl craftcmd aifetch      命令行封装脚本（curl）
```

## 许可证

本仓库分两部分授权：

* **代码**（`src/`、`tools/`、脚本、构建文件）：**LGPL-3.0-only**，完整文本见 [`LICENSE`](LICENSE)，
  其中引用的 GPL-3.0 见 [`LICENSE.GPL-3.0`](LICENSE.GPL-3.0)；源码文件头部标有
  `SPDX-License-Identifier: LGPL-3.0-only`。
* **文档**（`README.md`、`NOTICE.md` 及仓库内的说明性文字）：**CC BY-NC-SA 4.0**
  （署名—非商业性使用—相同方式共享 4.0 国际），完整文本见 [`LICENSE.CC-BY-NC-SA-4.0`](LICENSE.CC-BY-NC-SA-4.0)：
  可以自由复制、分发、改编，但必须署名、不得用于商业用途、且衍生作品需以相同许可分发。

第三方组件的来源与许可见 [`NOTICE.md`](NOTICE.md)（合并进来的上游模组、noautopause，
以及需要单独安装的 Baritone）。
