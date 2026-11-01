# Outlands

1.12.2 的模组加载器。构建已从旧的 ForgeGradle 方案搬进本仓库自足实现：MCP 流程（反编译、访问转换、cleanup、
重命名）、补丁集、loader 源码、二进制补丁、安装器与启动器整合包，全部由本仓库产出。

英文版见 `README.md`。

## 环境要求

* JDK 25（`java -version`）
* 没有别的了：Gradle 由 wrapper 自行下载，反编译/打补丁的工具链从 maven 取

## 构建

```
./gradlew setup      # 反编译 + 打补丁（首次需要，之后改了补丁也需要）
./gradlew build      # 编译 loader、重混淆、生成二进制补丁、打包
```

产物在 `projects/outlands/build/libs/`：

| 文件 | 说明 |
|---|---|
| `outlands-<版本>.jar` | loader（universal）jar |
| `outlands-<版本>-installer.jar` | 安装器/启动器 JSON + universal jar |
| `outlands-<版本>-sources.jar` | loader 源码（userdev 消费者注入的就是它） |
| `outlands-<版本>-userdev.jar` | 供模组开发环境使用的 userdev 产物 |
| `outlands-<版本>-srg.jar` | 重混淆后的 class（userdev 工具链使用） |
| `outlands-<版本>.zip` / `outlands-<版本>-local.zip` | MultiMC/Prism 实例包 |

`-Prelease` 会让启动器整合包指向已发布的 maven 地址，而不是本地 jar。

### 版本号

CalVer：`YYYY.MM.DD`，用 UTC，**不带任何其它信息**——没有 commit 距离、没有 dirty 标记、没有 CI 运行号。
因此同一天构建多次会得到相同的文件名，互相覆盖。

只有**发布**才带序号：当天第一次是 `2026.10.23`，之后是 `2026.10.23-2`、`2026.10.23-3`……后缀只在发布时决定，
本地构建永远没有。想复现某个已发布版本，用 `-PcalverDate=2026.10.23 -PcalverSuffix=-2`。

## 开发运行

```
./gradlew runOutlandsClient
./gradlew runOutlandsServer
```

工作目录都是 `projects/outlands/run/`。客户端通过 Slime Launcher（ForgeDev）获取 Minecraft classpath、assets 与 natives。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `projects/mcpbase` | MCP 层：反编译、访问转换、mcpcleanup、MCP→SRG 重命名、补丁应用 |
| `projects/outlands` | loader 源码（与打好补丁的 Minecraft 一起编译）、重混淆、二进制补丁、打包、userdev、安装器、启动器整合包 |
| `patches/minecraft` | 修改后的 Minecraft 源码，以可 git apply 的补丁形式维护 |
| `templates` | `LoaderVersion.java`，构建时填入当前版本 |
| `buildSrc` | 构建插件与任务（`top.outlands.gradle.*`） |
| `legacy/fg6` | 旧的 ForgeGradle 6 构建脚本，仅作参考 |

给模组开发者的坐标：`top.outlands:outlands:<版本>`（另有 `userdev`、`installer`、`sources`、`srg` 分类器），
来自 `https://maven.outlands.top/releases`。

## 维护补丁

Minecraft 源码通过 `patches/minecraft/**.java.patch` 修改。它们是标准的 unified diff，带 `before/` + `after/`
前缀和完整 hunk 头，`git apply -p2`、`patch -p2` 和 IDE 都能直接读。

* `./gradlew :outlands:checkPatches` 校验所有补丁仍然可用
* `:mcpbase:makePatches` 从 `projects/mcpbase/build/patched` 重新生成补丁集

## 发布（维护者）

> 本地构建会优先用 `mavenLocal()`，CI 不会。凡是从 `top.outlands` fork 取的东西（ForgeDev 插件、mavenizer……）
> 都必须发布到 `maven.outlands.top`，否则会出现“本地能构建、发布构建失败”。想本地复现 CI，可以用 `--init-script`
> 把 `mavenLocal` 从所有仓库里去掉。

发布由 CI（`Release.yml`）构建并上传，**任何情况下都不从本地上传**。`./gradlew release` 只是“按下按钮”：它不会改动
仓库，也不会发布本地构建。

### 一次性准备

在**你自己的** GitHub 账号上创建一个 fine-grained personal access token：

* Repository access：只勾 `Project-Outlands/Outlands`
* Permissions：`Actions: Read and write`（别的不需要）

把它放在仓库之外：

```
echo 'releaseToken=github_pat_...' >> ~/.gradle/gradle.properties
# Windows: %USERPROFILE%\.gradle\gradle.properties
```

也可以临时用 `-PreleaseToken=...` 或环境变量 `OUTLANDS_RELEASE_TOKEN`（环境变量更容易进 shell 历史，推荐用 properties）。

### 命令

```
./gradlew release                                 # 校验 → 算版本 → 派发 CI → 等结果
./gradlew release -PdryRun                        # 只校验并打印将要发布的版本
./gradlew release -PnoWait                        # 派发后立刻返回（打印 run 地址）
./gradlew release -PreleaseVersion=2026.10.23-2   # 补完一次中途失败的发布
./gradlew releaseVersion                          # 只打印当前发布将使用的版本
```

派发前，`release` 必须满足：当前在 `main`、工作区干净、`HEAD` 与 `origin/main` 完全一致。落后和未推送会被分开
报告，且**不会**替你执行 `git pull`。

CI 会再校验同样的条件（提交必须仍是 `HEAD`，版本必须仍是“下一个可用”），然后构建、上传 maven，**最后才打 tag**
并创建 GitHub release。这个顺序保证失败的发布不会消耗版本号。

### CI secrets

只有一个：`OUTLANDS_MAVEN_TOKEN`（maven 发布令牌）。用户名 `fg` 不是秘密，直接写在 workflow 里。

### 发布失败怎么办

| 失败位置 | 留下了什么 | 处理 |
|---|---|---|
| 构建/测试 | 什么都没留，无 tag | 修好后 `./gradlew release`（同一个版本号） |
| maven 上传 | 无 tag；部分上传会在其它步骤之前被识别出来 | 修好原因重跑；若已部分上传，先从 maven 删掉该版本 |
| tag / GitHub release / CHANGELOG | 产物已在 maven，tag 可能已存在 | `./gradlew release -PreleaseVersion=<版本>` |

tag 不会自动回退：它记录的是“哪个提交产出了这个版本”，而删 tag 也删不掉 maven 上的东西。想重跑已有 tag 的发布，
不改 tag，直接用 resume。

### 逃生口

手动推 tag 依然可以触发发布，版本从 tag 名读取：

```
git tag 2026.10.23 && git push origin 2026.10.23
```

这条路径绕过了版本计算，请确认那个号确实没人用过。
