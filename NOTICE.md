# NOTICE — 来源、版本与许可

MineAgentAIO 是**多个模组的合并版**：MineAgent 自己的四个客户端模组被合并成一个模组（源码在本仓库，
包名统一为 `com.mineagent.aio.*`），另外 reimplement 了 noautopause 的行为，并把 Baritone 留作**可选的外部模组**。

本文件列出每个部分的来源、版本与许可。代码整体按 **LGPL-3.0-only** 分发（见 [`LICENSE`](LICENSE) /
[`LICENSE.GPL-3.0`](LICENSE.GPL-3.0)）；文档按 **CC BY-NC-SA 4.0** 分发（见
[`LICENSE.CC-BY-NC-SA-4.0`](LICENSE.CC-BY-NC-SA-4.0)）。

## 1. 合并进本仓库的模组（版权归 MineAgent，LGPL-3.0-only）

| 模组 | 版本 | 上游仓库 | 合并后的位置 |
| --- | --- | --- | --- |
| MGHttpdProvider | 1.1.0 | <https://github.com/MineAgent/HttpdProvider> | `com.mineagent.aio.http` + `WindowTitle`（重写为内部类，不再是独立模组） |
| mcctl | 1.6.2 | <https://github.com/MineAgent/mcctl> | `com.mineagent.aio.ctl` |
| AdvancedInfoFetcher | 1.6.3 | <https://github.com/MineAgent/AdvancedInfoFetcher> | `com.mineagent.aio.aif` |
| cmdCraft | 1.3.2 | <https://github.com/MineAgent/cmdCraft> | `com.mineagent.aio.op` |

四个上游仓库同为 LGPL-3.0-only（源码文件头部标有 `SPDX-License-Identifier: LGPL-3.0-only`），
合并后本仓库整体仍以 LGPL-3.0-only 分发，版权声明与许可未改变。

`assets/mineagentaio/lang/*.json` 是从 cmdCraft 的 `assets/craftcmd/lang/*.json` 改名而来（键前缀
`craftcmd.*` → `mineagentaio.*`），文案内容未改；仍然属于上述 LGPL-3.0-only 代码的一部分，只是改由
`com.mineagent.aio.Messages` 直接在模组内读取（因为不依赖 Fabric API 时原版资源管理器看不到模组的 `assets/`）。

## 2. noautopause 1.0.2（行为合并，非打包）

* 上游：<https://github.com/Armandukx/noautopause>（作者 Armandukx）
* 上游许可：上游仓库声明 **MIT**；分发的 `noautopause-1.0.2.jar` 里 `fabric.mod.json` 写的是
  **CC0-1.0**，同时附带一份未填写年份/作者的 MIT 许可模板。两者都是宽松许可，允许使用与再分发。
* 本仓库的处理：**没有打包上游 jar，也没有逐字复制其源码**，而是按其行为在
  `com.mineagent.aio.NoAutoPause` 里重新实现：每次客户端 tick 检查一次，把
  `Minecraft.options.pauseOnLostFocus` 置为 `false`（只做一次），效果与上游一致。
  上游用 Fabric API 的 `ClientTickEvents.END_CLIENT_TICK`；本模组不依赖 Fabric API，改由
  `MinecraftMixin#tick` 调用，因此上游的 `fabric-api` 依赖也一并去掉了。
* 致谢：Armandukx（noautopause）。

## 3. Baritone（可选，未包含）

* 上游：<https://github.com/cabaletta/baritone>（leijurv、Brady 等）
* 许可：**LGPL-3.0**
* 本仓库的处理：**没有打包、没有修改、没有包含其代码**。它保持独立模组，由玩家自行安装；
  本模组只在运行时反射调用它的 API（`baritone.api.*`，见 `com.mineagent.aio.baritone.BaritoneBridge`），
  并在 `fabric.mod.json` 里用 `recommends` 声明。装了 `bt` 可用，没装 `bt` 返回 400。
* 实测版本：**Baritone 1.19.0**（`baritone-api-fabric-1.19.0.jar`，面向 Minecraft 26.2）。

## 4. 未包含：unlockRecipe（服务端模组）

* 上游：<https://github.com/MineAgent/unlockRecipe>（LGPL-3.0-only）
* **本模组不包含它，也不可能包含它**：它是 **Fabric 服务端**模组，做的是「玩家加入世界时用控制台身份
  执行 `/recipe give <玩家> *`」，必须运行在服务端（单人存档里就是集成服务器）。
  客户端模组无法代替它，所以需要「新存档配方书直接是满的」时请单独把它装进服务端。

## 5. 运行环境（不作为代码分发）

* Minecraft 26.2 / Fabric Loader 0.19.5+（本模组**不需要** Fabric API）
* Minecraft 与 Fabric Loader 的版权归 Mojang / Fabric 项目所有，本仓库不分发它们。

## 6. 文档

`README.md`、本文件以及仓库内的说明性文字按 **CC BY-NC-SA 4.0** 授权
（署名—非商业性使用—相同方式共享 4.0 国际），见 [`LICENSE.CC-BY-NC-SA-4.0`](LICENSE.CC-BY-NC-SA-4.0)。
代码（`src/`、`tools/`、`mcctl`、`craftcmd`、`aifetch`、构建文件）仍为 LGPL-3.0-only。
