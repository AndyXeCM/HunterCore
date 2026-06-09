# HunterCore

HunterCore 是一个基于 [DivineMC](https://github.com/BX-Team/DivineMC) 的独立 Minecraft 服务器核心。它保留 DivineMC / Purpur / Paper 的插件生态兼容性，同时加入 HunterCore 自己的内置插件层、扩展 API 和服务器管理命令。

这个仓库已经独立于 GitHub fork network。上游同步通过 `upstream` remote、手动检查工作流和分支合并完成，不依赖 GitHub fork 关系。

## 项目定位

- 基于 DivineMC、Purpur 和 Paper 的高性能服务端核心。
- 默认内置一批常用插件，首启前安装到 `plugins/`，减少开服时的重复配置。
- 通过 `plugins/HunterCore/preferences.yml` 统一管理内置插件、运行时模块和命令开关。
- 提供 HunterCore API，方便后续内置模块或外部插件扩展 `/huntercore` 命令。
- 保持独立仓库发布节奏，同时保留从 DivineMC 上游同步的能力。

## 当前内置插件

HunterCore 会在 Paper 扫描插件目录之前准备内置插件。首次启动后会生成：

```text
plugins/HunterCore/preferences.yml
```

这个配置可以关闭整个内置插件安装器、关闭单个插件，或禁止自动替换已变化的内置 jar。关闭已经加载的插件仍需要重启服务器。

当前内置插件：

```text
ViaVersion 5.9.1
ViaBackwards 5.9.1
ViaRewind 4.1.1
BlueMap 5.20
Chunky 1.5.3
Multiverse-Core 5.7.0
LuckPerms 5.5.55
CoreProtect 23.2
HunterTPA builtin
HunterAuth builtin
HunterTools builtin
```

外部内置插件由脚本准备：

```bash
scripts/prepare-bundled-plugins.sh
```

内置自研插件放在 `huntercore-plugins/` 下。

## 命令

```text
/about
/huntercore
/huntercore system
/huntercore plugins
/huntercore preferences
/huntercore preferences bundled <plugin-id> <on|off>
/huntercore preferences module <module> <on|off>
/huntercore preferences command <essentials|management> <command> <on|off>
/huntercore reload
/hc
```

`/about` 会显示 HunterCore 信息。`/huntercore system` 会输出 JVM、系统、CPU、内存、运行时间、在线玩家数量和插件目录信息。

HunterTools 还会内置一组常用管理和生存服工具：

```text
/htps
/hunteradmin modules
/hunteradmin module <module> <on|off>
/hunteradmin command <essentials|management> <command> <on|off>
/hunteradmin memory
/hunteradmin gc
/hunteradmin threads
/hunteradmin optimize
/hunteradmin web status
/hunteradmin web restart
/hunteradmin web users
/hunteradmin web user <name> <admin|player> <password>
/hunteradmin web remove <name>
/heal [player]
/feed [player]
/fly [player] [on|off]
/gm <mode> [player]
/day [world]
/night [world]
/sun [world]
/rain [world]
/thunder [world]
/broadcast <message>
/clearchat
/speed <1-10> [player] [walk|fly]
/spawn [player]
/setspawn
/back
/fakeplayer spawn <name> [world x y z [yaw pitch]]
/fakeplayer remove <name>
/fakeplayer list
/fakeplayer tp <name> [world x y z [yaw pitch]]
/fakeplayer clear
/npc spawn <name> [villager|mannequin] [world x y z [yaw pitch]]
/npc remove <name>
/npc list
/npc tp <name> [world x y z [yaw pitch]]
/npc clear
```

运行时模块：

```text
tps-display
sidebar
essentials
management
fake-players
npcs
web-panel
```

这些模块和命令都可以通过 `preferences.yml` 或 `/hunteradmin`、`/huntercore preferences` 开关。

`/fakeplayer` 使用 Minecraft 的 Mannequin 实体创建轻量假人，适合大厅展示、压测可视目标和基础交互占位。`/npc` 支持 `villager` 和 `mannequin` 两种类型。两者都会写入 `plugins/HunterCore/preferences.yml`，重启后由 HunterTools 重建。

## 网页面板和地图

HunterTools 自带一个轻量网页面板，默认监听：

```text
http://127.0.0.1:8088/
```

如果你要对外开放，在 `plugins/HunterCore/preferences.yml` 中改：

```yaml
modules:
  web-panel:
    enabled: true
    bind-address: 0.0.0.0
    port: 8088
    public-map: true
    map-url: http://%host%:8100/
```

游客可以查看基础状态和 BlueMap 地图链接；登录后的普通用户可以查看更完整的玩家/插件信息，并只能执行 `player-allowed-commands` 白名单中的指令；管理员可以执行控制台指令。首次启动不会写入明文默认密码，需要在控制台创建账号：

```text
/hunteradmin web user admin admin <password>
/hunteradmin web user steve player <password>
```

BlueMap 5.20 会作为内置插件安装，默认网页地图端口由 BlueMap 自己管理，HunterCore 面板会用 `map-url` 嵌入或跳转到地图。BlueMap 首次运行需要你在 `plugins/BlueMap/core.conf` 里阅读并确认 `accept-download`，它会下载 Mojang 客户端资源用于地图渲染。Chunky 1.5.3 也被内置，适合预生成区块、降低真实开服时的探索卡顿。

## 优化

HunterCore 默认开启一组保守的多线程/异步优化开关：

```text
optimizations.bundled-plugin-parallel-install.enabled
optimizations.bundled-plugin-parallel-install.max-workers
optimizations.hunter-tools.async-rendering
optimizations.hunter-tools.async-save
optimizations.hunter-tools.player-cache
optimizations.hunter-tools.render-workers
optimizations.hunter-tools.actor-async-load
optimizations.hunter-tools.actor-batch-save
optimizations.hunter-tools.web-panel-workers
optimizations.cpu.enabled
optimizations.cpu.paper-worker-threads
optimizations.cpu.divine-worker-threads
optimizations.cpu.netty-io-threads
optimizations.cpu.common-pool-parallelism
```

内置插件安装阶段会并行校验/写入不同 jar。HunterTools 的 sidebar 文本渲染、假人/NPC 配置加载、网页面板请求处理、GC 请求和配置保存会移出主线程，最终 Bukkit 状态修改仍回到主线程执行，避免破坏 Bukkit/Paper 线程安全规则。

HunterCore 还会在启动时按 CPU 核心数设置保守的多线程默认值，包括 Paper/DivineMC worker threads、Netty IO threads 和 ForkJoin common pool parallelism。你通过 JVM 参数显式设置过的值不会被覆盖。

## 构建

需要 Java 25。

```bash
GIT_CONFIG_COUNT=1 \
GIT_CONFIG_KEY_0=url.git@github.com:.insteadOf \
GIT_CONFIG_VALUE_0=https://github.com/ \
./gradlew applyAllPatches createPaperclipJar --no-daemon
```

构建产物会生成在：

```text
divinemc-server/build/libs/
```

可直接运行的 paperclip jar 通常类似：

```text
divinemc-server/build/libs/divinemc-paperclip-26.1.2.local-SNAPSHOT.jar
```

GitHub Actions 会在发布时把它重命名为 `HunterCore-<version>-paperclip.jar` 作为 release asset。

## 下载和运行

推荐从 [GitHub Releases](https://github.com/AndyXeCM/HunterCore/releases) 下载 `HunterCore-*-paperclip.jar`。

```bash
java -Xms2G -Xmx4G -jar HunterCore-<version>-paperclip.jar nogui
```

首次启动会生成 EULA 和配置文件。接受 Minecraft EULA 后再次启动即可。

## 发布版本

发布使用 `.github/workflows/release.yml`。

通过 tag 发布：

```bash
git tag v26.1.2-huntercore.2
git push origin v26.1.2-huntercore.2
```

也可以在 GitHub Actions 页面手动运行 `Release HunterCore`，输入版本号，例如：

```text
v26.1.2-huntercore.2
```

工作流会构建 paperclip jar，生成 SHA256 校验文件，并创建或更新 GitHub Release。

## 同步 DivineMC 上游

本地保留只读上游 remote：

```bash
git remote -v
# upstream  git@github.com:BX-Team/DivineMC.git (fetch)
# upstream  DISABLED (push)
```

推荐同步流程：

```bash
git fetch upstream
git checkout main
git checkout -b codex/sync-divinemc-YYYYMMDD
git merge upstream/ver/26.1.2
./gradlew applyAllPatches createPaperclipJar --no-daemon
```

如果 DivineMC 的当前维护分支变化，更新上面的 `upstream/ver/26.1.2` 即可。仓库也提供 `Check DivineMC Upstream` 手动工作流，用来查看上游差异并执行 dry-run merge。

## HunterCore API

API 入口：

```java
org.huntercore.api.HunterCoreProvider.get()
```

插件可以注册未来的 `/huntercore` 子命令扩展：

```java
HunterCoreProvider.get().registerCommandExtension(extension);
```

扩展接口：

```java
org.huntercore.api.HunterCommandExtension
```

## 许可证和致谢

HunterCore 继承 DivineMC / Paper 系列项目的 GPL-3.0 许可证要求。详见 [LICENSE](LICENSE)。

HunterCore 基于 DivineMC 构建，DivineMC 本身包含来自 Purpur、Paper、Pufferfish、Leaves、SparklyPaper 等项目的补丁和优化。感谢这些项目为 Minecraft 服务端生态做出的工作。
