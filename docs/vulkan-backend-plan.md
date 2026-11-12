# Vulkan 后端：已知设计、已知特性、采纳的范式

> 状态：盘点稿（v29，**修复眼空间多光源漫反射与法线变换**；彻底解决 2D 物品暗化、村民与实体暗沉黑脸、箱子等内置渲染方块法线反转问题）。
> 分支 `vulkan-backend`，起点 `0b36b118`。
> 前置文档：`docs/render-takeover-interfaces.md`（接缝设计，960 行）。
> 范式依据：https://www.howtovulkan.com/ （Sascha Willems，更新于 2026-10-30）。
> 规范依据：Vulkan spec Appendix D（1.4 提升清单）。

---

## 0. 一句话概括现状

接缝（seam）已经在 `main` 上，**后端一个都没有**。
`RenderBackends` 里唯一条目 `vanilla-gl` 是「不做替换」的占位符，不是实现。
起点是：**去填一个已经画好边界的空位**，而不是去改渲染器。

---

## 1. 已落地的接缝（`main` 上可查）

| 平面 | 类 | 位置 |
|---|---|---|
| 后端裁决 | `RenderBackend` / `RenderBackends` / `RenderBackendSelection` | `client/render/` |
| 命令转发 | `ShimGl` / `ShimRegistry` / `ShimRewriteRules` / `RetargetEntry` / `ShimEnvironment` | `client/render/shim/`, `lwjgl/shim/` |
| 只读状态 | `GlStateView` / `MatrixView` / `ChunkView` / `RenderState` | `client/render/state/` |
| 生命周期 | `ChunkLifecycle` / `ChunkLifecycleListener` | `client/event/` |
| 附件 | `Attachments` / `AttachmentKey` | `common/attachment/` |

`RenderBackend` 的完整接口面：

```java
String id();
long   sdlWindowFlags();                  // 建窗前唯一要回答的问题
default boolean isOpenGl() { false; }     // 契约,不是提示
default void init(long window) {}         // 建窗后调用一次
default void close() {}                   // 关闭 / 选择失败时
default @Nullable Object nativeContext(); // 故意不类型化
```

`RenderBackendSelection.select()` 解析顺序（已实现）：

```
1. -Doutlands.render.backend 指向已知后端
2. 否则：priority 最高且 probe 可用的候选
3. 否则：vanilla-gl
```

第 1 条**不绕过 probe**：强制指定的后端仍要证明自己能拥有帧，否则回落。
`Display.java` 只有三个触点（`:410` `isOpenGl()`、`:518/:528` `sdlWindowFlags()`）。

`ShimGl` 是**骨架**：84 行，只有 `glEnable` / `glDisable`。默认实现 `REAL_GL` 直通真实 GL。

验证：466 测试全绿；分发点 `2.162 ns/call` vs 直调 `1.657 ns/call`。

---

## 2. 契约约束（不可协商）

1. **选择在窗口存在之前，之后永不变更。** `SDL_WINDOW_OPENGL` 与 `SDL_WINDOW_VULKAN` 在 `SDL_CreateWindow` 时互斥。
2. **回退是整体回退。** Vulkan 窗口里没有 GL 上下文，不存在「这个阶段先用 GL 顶一下」。
   ⇒ 不能覆盖全部所属阶段的后端，就不该被选中。
3. **`isOpenGl()` 是契约。** 它决定 `applyGlAttributes(...)` 与 `SDL_GL_CreateContext` 是否运行；
   答 `true` 必须接受 `ContextAttribs` 通道。
4. **`nativeContext()` 故意不类型化。**
5. **后端注册式，不设 `VULKAN` 常量。** 未注册的 `vulkan` 不可达。
6. **不静默降级。** `no-op` 只允许在构造上不可达时；`route` 转垫片；否则显式 `fail`。
7. **垫片只承诺 GL 2.1 子集**（含 vanilla 自用的 ARB FBO 路径）。
   任何现有渲染模组明确不支持，且要**显式拒绝**。完整契约见 §8.4。

---

## 3. 已设计但未实现的平面

### 3.1 帧阶段钩子（Vulkan 后端的核心负担）

设计在 `render-takeover-interfaces.md` §5，代码未落地。这 8 个 `phaseId`
就是 Vulkan 后端必须**全部**承担的清单，一个都不能留给 vanilla：

```
terrain.setup    terrain.layer   terrain.mark_dirty   terrain.reload
terrain.query    terrain.sky     entities             block_entities
```

```java
interface PhaseHook    { String phaseId(); default int priority(); void run(PhaseContext ctx); }
interface PhaseContext { GlStateView glState(); MatrixView matrices(); ChunkView chunks();
                         CameraView camera(); FrameClock clock();
                         Original original(); @Nullable Object nativeContext(); }
interface Original     { void call(); boolean available(); }
```

`original()` 是核心语义：vanilla 方法**不被覆盖**，只是「有钩子时跳过 / 调 `original()` 时执行」。
这样别的模组锚在这个方法里的注入点天然可达。

### 3.2 其余

`BiomeTintProvider`（§8.3）未实现；RETARGET 规则集的运行期 SPI 只有设计。

### 3.3 渲染图：阶段钩子的泛化 ← 本轮新增设计

#### 为什么阶段钩子不够

§3.1 那 8 个 `phaseId` 是「**接住** vanilla 已有的 8 个入口」，是一个**闭合集合**。
新式模组要的是「**加自己的 pass**」，那是一个**开放集合**。两者不是一个东西：

| | 阶段钩子 | 渲染图 |
|---|---|---|
| 解决的问题 | 替换已有阶段的实现 | 新增、重排、插入 pass |
| 集合性质 | 闭合（loader 掌握 `REQUIRED`） | 开放（模组可增删） |
| 失败模式 | 缺 provider → 启动时报错 | 环、缺依赖、顺序歧义 |

**结论：渲染图不是阶段钩子的替代，是它的泛化。**
而 `PhaseRegistry` 应该就是图的节点注册表——**不建两套**。

#### Bevy 的做法（只取骨架）

| Bevy 的 | 说明 | 我们借不借 |
|---|---|---|
| 节点 = label + `run(world, ctx)` | 单位是 **pass**，不是 draw | ✅ 借 |
| 边 = 顺序声明（`add_render_graph_edges`） | 不靠索引 | ✅ 借 |
| 每帧拓扑排序后执行 | 简单、可校验 | ✅ 借 |
| 子图（`Core2d` / `Core3d` / `CorePipeline`） | 嵌套 | ✅ 借 |
| `Extract → Prepare → Queue → Render` 的 schedule 拆分 | 让 CPU/GPU 并行 | ⚠️ **只借思想** |
| ECS / `RenderLabel` derive | Bevy 的世界模型 | ❌ 不借 |

`Extract → Prepare → Queue → Render` 只借思想的原因：vanilla 没有这个拆分，
`setupTerrain` 与绘制是绞在一起的。强行拆成四个 schedule 是**大重构**，
而收益（CPU/GPU 并行）在 1.12.2 的帧预算里未必能兑现。先不拆。

#### 采用渲染图的最强理由：屏障与布局自动化

这不是「架构好看」，是它直接解决了 §4.5 标为「最难的部分」：

```
节点声明：读/写哪些资源，读还是写
图推导出：image layout 迁移 + pipeline barrier（stage/access mask）
```

§4.3 说布局塔缩成 3 条固定迁移，§4.5 说同步是最难的。**两者其实是同一件事的两面**：
如果资源访问由节点声明，屏障就不是手写的，是推导出来的。

这也给模组提供了一个**安全的插入面**：模组只声明「我读写什么」，
不写 barrier。模组写 barrier 几乎必然写错，而且错在别的 GPU 上才显形（§4.9）。

#### 硬约束：粒度是 pass，不是 draw

渲染图**不能出现在每次 GL 调用的路径上**。vanilla 一帧的绘制调用是万量级，
拓扑排序 + 动态分发每 draw 一次就是灾难。

⇒ 图的节点数应该是**几十**，不是几万。地形的一整层绘制是**一个节点**，
不是一个 quad 一个节点。

#### 模组插入/编辑的机制

**锚点必须声明式，不能索引式**：

```java
// ✅ 允许
graph.insertBefore(TERRAIN_LAYER, myNode);
graph.insertAfter(TERRAIN_LAYER, myNode);
graph.replace(TERRAIN_LAYER, myNode);
graph.remove(MY_OLD_NODE);

// ❌ 不允许：后面的模组插入会错位
graph.insertAt(5, myNode);
```

**四条必须写进契约的规则**：

1. **同锚点的多插入需要确定性 tiebreak。** 两个模组都 `insertAfter(TERRAIN_LAYER)`，
   顺序不能依赖注册先后（模组加载顺序在 1.12.2 里不完全可控）。
   用显式 `priority()`，相同优先级时按 modId 字典序——**可重现比公平重要**。
2. **`replace` 后，锚在旧 label 上的节点应锚到新节点。** 否则一个模组替换了
   `terrain.layer`，另一个模组声明的 `insertAfter(terrain.layer)` 就悬空了。
   这个重定向必须文档化，因为它是有歧义的行为（也可以选「报错」）。
3. **环检测在冻结时做，不在运行时做。** 与 §2-5 的「后端表冻结」同一哲学：
   能静态发现的问题不该拖到帧循环里。
4. **必需节点完整性校验。** 这就是 §3.1 原本的 `PhaseRegistry.REQUIRED` 检查：
   选定的后端必须能回答「这 8 个阶段谁负责」。渲染图不取消这个检查，而是把它
   变成「图里必须存在这 8 个 label」。

#### 与垫片的交叉点 ← 最容易出问题的地方

老模组通过 `ShimGl` 发出的绘制，会落在「**当前正在执行的节点**」的命令缓冲与
动态渲染实例里。这带来两个具体后果：

1. **`ShimGl` 必须知道当前节点上下文。** 否则它无处记录命令。
   这是个新的隐式契约：图在运行时维护一个 current-node 栈。
2. **老模组在节点内切渲染目标（`glBindFramebuffer`）时，垫片必须结束当前
   dynamic rendering 实例再开新的。** Vulkan 不允许在渲染实例内改附件。
   而 GL 允许——所以这是垫片里一个真实的、无法回避的语义鸿沟。
   它不属于任何单个阶段，属于「垫片 × 渲染图」的接缝。

#### 分层原则（不可越过的红线）

```
老模组 → ShimGl（窄接缝，命令级，保证不崩）
新模组 → 渲染图（宽接缝，pass 级，保证可表达）
```

**宽接缝不能成为绕过窄接缝的后门。** 具体说：图节点拿不到「任意 GL 调用直通」
的能力；它拿到的是一组已声明资源的上下文。否则一个模组就能破坏
§2-6「不得静默降级」的前提。

命名空间：`net.minecraftforge.client.render.graph`（与 §1 其余接缝同级，模组编译期可见）。

---

## 4. 采纳的范式（howtovulkan 2026）

这一节是本轮研读的产出。**它改变了我上一版的判断**，所以逐条记下来源。

### 4.1 基线是 1.4，不是 1.3 ← 二次修正

本项已经翻转两次，说明过程，因为这次的依据不同：

- **v1**：倾向 1.4（直觉「更新更好」，没有论证）
- **v2**：改成 1.3（范式文档用 1.3；且我误以为 1.4 只多一个 `maintenance5`）
- **v3（本版）：定 1.4** —— v2 的前提「1.4 只多 maintenance5」**是错的**

范式文档用 1.3，是因为它的目标是「an afternoon」跑通一个通用 demo，设备覆盖优先。
**我们的场景不同**：我们不是从零写应用，而是写一个**要给 GL 调用垫背的垫片**。
1.4 进 core 的 15 个扩展里，有一批正好在填 GL 与 Vulkan 的语义鸿沟。

#### 1.4 进 core 的完整清单（spec Appendix D）

```
VK_KHR_dynamic_rendering_local_read    VK_KHR_global_priority
VK_KHR_index_type_uint8                VK_KHR_line_rasterization
VK_KHR_load_store_op_none              VK_KHR_maintenance5
VK_KHR_maintenance6                    VK_KHR_map_memory2
VK_KHR_push_descriptor                 VK_KHR_shader_expect_assume
VK_KHR_shader_float_controls2          VK_KHR_shader_subgroup_rotate
VK_KHR_vertex_attribute_divisor        VK_EXT_host_image_copy
VK_EXT_pipeline_robustness
```

外加 Roadmap 2024 的**必需**下限：`maxBoundDescriptorSets ≥ 7`、
**`maxColorAttachments ≥ 8`**、`timestampComputeAndGraphics = TRUE`。

MoltenVK 对全部 15 个都是 ✅，只有 `dynamic_rendering_local_read` 标注
"partial support required"。

#### 直接减轻垫片工作量的 7 条

| 1.4 core 特性 | 对应的 GL 语义 | 没有它会怎样 |
|---|---|---|
| **`VK_KHR_push_descriptor`** | `glBindTexture` 之后立刻 `glDrawElements` | 要为每次绑定分配/更新描述符集，或按 frame 复制多份 set |
| **`VK_EXT_host_image_copy`** | `glTexSubImage2D` 是**同步**的 | 必须 staging + 提交命令缓冲 + 等 fence 才能返回 |
| **`VK_KHR_dynamic_rendering_local_read`** | 同一 attachment 读写（混合、后处理回读） | 退化成 resolve 到中间图像再采样，多一次 pass 与分配 |
| **`VK_KHR_vertex_attribute_divisor`** | `glVertexAttribDivisor` | 要 `VK_EXT_vertex_attribute_divisor` 并做特性探测 |
| **`VK_KHR_index_type_uint8`** | `glDrawElements(GL_UNSIGNED_BYTE, ...)` | 要自己把 8 位索引扩成 16/32 位 |
| **`VK_EXT_pipeline_robustness`** | GL 越界读不会崩设备 | 模组给越界数据就可能 device lost |
| **`VK_KHR_maintenance5`** | 见下 | — |

`maintenance5` 里有三条与 vanilla 直接相关：

- **`VK_FORMAT_A8_UNORM` 成为 core 格式** —— GL 有 `GL_ALPHA` 单通道格式，
  vanilla 的字体纹理就用它。范式文档警告过：
  > "If you used such formats in OpenGL the driver often secretly converted them to RGBA.
  > In Vulkan trying to use an unsupported format instead would just fail."

  `A8_UNORM` 进 core 意味着垫片能**原生**表达 `GL_ALPHA`，不用偷偷转 RGBA。
- **`vkCmdBindIndexBuffer2`** —— 绑索引缓冲时可同时给 offset 和 size，
  对应 GL「EBO 已绑定，`glDrawElements` 只给偏移」的用法。
- **shader module 被弃用** —— 可以把 `VkShaderModuleCreateInfo` 直接塞进 pipeline 的
  stage create info。我们的 shader 是 SPIR-V 字节（§8.2），少一层对象。

#### 顺带解答一个旧悬案

之前问过「拿不到 `local_read` 版动态渲染的影响有多大」。答案是：
**它已经是 1.4 core**，不需要「拿不到」。影响只剩 MoltenVK 上的 partial support。

#### 为什么 `maxColorAttachments ≥ 8` 重要

它不是垫片需求，是**光影包需求**：Iris 一系的 deferred pack 用多个 G-buffer 附件。
1.4 把 8 个变成下限，意味着不用探测就能假定存在。

#### 代价

- 设备覆盖比 1.3 窄：1.4 要求 Roadmap 2024 的硬件能力，更老的卡被排除。
- 但注意：**我们本来就已经在排除老设备**——GL 4.6 + compat profile 的门槛更高。
- MoltenVK 需要较新版本才完整支持 1.4，macOS 的分发门槛随之抬高。

**结论：基线定 1.4 core。** §4.8 提到的两个仍不普及的扩展不在 1.4 里，仍然不能依赖。

### 4.2 缓冲区不需要描述符 ← 这推翻了我上一版的最大风险项

上一版我把「描述符（set/layout/pool）」标为 🔴 **最大的未知**，理由是它与 PSO layout、
shader 反射、每帧更新三者耦合。范式文档给了另一条路：

> "by using Vulkan 1.3's buffer device address feature, we can do away with descriptors (for buffers).
> Instead of having to access them through descriptors, we can access buffers via their address
> using pointer syntax in the shader."

做法：缓冲区加 `VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT`，取
`vkGetBufferDeviceAddress`，把地址用 **push constant** 传进 shader，shader 里用指针访问。

```cpp
vkCmdPushConstants(cb, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT,
                   0, sizeof(VkDeviceAddress), &shaderDataBuffers[frameIndex].deviceAddress);
```

**描述符因此只剩图像一种用途**（图像至今没有 BDA 等价物）。而图像侧用
descriptor indexing 走 bindless：

```cpp
Sampler2D textures[];   // shader 侧
textures[NonUniformResourceIndex(input.InstanceIndex)].Sample(input.UV)
```

一个 `VkDescriptorSetLayout`，一个 binding，`descriptorCount = 图像数`，
`VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT`，一个 set，一次 `vkUpdateDescriptorSets`。

> "This approach scales up no matter how many textures you use (within the limits of what the GPU supports)."

**对我们的意义**：vanilla 有大量「设 uniform 再画」的调用。BDA + push constant 让
「每帧/每次绘制的常量」不需要描述符管理，这是最大的一块工程量消失。

### 4.3 图像布局塌缩成 3 个规范布局 ← 这推翻了我的第二风险项

上一版我写「每个 render target / 采样纹理 / glyph atlas 都要跟踪布局」。
1.3 的 `VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL` 把「所有类型的 attachment 布局」合成一个：

> "The `VK_IMAGE_LAYOUT_ATTACHMENT_OPTIMAL` layout is a core feature of Vulkan 1.3
> that combines all types of attachment layouts into a single one. This simplifies image barriers."

于是实际只需要这几个状态：

```
attachment  : UNDEFINED → ATTACHMENT_OPTIMAL → PRESENT_SRC_KHR
被采样纹理  : UNDEFINED → TRANSFER_DST_OPTIMAL → READ_ONLY_OPTIMAL
depth       : UNDEFINED → ATTACHMENT_OPTIMAL（不进 PRESENT）
```

**布局跟踪从「每个图像一套状态机」退化为「按用途分类的固定迁移」**，
可以在创建时把每个图像的用途定死，运行期不做通用状态机。

### 4.4 VMA + `MEMORY_USAGE_AUTO` + 持久映射 → 缓冲区上传不需要 staging

> "Early on, CPU accessible VRAM memory types were only available on systems with a unified
> memory architecture... But thanks to (Re)BAR/SAM even dedicated GPUs can now map most of
> their VRAM into host address space and make it accessible via the CPU."
>
> "Without this we'd have to create a so-called 'staging' buffer on the host, copy data to that
> buffer and then submit a buffer copy... That would require a lot more code."

分配标志组合：

```cpp
.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
       | VMA_ALLOCATION_CREATE_HOST_ACCESS_ALLOW_TRANSFER_INSTEAD_BIT
       | VMA_ALLOCATION_CREATE_MAPPED_BIT,
.usage = VMA_MEMORY_USAGE_AUTO
```

`ALLOW_TRANSFER_INSTEAD` 是关键：拿不到 host-visible VRAM 时 VMA 自动退回 staging 路径，
**不需要我们写两条代码路径**。

**图像上传仍然需要 staging**——optimal tiling 是硬件相关布局，无法 memcpy。

### 4.5 同步拓扑是固定的四件套，但有个反直觉的细节

| 对象 | 数量 | 注意 |
|---|---|---|
| Fence | 每 frame-in-flight 一个 | 创建时必须 `VK_FENCE_CREATE_SIGNALED_BIT`，否则首次 wait 超时 |
| image-acquired 信号量 | 每 frame-in-flight 一个 | 按 `frameIndex` 索引 |
| render-complete 信号量 | **每个 swapchain 图像一个** | 按 `imageIndex` 索引 |
| 屏障 | 命令，不预创建 | `vkCmdPipelineBarrier2` + `VkDependencyInfo` |

第三个是反直觉的，原文解释：

> "`vkQueuePresentKHR` cannot signal a semaphore without a certain extension, which is not yet
> available everywhere. To work around this, we decouple the two semaphore types and use one
> render-complete semaphore per swapchain image instead."

**这直接影响 swapchain 重建**：重建时要按新 `imageCount` 重建 render-complete 信号量数组。

### 4.6 命令缓冲每帧重录，不要预录

> "You might be tempted to pre-record command buffers and reuse them until something changes...
> This makes things unnecessarily complicated however, as you'd have to implement update logic
> that works with CPU/GPU parallelism."

对我们的意义：vanilla 的每帧状态变化幅度很大，**预录必然失败**。这也消除了一个
「要不要做 command buffer 缓存」的伪问题。

### 4.7 viewport / scissor 是动态状态

```cpp
dynamicStates = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
```

⇒ 窗口 resize **不需要重建 pipeline**，只需重建 swapchain + depth。

### 4.8 两个未来候选（现在不能用）

- `VK_EXT_host_image_copy` —— 免命令缓冲的图像上传
- `VK_KHR_unified_image_layouts` —— 消除图像布局

> "These aren't widely supported yet, but future candidates for making Vulkan easier to use."

记下来，不依赖。

### 4.9 工程纪律（不可省）

- **Validation layers 必须常开**，并周期性开 sync validation preset
- **RenderDoc** 必需
- `chk()` 检查所有 `VkResult`；但 present 路径要用 `chkSwapchain()`，因为
  `VK_ERROR_OUT_OF_DATE_KHR` 应该**重建**而不是退出
- 清理顺序：allocator → device → **instance 最后**（这样 validation layer 会报出漏删的对象）
- 命令缓冲不需要显式销毁，销毁 pool 即释放

---

## 5. 重新评估：Vulkan 侧要建的东西

| 项 | 上一版判断 | 采纳 1.3 范式后 |
|---|---|---|
| **描述符** | 🔴 最大未知 | 🟢 **只用于图像**；缓冲区走 BDA + push constant。图像侧可用 1.4 core 的 push descriptor 逐 draw 推，无需 set 生命周期 |
| **图像布局** | 每个 RT/纹理/atlas 都要跟踪 | 🟢 塌缩为按用途分类的 3 条固定迁移 |
| **显存分配** | 内存类型 + 子分配策略 | 🟢 VMA `MEMORY_USAGE_AUTO`，不自己选类型 |
| **缓冲区上传** | 需要 staging | 🟢 持久映射 VRAM + `ALLOW_TRANSFER_INSTEAD` 兜底 |
| **图像上传** | 需要 staging | 🟡 仍然需要（optimal tiling 无法 memcpy） |
| **PSO** | 每个 shader × blend/depth 组合 | 🟡 viewport/scissor 动态；resize 不重建 |
| **屏障 / 同步** | 与帧内多 pass 纠缠 | 🟡 sync2 + 固定四件套；**仍是最难的部分** |
| **帧上下文** | 决定整体结构 | 🟢 frames-in-flight = 2，只有 CPU/GPU 共享资源要复制 |
| **swapchain 重建** | 事件路径与 `Display` 的 resize 处理对接 | 🟢 模板化，`oldSwapchain` + 重建 depth/信号量 |

**真正剩下的硬骨头只有三个**：同步的正确性、§3.1 那 8 个阶段的语义映射、
以及光影包要求的程序图（§6.4）。三者都不是 Vulkan 的复杂度，
是**我们这个场景**的复杂度。

---

## 6. 功能范围：垫片之外的六件事

除了「完全重建 vanilla 渲染」，还要支持六类功能。**关键观察：这六件全部在垫片之上**——
它们不改 `ShimGl` 的接口面，只改后端内部、阶段钩子与图集管理。

（一处例外：CTM 对 `ChunkView` 提了新的邻接查询要求，见 §6.6。）

### 6.1 动态光源 —— 暂不做

原计划支持彩色光、泛光灯、投影。**本轮决定先不做**，理由不是工程量，而是它和光影包路线冲突：

- 只做「从方块位置推彩色光」而不改 vanilla 的光照传播，得到的是**假彩色光**：
  它不与 vanilla 的 smooth lighting 合成，也不与将来 shaderpack 自己的光照模型合成
- 真做要改 vanilla 的光照传播（`Chunk` 的 nibble 光照数组、`World` 的亮度查询），
  那是**游戏逻辑层**的改动，不是渲染后端的事，且会破坏与所有光照类模组的兼容
- 而 shaderpack 路线（§6.4）本来就要**自己定义光照模型**，届时彩色光由 pack 决定

**结论：推迟到 shaderpack 路线定型之后再评估。** 到那时它多半不是「我们实现彩色光」，
而是「我们把足够的光源数据喂给 pack」。

**因此现在不预先设计它的接口**——猜错的形状比缺失的接口更难拆。

### 6.2 Sodium Extra 的常见特性

已核实的特性集（CurseForge / Modrinth）：

| 类别 | 具体项 | 落点 |
|---|---|---|
| 雾 | 总开关、按维度、起始倍率、结束/密度 | 帧策略（相机 uniform + 距离计算） |
| 云 | 开关、高度、距离 | `terrain.sky` 阶段 |
| 天空细节 | 天空/星星/日月/天气/生物群系颜色/天空颜色 开关 | `terrain.sky` 阶段 |
| 动画 | 火/水/岩浆/传送门/方块动画/sculk 传感器 | 纹理图集动画驱动 |
| 粒子 | 各类粒子开关（含模组粒子） | 实体/粒子阶段 |
| 实体 | 距离裁剪、静态实体 | 实体阶段 + `ChunkView` |
| 杂项 | FPS/坐标显示 | HUD，与后端无关 |

**这些是帧级策略，不是绘制级策略**——恰好落在 `PhaseHook` 与后端配置上，
不需要扩 `ShimGl`。这是好消息：它把 §8.4 的接口面问题与功能范围解耦了。

### 6.3 连续 LOD

Distant Horizons 那一系。它不是「优化」，是**另一条几何管线**：

- 数据源：降采样的区块数据（1/2、1/4、1/8 …），与 vanilla `Chunk` 不同构
- 选择：按距离环选取层级，环边界要**连续**（这是「连续 LOD」的含义，
  不是离散跳变）
- 绘制：独立的 pass，且要处理与 vanilla 地形/半透明段的穿插顺序

对应到接缝：需要**新增一个 `terrain.lod` 阶段**，以及一套独立的数据模型。
注意 Actinium 的 `MixinRenderGlobal` 注释里提到过：Distant Horizons 把它的
延迟半透明 LOD pass 锚在 `renderBlockLayer` 的那个重载里，并**跨该重载采样状态**。
这正是 `original()` 语义要保住的东西（§3.1）。

### 6.4 光影包（Aperture）

已定：**我们消费的是 SPIR-V**，不处理 Slang（§8.2）。

但 Aperture 的形态决定了「只给 SPIR-V」不够：

- Aperture 是 **Iris 2.0 的新 shaderpack 标准**
- 它把文件式的 `shaders.properties` 换成 **TypeScript 的 `pack.ts` 描述管线图**
- 「Unlike in OptiFine/Iris, there is no "default" setup; you must create one.」
- Aperture 自身**已经转向 Slang**

所以一个 pack 包含两部分：**SPIR-V 字节** + **管线图（程序与 composite 的顺序、附件、输入）**。
我们只解决了前者。

**这引出一个必须面对的量级差**：§3.1 那 8 个阶段是按 Actinium 的 8 个 `@Overwrite`
一一映射设计的，是**地形中心**的。而 Iris 一系的 pack 需要的是**几十个程序阶段**
（`gbuffers_*` 二十余个、`shadow*`、`deferred*`、`composite*`、`prepare*`、`final`）。

⇒ **8 个阶段对「替换 vanilla 渲染」是充分且必要的；对光影包是不充分的。**
光影包路径根本不走垫片，它走「阶段钩子 + 程序图注册表」。
这需要一个比 `PhaseRegistry` 大一个数量级的东西，且它的形状取决于 Aperture 的格式。

**因此：光影包格式研究必须先于光影包实现，但不必先于 M0–M3。**

### 6.5 OptiFine 动态贴图

vanilla 自己已经有**动画贴图**：`.mcmeta` 里给帧序列，`TextureAtlasSprite` 每 tick 换帧。
那是**时间驱动**的，与我们的后端无关（只是图集内容变，UV 不变）。

OptiFine 的 dynamic textures 是**世界状态驱动**的：`optifine/` 下的 `.properties` 指定
某贴图的内容取自另一组 sprite 中的哪一个，选择依据是方块状态 / 实体状态 / 时间 /
生物群系（如 `compass` / `clock` 按物品 NBT，`water` / `lava` / `fire` / `portal`
按帧与世界状态，`explosion` / `flame` / `smoke` 按粒子年龄）。

对我们的要求分两层，**而这两层的成本差很多**：

| 形态 | 做法 | 成本 |
|---|---|---|
| sprite 已在图集里，只是换用哪一块 | **只改 quad 的 UV**，不上传 | 低。与 CTM 同构 |
| 贴图内容由 CPU 生成/修改 | 图集局部内容更新 | 中。需要上传路径 |

**OptiFine 的多数 dynamic texture 属于第一类**（目标 sprite 本来就在图集里）。
真正需要上传的是少数。

第二类在 Vulkan 侧正好是 §4.1 那条的**第二个受益点**：`VK_EXT_host_image_copy`（1.4 core）
让「同步更新图集的一块区域」变成一次 `vkCopyMemoryToImage`，
不需要 staging + 提交命令缓冲 + 等 fence。GL 的 `glTexSubImage2D` 就是这个语义。

### 6.6 CTM（Connected Textures）

OptiFine 的 CTM 是一个**方法族**，不是单一功能：`standard` / `full`（47 块）、
`compact`（5 块）、`horizontal` / `vertical`、`overlay` 系列、`random`、`repeat`、
`fixed`、`bookshelf` / `glass` / `sandstone`、Chisel 风格的 `ctm_static` 等。

**机制**：对每个方块面算 8 邻接掩码 → 用掩码从 CTM 图集里选一个 tile →
该面的 quad 改用这个 tile 的 UV。

**关键认识：CTM 是「换 UV」，不是「换绘制」。** 在 bindless + 图集下，它
**不增加 draw call、不增加绑定、不增加描述符**。这是它比看起来便宜得多的原因。

#### 已有现成接缝（重要）

Actinium 已经有这个抽象，注释原话：

> "lets addons implement runtime block quad work such as CTM or emissive overlays
> without Mixins into the terrain renderer."

```java
public interface BlockQuadTransformer {
    List<BakedQuad> transform(IBlockState, BlockPos, ActiniumBlockAccess,
                              BlockRenderLayer, EnumFacing, List<BakedQuad>);
}
```

设计文档 §1.2 把它列为「注册表替代 mixin 已在生态里跑通」的两处证据之一
（另一处是 `ChunkAnimationProvider` + `ChunkAnimatorCompat`：一个原本靠 ASM coremod
骇入 vanilla 的模组，被**一个 setter** 替代）。

所以 CTM 在我们的后端里应该是：**网格构建期的一个 quad 变换点**，不是绘制期的东西。

#### 两个必须早定的点

1. **`ChunkView` 要能给出跨区块边界的 8 邻域方块**（含对角）。
   现有 `ChunkView` 的语义是「只读 `loadedChunks`」，不包含邻居查询。
   **这是本轮唯一一处功能倒逼接缝修改的地方**（见 §6 开头的例外声明）。
2. **邻居变化必须能失效相邻区块的网格。** CTM 掩码依赖邻居，所以一个方块变化会
   影响周围 8 个方块所属的网格。vanilla 的 `markBlocksForUpdate` 本来就有
   `-1..1` 的扩展，但要确认它覆盖 CTM 需要的**对角**邻居——否则会出现
   「拆了一块，斜对角的连接纹理不更新」这类顽固 bug。

---

## 6.7 vanilla 1.12 的顶点数据：实测事实

M3 之后要替换占位几何体，先把 vanilla 实际产出什么搞清楚。以下全部读自
`projects/mcpbase/build/patched-mcp/src`（带 patch 的真实源码，不是记忆）。

### 6.7.1 数据形状：每区块 × 每渲染层 = 一个非索引顶点数组

```
RenderChunk.rebuildChunk()
  └─ 逐方块：blockrendererdispatcher.renderBlock(state, pos, worldView, bufferBuilder)
       bufferBuilder = generator.getRegionRenderCacheBuilder().getWorldRendererByLayerId(layer.ordinal())
  └─ 每层结束时：postRenderBlocks(layer, x, y, z, bufferBuilder, compiledChunk)
       └─ TRANSLUCENT 层额外 sortVertexData(x,y,z)
       └─ bufferBuilder.finishDrawing()
  └─ 之后 VertexBufferUploader.draw() → vertexBuffer.bufferData(byteBuffer)
```

关键点：

- **按 `BlockRenderLayer` 分层**（SOLID / CUTOUT_MIPPED / CUTOUT / TRANSLUCENT），
  每层一个 `VertexBuffer`（一个 GL VBO）
- **非索引**：`glDrawArrays`，不是 `glDrawElements`
- **四边形**：`begin(7, BLOCK)`，7 = `GL_QUADS`。**Vulkan 没有四边形图元**
- 顶点坐标已减去区块原点：`setTranslation(-pos.getX(), -pos.getY(), -pos.getZ())`，
  绘制时用 `multModelviewMatrix()` 把区块位置加回去
- 只有 TRANSLUCENT 层在相机移动时重排序并重新上传

### 6.7.2 顶点格式：28 字节，四个属性

`DefaultVertexFormats.BLOCK`，与 `VboRenderList.setupArrayPointers()` 的 stride/offset 完全对得上：

| 属性 | GL 调用 | 类型 | 偏移 | 字节 |
|---|---|---|---|---|
| 位置 | `glVertexPointer(3, 5126, 28, 0)` | `FLOAT` ×3 | 0 | 12 |
| 颜色 | `glColorPointer(4, 5121, 28, 12)` | `UBYTE` ×4 归一化 | 12 | 4 |
| 纹理 UV | `glTexCoordPointer(2, 5126, 28, 16)` | `FLOAT` ×2 | 16 | 8 |
| **光照图 UV** | `glTexCoordPointer(2, 5122, 28, 24)`（lightmap 纹理单元） | `SHORT` ×2 归一化 | 24 | 4 |

**两套 UV**：纹理图集一套，光照图（`lightmapTexUnit`）一套。M3 的 shader 只有位置/法线/颜色，
没有这两套——这是第一个要补的东西。

### 6.7.3 绘制循环：每帧的 draw 数量

```java
// VboRenderList.renderChunkLayer
for (RenderChunk renderchunk : this.renderChunks) {
    GlStateManager.pushMatrix();
    this.preRenderChunk(renderchunk);
    renderchunk.multModelviewMatrix();          // 区块平移进 modelview
    vertexbuffer.bindBuffer();
    this.setupArrayPointers();                  // 每区块重设一次属性指针
    vertexbuffer.drawArrays(7);                 // GL_QUADS
    GlStateManager.popMatrix();
}
```

⇒ **draw 次数 = 可见区块数 × 有内容的层数**。视距 16 时可见区块约 1000+，层数通常 2–4，
所以每帧是**数千次 draw call**，每次一个 uniform 矩阵更新（`multModelviewMatrix` 走 modelview 栈）。

这直接印证了 §3.3 那条约束：渲染图节点必须是 pass 粒度。把这几千次 draw 变成节点是灾难。

### 6.7.4 这对 Vulkan 侧意味着什么

| vanilla 的做法 | Vulkan 不能直接照搬 | 可选路线 |
|---|---|---|
| `GL_QUADS` 非索引 | Vulkan 只有三角形 | **网格构建期把四边形拆成两个三角形**（顶点 4→6），或索引化 |
| 每区块一个 VBO | 数千个小缓冲 | 合并成大缓冲 + 偏移（`vkCmdDrawIndexed` 的 `firstIndex`） |
| 每区块一次 uniform 矩阵 | push constant 每 draw 一次，64 字节 | 可行，但要每 draw 一次 `vkCmdPushConstants` |
| 每区块重设属性指针 | 顶点格式固定则无需 | 网格构建期统一格式 |
| 两套 UV | 需要第二套 UV + 光照图采样 | 加进顶点格式 + 绑定光照图纹理 |
| TRANSLUCENT 每帧重排序 | 需要 CPU 重排 + 重传 | 独立缓冲 + 排序后重建 |

### 6.7.5 构建性能：vanilla 的实测热路径

#### 每 quad 七次调用，其中四次是随机访问读改写

`BlockModelRenderer` 里一个 quad 的实际序列：

```java
buffer.addVertexData(bakedquad.getVertexData());   // 1 次，批量 int[] 拷贝
buffer.putBrightness4(b0, b1, b2, b3);             // 1 次，处理 4 顶点
buffer.putColorMultiplier(r, g, b, 4);             // ← 4 次，一次一个顶点
buffer.putColorMultiplier(r, g, b, 3);
buffer.putColorMultiplier(r, g, b, 2);
buffer.putColorMultiplier(r, g, b, 1);
buffer.putPosition(d0, d1, d2);                    // 1 次，处理 4 顶点
```

每次 `putColorMultiplier` 内部：

```java
int i = this.getColorIndex(vertexIndex);   // 重算偏移
j = this.rawIntBuffer.get(i);              // 直接缓冲随机读
// 拆 3 字节 → 3 次 float 乘 → 3 次 int 截断 → 重打包
this.rawIntBuffer.put(i, j);               // 直接缓冲随机写
```

**每 quad 八次直接缓冲随机访问读改写，只为乘一个颜色。** 另外 `growBuffer` 按 2 MB 步进，
增长时新建 direct buffer 并**整块拷贝**。

#### 瓶颈排序（按影响）

1. **`getActualState` / `getExtendedState` 被按层重复调用。**
   `rebuildChunk` 的层循环在方块循环**内部**，跨两层的方块算两遍状态。
2. **`BlockRendererDispatcher.renderBlock` 的结构开销**：6 方向各一次 `model.getQuads(...)` 返回 List、
   每方向一次 `shouldSideBeRendered`，然后进 `renderModel` 的 `VertexFormat` 分派与 AO 管线。
3. **逐顶点颜色读改写**（上面那 8 次）。
4. `growBuffer` 的整块拷贝。

`BufferBuilder` 本身反而**不是**最大的那块。

### 6.7.6 Actinium 的兼容策略：按方块分流，保留 vanilla 入口

```java
if (blockState.getRenderType() == EnumBlockRenderType.MODEL
        && options.useFastBlockRenderer
        && !SnowRealMagicCompat.shouldForceVanillaRender(block)
        && !ArchitectureCraftCompat.shouldForceVanillaRender(block)) {
    buildContext.getBlockRenderer().renderBlock(...);                    // 快路径
} else {
    var buffer = buildContext.getBufferForLayer(layer);
    buildContext.beginVanillaBlockRender(buffer, blockPos, blockState);
    try { dispatcher.renderBlock(blockState, blockPos, slice, buffer); }  // vanilla 路径
    finally { buildContext.endVanillaRender(buffer); }
}
```

- 只对 **`EnumBlockRenderType.MODEL`（烘焙模型）** 走快路径
- 其余（自定义 ISBRH、特殊渲染类型）全部走 `dispatcher.renderBlock`
- 再叠加**按模组的显式豁免**（`shouldForceVanillaRender`）

#### 最深的一课：兼容性覆盖「mixin 进它」的模组

`VintageBlockRenderer` 里的注释原话：

> The vanilla face-culling call stays at this call site **on purpose**: addon mixins `@Redirect`
> instructions inside `renderBlock` … so extracting it into a helper method would **move it out of
> their reach**.

而且有连带后果：快路径跳过了 `BlockModelRenderer`，而某个模组的「隐藏方块」规则原本是靠 redirect
注入在 `BlockModelRenderer` 里的——所以 Actinium 必须**自己重新实现那条遮挡规则**，
否则隐藏方块仍然遮挡、在相邻方块上打出透视洞。

⇒ **快路径必须复刻 vanilla 代码的副作用，包括别的模组 mixin 注入进去的副作用。**

### 6.7.7 批量化不是选择性的，但缓冲粒度是

`VintageChunkBuildContext.convertVanillaDataToCeleritasData` 是汇合点：

```java
for (int i = 0; i < renderers.length; i++) {
    ByteBuffer rawBuffer = bufferBuilder.getByteBuffer();              // vanilla 的 28 字节 BLOCK 顶点
    List<VanillaQuadContext> quadContexts = extension.actinium$consumeQuadContexts();
    var material = buffers.getRenderPassConfiguration().getMaterialForRenderType(layer);
    copyBlockData(rawBuffer, buffers, material, layer, quadContexts);  // 转成 Celeritas 格式
}
```

| | 快路径 | vanilla 路径 |
|---|---|---|
| 产出 | 直接写 Celeritas 格式 | 写 `BufferBuilder`（28 字节 BLOCK） |
| 缓冲粒度 | **按 material** | **按 `BlockRenderLayer`**（只有 4 个） |
| 汇合 | — | 转格式后并入对应 material 缓冲 |

**两条路径最终都进同一个 material 缓冲，所以批量化本身是一致的、非选择性的。**
分流只决定「谁生成几何」，不决定「几何去哪」。

但 vanilla 路径的粒度比快路径**粗**，所以 `copyBlockData` 需要 `quadContexts`：
vanilla 的一个 `BufferBuilder` 里混了多个 material 的 quad，必须**逐 quad 记录上下文**
（`beginVanillaBlockRender` 挂 `VanillaQuadContext`：局部坐标、shader block id、render type、光照值）
才能在转换时重新分配。

### 6.7.8 对我们的含义：两笔成本我们不用付

我们的拦截点是 `VertexBufferUploader.draw`（上传），不是 `renderBlock`（构建）：

| | Actinium | 我们 |
|---|---|---|
| 替换什么 | `renderBlock` 的调用 | `VertexBufferUploader.draw` 的上传 |
| 得到什么 | 快路径 + vanilla 回退 | **vanilla 的慢构建，但数据完整** |
| 兼容性成本 | 要复刻被跳过代码的副作用 | **零**——vanilla 代码一行没跳过 |

因此：

- **不需要** `shouldForceVanillaRender` 那类豁免机制：我们的慢路径就是 vanilla 本身
- **不需要** `quadContexts` 逐 quad 上下文记录：我们只有 4 个 layer 缓冲，无 material 细分
- **不需要** `convertVanillaDataToCeleritasData` 格式转换：直接吃 28 字节 BLOCK

**性能优化和兼容性在我们这里是可分离的**：M4 走慢路径保证正确性，之后要提速时再把快路径
塞进**同一个接缝**——那时才需要面对 Actinium 面对的那些问题。

### 6.7.9 批量化的真实形态

粒度是 **chunk × layer**（视距 16 约 1000+ 区块 × 2–4 层 = 数千次 draw）。要减少 draw 就得跨区块合并，
而合并的前提是所有区块用**同一个顶点格式**——单一路径恰好满足。

所以批量化在我们的后端里是：**把 N 个 chunk×layer 缓冲合并进一个大缓冲 + 每段一个 `firstIndex`**。
这与快/慢路径无关，是纯粹的上传层决定。

### 6.7.10 M4 第一步已实现：格式直通

**实现的核心发现：vanilla 的地形光照是烘焙进顶点颜色的**（`putColorMultiplier` 乘的就是 diffuse+AO），
所以**不需要法线，格式一个字节都不用转**——转换只是「四边形拆三角形 + 索引化」。

| 交付 | 说明 |
|---|---|
| `TerrainVertexFormat` | 28 字节布局的常量，offset 与 `setupArrayPointers` 一致，代码与 shader 共用一份事实 |
| `TerrainMeshBuilder` | quad → 索引化三角形；顶点字节**原样克隆**，零转换 |
| `TerrainMeshBuilderTest` | 8 个测试：winding、多 quad 基址、16/32 位选择、截断拒绝 |
| shader 重写 | 四属性输入（pos/colour/uv/lightmap），fragment 显示烘焙色 × 光照图 |
| `VulkanPipeline` | 四属性顶点输入，`R16G16_UNORM` 让 shader 直接拿到 0..1 的光照图 |

索引化收益（实测）：100 quad 时 5400 字节 vs 6720 字节（省 20%）。

**验收**：真机截图——泥土色侧面 + 草绿顶面 + per-texel 格纹（证明 UV 流动），
光照符合 vanilla diffuse 系数，validation 零报错。503 测试全绿。

### 6.7.11 参考材料的边界：howtovulkan 不管批量化

核实结果（直接问原文，不是凭印象）：

| 主题 | howtovulkan |
|---|---|
| 批量化 / 减少 draw call | ❌ 完全没有 |
| 间接绘制（`vkCmdDrawIndexedIndirect`、draw indirect count、multi-draw） | ❌ 完全没有 |
| GPU-driven / mesh shader / visibility buffer | ❌ 完全没有 |
| 实例化 | ✅ 有，但是「同一个网格画 N 次」 |
| 顶点+索引同一缓冲、按 offset 绑索引 | ✅ 有，正是我们要的形状 |

它自己声明的范围就排除了这些：

> "We focus on rasterization, other parts of Vulkan like compute or raytracing are not covered."
> "It won't explain programming, **software architecture**, graphics concepts or how Vulkan works (in detail)."

**它是一份「一个下午把三角形画出来」的教程，刻意回避架构。** 我们的问题是架构问题，它答不了。

#### 但它有两条正好对上

**1. 顶点和索引放同一缓冲，索引按 offset 绑定**

> "Instead of having separate buffers for vertices and indices, we'll put both into the same buffer. Hence why the
> `size` of the buffer is calculated from the size of both vertices and index vectors."
> `vkCmdBindIndexBuffer(cb, vBuffer, vBufSize, VK_INDEX_TYPE_UINT16);`

这正是「N 个 chunk×layer 合并进一个大缓冲 + 每段一个偏移」的形状。**不需要为每个区块建独立缓冲。**

**2. 实例化不适用于地形**

`vkCmdDrawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance)` 的实例化要求
**几何相同**、只是变换不同。区块各不相同，所以实例化不适合地形——它适合「同一网格重复」。

#### 三条真实路线

| 方向 | 做法 | 状态 |
|---|---|---|
| **合并缓冲 + 多次绑定** | 一个大 VBO/IBO，每段一次 `vkCmdDrawIndexed(firstIndex, …)` | ✅ 1.0 就有，howtovulkan 示范了机制 |
| **间接绘制** | `vkCmdDrawIndexedIndirect` + draw 参数数组，一次调用画 N 段 | ✅ 1.4 core |
| **Multi-draw** | `VK_EXT_multi_draw` / `vkCmdDrawMultiIndexedEXT` | ⚠️ 扩展，非 core |

真正「最新」的地形做法是 GPU-driven：可见性剔除搬到 compute，写 indirect 参数，再一次 indirect 绘制。
**远超当前阶段。**

#### 结论：先只做合并缓冲

按现在的阶段（每区块一个 mesh，draw = chunk × layer），第一步只做「合并缓冲 + 每段一次 draw」：

- 不引入新机制（就是 howtovulkan 示范的那条）
- 把「每区块一个 `VkBuffer`」变成「一个大 `VkBuffer`」——这是**内存与绑定开销**的胜利
- draw 数仍是数千，但每次 draw 的成本从「绑缓冲 + 绑描述符」降到一次 `vkCmdDrawIndexed`

要到「一次调用画完所有区块」才需要 indirect。那一步应该**在有真实地形、能量出 draw call 成本之后**再做——
否则是猜。

### 6.7.12 M4 第二步已实现：跨区块合并缓冲

| 交付 | 说明 |
|---|---|
| `TerrainMeshPacker` | N 个 mesh → 一个顶点缓冲 + 一个索引缓冲 + 每段的 `Slice(firstIndex, indexCount, vertexOffset)` |
| `TerrainMeshPackerTest` | 8 个测试：slice 记账、字节顺序、索引保持 chunk 相对、16→32 位扩宽、空 pack |
| `VulkanTerrainLayer` | 绑一次缓冲，逐 slice 一次 `vkCmdDrawIndexed` |

**两个关键决定**：

1. **索引保持 chunk 相对，不重写基址**——base 通过 draw call 的 `vertexOffset` 传入。
   重写每个索引是对索引数据的一次全量遍历，只为一个 draw call 本来就带的参数。
2. **一个 mesh 超过 65535 顶点就把整个 pack 扩到 32 位**——pack 共享一个索引空间，
   无法按 mesh 混用两种宽度。

**验收**：4×4 网格 16 个立方体，每个都在正确位置、各自 tint 正确——
`firstIndex`/`vertexOffset` 错任何一个都会画出错位几何。validation 零报错。511 测试全绿。

**下一步（M4 剩余）**：接真实数据——patch `VertexBufferUploader.draw` 拦截字节流。

### 6.7.13 还没查的

- `RegionRenderCacheBuilder` 的缓冲尺寸与扩容策略（决定单区块顶点数上界）
- 区块网格构建的线程模型（`ChunkCompileTaskGenerator` / `ChunkRenderWorker`），
  以及它和 Vulkan 命令缓冲录制线程的关系
- `BlockRendererDispatcher.renderBlock` 里 Forge 的 `RenderBlockLayer` 事件钩子
  （`ForgeHooksClient.setRenderLayer`）——这是模组插入地形构建的既有位置

### 6.7.14 M4 第三步：用 vanilla 的真实产出验证格式

前面几步的测试都是自己造字节，只能证明转换自洽，不能证明读的是游戏真正写的东西。
`VanillaTerrainDataTest` 改成驱动真实的 `BufferBuilder` + `DefaultVertexFormats.BLOCK`。

`BufferBuilder` 是**纯 CPU** 的（不碰 GL），所以整条地形数据路径可以无上下文测试——
这同时也是捕获钩子能在 `ChunkRenderDispatcher` 里直接交字节的原因。

**测试抓到了两个只看代码发现不了的错：**

| 错法 | 症状 | 真相 |
|---|---|---|
| 把颜色当 float 读 | `NaN` | `BLOCK` 的颜色是 `COLOR_4UB`（4 字节），float 读会横跨颜色和一半 UV |
| 把 lightmap 当 (sky, block) | 白天变暗、夜里变亮 | `BufferBuilder.lightmap(skyLight, blockLight)` 写盘是 **block 在前、sky 在后**——与它自己的参数顺序相反 |

第二条连 `terrain.frag` 里都写反了（`blockLight = inLightmap.y`），只是被
`max(blockLight, skyLight)` 的对称性掩盖了。已修，并把分量顺序写进
`TerrainVertexFormat` 的注释——**偏移量检查抓不到这类错**。

**没验证到的**：捕获钩子在活客户端里真的被触发。那需要客户端能启动，
而启动链卡在 §8.4.1 的第 4 条。

---

## 7. 依赖与分发

### 7.1 要补的模块

`gradle.properties`: `lwjgl_version = 3.4.3`
`lwjglLibraries[0]` 现在是：

```groovy
['lwjgl-sdl', 'lwjgl-jemalloc', 'lwjgl-openal', 'lwjgl-opengl', 'lwjgl-stb', 'lwjgl']
```

要加：

| 模块 | 用途 | 备注 |
|---|---|---|
| `lwjgl-vulkan` | 核心绑定 `org.lwjgl.vulkan.*` | 见 §7.2 的 natives 特殊性 |
| `lwjgl-vma` | `org.lwjgl.vulkan.Vma*` | 对应范式里的 VMA |
| `lwjgl-ktx` | 可选，纹理容器 | 我们的纹理来自 vanilla，多半用不上 |

**已经有的、不需要额外加**：`lwjgl-sdl` 是 SDL3，`SDL_Vulkan_GetInstanceExtensions` /
`SDL_Vulkan_CreateSurface` / `SDL_Vulkan_GetPresentationSupport` 都在里面，
所以 **surface 创建不缺依赖**。

#### 【M0 已落】只进了 `lwjgl3Api` + `compileOnly`

已核实：**`lwjgl-vulkan` 不能放进 `lwjglLibraries[0]`**。那个列表会按 `lwjglLibraries[1]`
的每个架构展开成 `lwjglNatives`，而 `lwjgl-vulkan` 在 Maven Central 上只有
`natives-macos` / `natives-macos-arm64` 两个 natives；`natives-linux` 等并不存在，
放进去会让 Gradle 直接解析失败。`lwjgl-vma` 则全平台齐全。

所以 M0 的做法是：

```groovy
['lwjgl-vulkan', 'lwjgl-vma'].each {
    lwjgl3Api   "org.lwjgl:$it:$props.lwjgl_version"
    compileOnly "org.lwjgl:$it:$props.lwjgl_version"
}
```

**为什么不进 `installer` / `runtimeOnly`**：那两个配置最终会写进 launcher JSON，
也就是**每个玩家都会下载**，包括永远不会跑 Vulkan 的人。M0 阶段一行 Vulkan 代码都没有，
没有任何理由把二进制发出去。

已验证：`compileClasspath` 里有两者，`runtimeClasspath` 与 `installer` 里都没有，
且 `verifyNoStaleLibraries` 通过。

**留给 M1 的**：`installer` / `runtimeOnly` / `lwjglNatives` 的接入，以及
`lwjgl-vulkan` 的 macOS-only natives 特殊处理（需要在 `Util.getLWJGLNatives` 里
支持「某模块只有部分架构」的声明，否则 macOS 的 MoltenVK 会被 `catch (ignored) {}`
静默丢掉）。

### 7.2 `lwjgl-vulkan` 的 natives 分发与 OpenGL 完全不同 ← 重要

已核实（LWJGL issue #412 与 javadoc）：

> "There is no `lwjgl-vulkan-natives` for Linux/Windows. On macOS it contains the MoltenVK binary,
> which emulates Vulkan over Metal."

也就是说：

| 平台 | 行为 |
|---|---|
| Windows | 加载系统的 `vulkan-1.dll`（显卡驱动提供），**LWJGL 不带 natives** |
| Linux | 加载系统的 `libvulkan.so.1`（`libvulkan1` 包 / 驱动），**不带 natives** |
| macOS | `lwjgl-vulkan-natives-macos*` 内含 MoltenVK；先试 `libMoltenVK.dylib`，再试 `libvulkan.1.dylib` |

MoltenVK 侧已确认支持 1.4 的全部 15 个提升扩展（`dynamic_rendering_local_read` 为
partial support），所以 §4.1 的 1.4 基线在 macOS 上成立，代价是 MoltenVK 版本门槛。

**推论（对分发策略是实质性的）**：

1. **Windows/Linux 上我们依赖用户装了 Vulkan loader。** 现代驱动通常自带，
   但老 Intel、部分 Linux 发行版、虚拟机、远程桌面环境可能没有。
   ⇒ `BackendProbe.isAvailable()` 必须真的去试加载，而不能只查驱动版本。
2. **validation layers 不来自 LWJGL natives**，来自系统 / Vulkan SDK。
   开发期要显式要求装 SDK。
3. macOS 走 MoltenVK 意味着**它是「层」不是驱动**，性能与特性覆盖都另算。

这与 OpenGL 时代「LWJGL 自带 natives，装上就能跑」的直觉相反，
是选择 Vulkan 时最容易被低估的分发成本。

### 7.3 Java 侧的映射

范式文档是 C++20 + C API。对应到我们的栈：

| 范式里的 | 我们用什么 |
|---|---|
| C 结构体 + designated initializers | LWJGL 的 `VkXxx` + `MemoryStack` 分配，`sType` 由绑定自动填 |
| `pNext` 链 | `setPNext(...)` |
| Volk（meta-loader） | **不需要**，LWJGL 自己加载函数指针 |
| VMA | `lwjgl-vma` |
| glm | **joml**（已为 `MatrixView` 重新引入） |
| SDL3 | `lwjgl-sdl`（已有） |
| Slang 运行期编译 | **没有 Java 绑定** ← 见 §8.2 |
| KTX-Software | `lwjgl-ktx`（若需要） |

---

## 8. 尚未决定的问题

### 8.1 【已定】门后机制：分支默认启用，合入前再议

**决定**：不单独做「门后开关」。在 `vulkan-backend` 分支上把 Vulkan 后端注册为**默认候选**，
优先级高于其它候选。分支上默认就进 Vulkan。等真正成熟、准备合入时再决定默认状态。

（澄清一个细节：`vanilla-gl` **不在候选列表里**，它是回退目标。所以「放到原版 gl 之上」
的实际含义是「成为优先级最高的候选」。）

#### 为什么这样可行

`RenderBackendSelection` 的选择逻辑本身不需要改——变的是「注册了什么候选」与
「probe 回答什么」。所以：

- 选择逻辑的测试（property 优先、不可用回落、未知名字回落）**永远保持绿**
- 分支与主干只在两处不同：**注册的优先级**、**probe 的返回值**

#### 必须做的一件事：让这个差异可被发现

「分支默认进 Vulkan」如果只体现为一个数字，合入时极易漏掉。所以：

- 优先级写成**具名常量**，javadoc 明写「分支开发期高于一切候选；合入前必须重审」
- 启动时**显式日志**：「Vulkan backend selected by default (branch development mode)」
- 这两处进合入 checklist

#### 与 §2-2 的张力，以及怎么保持契约诚实

§2-2 说「不能覆盖全部所属阶段的后端不该被选中」。M1 阶段的后端只做到「清屏」，
它确实覆盖不了那 8 个阶段——此时 probe 返回 `true` 就是在撒谎。

处理方式：**probe 的语义是「能拥有帧」，不是「功能完整」。** 分两步：

| 阶段 | probe | 结果 |
|---|---|---|
| **M0** | `false` | 仍回落 vanilla。目的是验证注册/冻结/日志链路，且不打断开发循环 |
| **M1** | `true` | 一旦能建 instance/device/swapchain 并清屏，分支从此默认进 Vulkan |

从 M1 到 M3，画面是错的、功能是缺的，但**它能拥有帧**——probe 说的是真话。
真正该在合入前解决的是「覆盖率」而不是「可用性」，而那正是未来要定的默认状态问题。

#### 合入时要定的三选一（记录，不现在决定）

1. 默认开启（对所有用户）
2. 通过 `-Doutlands.render.backend=vulkan` 显式选择
3. 按硬件能力自动选择（probe 里加 Vulkan 版本 / 扩展检查）

#### 顺带一条

任何第三方（渲染模组）注册的后端候选应被**显式拒绝**（§8.4），
否则一个渲染模组的候选可能抢在 Vulkan 之前。

### 8.2 【已定】shader 是 SPIR-V，不处理 Slang

计划分发的光影包**以 SPIR-V 形式消费**。因此：

- **不需要** Slang 编译器、不需要 FFM 下调 `libslang.so`、不需要离线 `slangc`
- 只需要 `vkCreateShaderModule`（或 1.4 的 maintenance5 路径，连 module 对象都省掉）
- 范式文档里的 Slang 相关坑（LunarG SDK 1.4.357.0 的编译 bug）与我们无关

**这消掉了原 v2 的阻塞项。** 工具链已在 §8.3 核实：`lwjgl-spvc` 提供反省与特化常量，
`lwjgl-shaderc` 备用；没有 Slang 绑定也不需要。

#### 8.2.1 【已定】两条 shader 来源：原版手写 + AOT，模组 JIT

§8.2 只定了**分发格式**。补上「谁在什么时候把 GLSL 变成 SPIR-V」，因为两者性质完全不同：

| 来源 | 什么时候编译 | 谁负责 | 为什么 |
|---|---|---|---|
| 原版 `shaders/program/*` | 构建期（AOT） | `src/main/shaders/vanilla/` 手写 Vulkan GLSL 450，`compileProgramShaders` 出 `.spv`，随 jar 发到 `assets/minecraft/shaders/program/` 同名位置 | 原版 shader 是**固定资产**，没必要让每个玩家的首次使用付编译代价；手写版还能显式给出 block binding 与 varying location |
| 光影包 / 模组 | 运行期（JIT） | `GlShaders` 收 `glShaderSource` 的文本 → `GlslPreprocessor` → `shaderc` | 源只存在于运行时；通用改写器能覆盖 GLSL 1.20 时代的写法就够，不必对每个模组特供 |

两条路的产物是同一种东西（SPIR-V 模块），下游（`SpirvReflection` → 管线 → 描述符）不分来源。
`.spv` 优先加载的规则已落地：模块放在源的同一路径、同一 namespace、同一资源包下（`blit.fsh` 与
`blit.fsh.spv`），`SpirvResources` 只在这一条成立时采用模块，否则回落到编译源码。

**为什么原版手写而不是通用改写。** 通用改写器只做语法升级，varying 的 location 交给 shaderc 的
`auto_map_locations` 按**声明顺序**分配 —— 顶点和片元是两个模块各自编译的，顺序不同就会
「名字对得上、location 对不上」，画出来是垃圾数据且不抛异常。手写版把 location 写死，
`VanillaProgramShadersTest` 逐对校验顶点输出与片元输入的名字+location 一致，以及 block 里共享成员的偏移一致。

**为什么不用 Slang。** 评估过本机 `slangc` 2026.22（`/home/gba/bin/slang/`）：能直接出 SPIR-V，
`[[vk::binding]]` / `[[vk::location]]` 显式，加 `-g` 后成员名会以 `OpMemberName` 出现（否则按名查 uniform 会失败），
`OpSource=11` 可标记来源。代价是构建多一条工具链（slangc + 若干 `.so`，CI 要下载）并与 loader 自有 GLSL 并存两种语言。
对这五个十行级的原版 shader 不划算；**长期**若要把 loader 自己的管线 shader 模块化，Slang 仍是候选，
所以 AOT 那一步按「源目录 → `.spv`」抽象，换语言只动编译分派。

**还缺什么（原版路径真正跑起来的前提）。** 能力表仍然声明 GL 2.0/2.1 为 false。shader 对象本身已可用，
但 `entity_outline` 这个 `ShaderGroup` 一建立就要：第二张渲染目标（`swap`/`final`）以及对应的
「绑定 / 清屏 / 采样」链路，还有用链接好的 program 画全屏 quad 的绘制路径。两者都还不存在（见 §3.3 与
`FramebufferVk` 的说明），所以现在声明支持只会把「明确的缺口」变成「帧中间的一次失败」。

### 8.3 【可推迟】Aperture 的管线图，与「格式是数据」这条硬约束

SPIR-V 只解决「shader 本身」。pack 的另一半是**管线图**：程序注册顺序、composite 链、
附件与输入声明。这部分不是 SPIR-V 能表达的。

#### 先核实：LWJGL 到底有什么工具链

| 模块 | 能力 | 我们用不用 |
|---|---|---|
| `lwjgl-spvc`（SPIRV-Cross） | SPIR-V → GLSL/HLSL/MSL；**反省**（`SpvcReflectedResource`）；**特化常量**（`SpvcSpecializationConstant`） | ✅ 反省必用 |
| `lwjgl-shaderc` | GLSL/HLSL → SPIR-V | ⭕ 备用（我们的 shader 已是 SPIR-V） |
| `lwjgl-ktx` | 纹理容器 | ⭕ 可选 |
| **Slang** | — | ❌ **没有绑定**，也不需要：作者在离线编译 |
| **spirv-opt** | SPIR-V 优化器 | ❌ **LWJGL 没有绑定**（shaderc 内部含 SPIRV-Tools，但不暴露优化 API） |

两个模块都有全平台 natives。

#### 「用户电脑上再进一步编译」实际能做什么

这句话有三种可能的含义，能力差别很大：

| 含义 | 可行性 |
|---|---|
| 驱动把 SPIR-V 编译成 ISA | ✅ **总是发生**，在 `vkCreateGraphicsPipelines` 时，不需要我们做任何事 |
| **特化常量**在运行期定值 | ✅ `spvc` 反省出 `SpvcSpecializationConstant`，用 `VkSpecializationInfo` 填值。这才是真正的「按机器再编译」 |
| 跑 `spirv-opt` 优化 pass | ❌ LWJGL 没有绑定。要么不用，要么自己写 FFM 绑定 |

所以「分发 SPIR-V + 运行期再编译」这个构想成立，但**再编译的手段是特化常量，不是优化器**。

#### 硬约束：格式必须是数据，不能是代码 ← 现在就定

Aperture 用 TypeScript 的 `pack.ts` 描述管线。**我们不跟这一条。**
先把一条站不住的理由删掉，再说真的理由。

**站不住的：安全边界。** 我上一版写「下载来的内容不能执行代码」，这在 1.12.2 里是错的——
模组本身就是任意 JVM 字节码，而且 `nashorn-core:15.7` 已经因为老模组兼容在依赖树里
（`// Scripts`）。一个装得下模组的平台没资格拿「不许执行」当理由。

**站得住的四条：**

**1. Nashorn 在，不等于 TS 工具链在。** 这是两件差别很大的事。

| | 现状 | 缺口 |
|---|---|---|
| JS 引擎 | `nashorn-core:15.7`（**ES5.1**，无模块、无 Promise） | 无 |
| **TS 转译器** | — | **新依赖**，且是大件：`tsc` 本身要现代 JS 才能跑，Nashorn 跑不动；现实选项是 SWC / esbuild 的各平台原生二进制，或 `tsc.js` + GraalJS |
| 现代运行时 | — | 若用 GraalJS，是又一个引擎 |

引擎是便宜的那一半，**转译器 + 现代运行时才是贵的那一半**。所以「反正已经有 Nashorn 了」
不成立——Nashorn 连 `tsc` 都执行不了。

**2. 必须在执行前校验，而不是执行后祈祷。** 一个 pack 要能回答：有哪些附件、
哪条边成环、哪个程序引用了没声明的渲染目标。数据可以静态检查；
程序只能**跑一遍**才能知道——而跑一遍就意味着 pack 已经拿到了执行权。

**3. 确定性。** 同一份 pack 两次加载必须产出同一条管线。`Date.now()`、`Math.random()`、
全局状态都不保证这一点，而排错时「上次是好的」必须是个有意义的问题。

**4. 赌注。** 我们自己的数据格式没有上游依赖。TS-on-Java 桥接库（用户看到的那些声称
ES2026、高度疑似 AI 生成的）意味着**把 pack 格式押在一个可能没人维护的桥接上**。
pack 格式是要活很多年的东西。

#### 那到底为什么要「可计算」

这一条值得单独说，因为它是 Aperture 做对的判断，不该因为否掉 TS 就一起否掉。

**需求是避免选项的笛卡尔积。** 一个 pack 有阴影开关、质量档、雾开关、若干效果开关；
如果只能声明，就得为每种组合各写一条管线——组合爆炸。所以 pack 需要**在加载时算**，
而不是提前枚举。

但要注意这个计算的**定义域是小的、已知的**：选项是有界的（bool / 枚举 / 数值区间），
输入是程序与附件，输出是管线。这不需要通用语言。

**结论：要一个「总函数」的表达式语言，不要图灵完备的语言。**

- 总函数 ⇒ 一定终止，可以静态求值、可以分析、可以拒绝无限循环
- 数据形状 ⇒ 可以先校验再执行（第 2 条）
- 有界定义域 ⇒ 选项的每种取值都能被测试覆盖

这正是 CUE / Dhall / Jsonnet 这一类存在的理由：**要计算，但不要图灵完备**，
因为要能分析。`shaders.properties` 的失败恰恰是相反方向——它靠约定不断长大，
最后事实上变成了程序，但没有任何人能校验它。

**所以：pack 格式 = 数据 + 一个受限表达式语言，不是 TypeScript。**

TS 唯一能提供的、数据格式提供不了的东西是**作者熟悉度**。那是文档问题，不是架构问题；
而代价是 JS 语义、转译器、以及版本跑步机。

#### 推迟的代价：几乎为零

关键观察：**pack 格式不是新子系统，它是「已序列化的子图 + 附件声明」。**
`RenderGraph` 已经表达了节点、边、顺序；pack 只是把同一形状从代码里搬到文件里。

所以推迟的安全理由：

- **M3 不需要它**。M3 只要一个硬编码的地形 shader 和一个硬编码 PSO。
- **插入点已经存在**。§3.3 的图就是插入机制；光影包接管整条管线 = 注册自己的节点并连边。
  缺的不是机制，是**描述**。
- **推迟到期的位置是 M5（pack 加载），不是 M3。** 到那时先要定的是
  「一个 pack 的节点如何映射到图的 label」，而不是 TS 与否。

#### 三个参考对象各借什么

| 来源 | 借 | 不借 |
|---|---|---|
| **OptiFine** | **词汇表**：`gbuffers_*` / `shadow*` / `composite*` 的阶段划分，作者的心智模型 | `shaders.properties` 的隐式文件名约定 |
| **Iris** | 与真实渲染器的集成方式、阶段分类 | 对老 pack 的兼容层 |
| **Aperture** | **核心洞察：用显式管线描述取代「靠文件名约定」** | TypeScript / `pack.ts` |

**Aperture 真正的贡献不是 TS，是把「隐式约定」换成「显式描述」。** 那一半我们要，
而且它与 TS 正交。

#### 老 pack 不兼容：这是已定的支持策略

老光影包（OptiFine/Iris 格式）**不兼容**，由用户自行用 AI 移植。
这直接删掉了一大块范围：不需要实现 `shaders.properties`、不需要模拟 `gbuffers_*` 的隐式语义、
不需要追 Iris 的插入点。参考它们的**设计**，不是实现它们的**格式**。

### 8.4 【已定】垫片契约：GL 2.1 子集

**定义**：`ShimGl` 只承诺 **GL 2.1** 的命令面。
目标消费者是「1.12 生态里绝大多数老式直接渲染模组」。
**任何现有渲染模组（Actinium / Angelica / OptiFine / Celeritas 等）明确不支持**，
且应当被**显式检测并拒绝**，而不是半工作。

#### 为什么 GL 2.1 是自然的分界线

vanilla 1.12.2 自己的最低要求就是 OpenGL 2.1（GLSL 1.20）。所以这条线不是我们画的，
是游戏本身画的：**垫片只需要覆盖 vanilla 能调的东西 + 老模组合理会调的东西**。

一个必须说清的细节：vanilla 1.12 的 `Framebuffer` 走 `ARBFramebufferObject`
（GL 3.0 core，2.1 里是扩展）。所以契约实际是 **「GL 2.1 + ARB_framebuffer_object」**。
不把 FBO 算进来的话，vanilla 自己的主渲染目标就无法表达。

#### 三桶分类（对应 §2-6 的 no-op / route / fail）

**桶 A：必须支持**

| 组 | 内容 | Vulkan 侧代价 |
|---|---|---|
| 立即模式 | `glBegin/glEnd`、`glVertex3f`、`glColor4f`、`glNormal3f`、`glTexCoord2f` | 累积缓冲，`glEnd` 时 flush |
| 矩阵栈 | `glMatrixMode`、`glLoadIdentity`、`glMultMatrixf`、`glPushMatrix`/`glPopMatrix` | CPU 维护栈，送进生成的 shader |
| 固定功能光照 | `glLight*`、`glMaterial*`、`glShadeModel` | **生成 FFP shader** |
| 纹理环境 | `glTexEnv*`（replace/modulate/add/combine）、`glActiveTexture` | 同上 |
| 雾 / alpha 测试 / 裁剪面 | `glFog*`、`glAlphaFunc`、`glClipPlane` | 同上 |
| 顶点数组 | `glVertexPointer` 等 + `glEnableClientState`、**client memory 指针** | 每次绘制上传（持久映射缓冲） |
| 缓冲对象 | `glGenBuffers`/`glBindBuffer`/`glBufferData`/`glVertexAttribPointer` | VBO + **vertex layout → PSO 缓存** |
| 绘制 | `glDrawArrays`、`glDrawElements`、`glDrawRangeElements`、`glMultiDraw*` | 直接映射 |
| 纹理 | `glTexImage2D`、`glTexSubImage2D`、`glTexParameter*`、mipmap、`glCopyTexSubImage2D` | 1.4 的 `host_image_copy`（§4.1） |
| 状态 | `glEnable/glDisable`、`glBlendFunc`、`glDepthMask`、`glDepthFunc`、`glCullFace`、`glPolygonOffset`、`glColorMask`、`glStencil*`、`glViewport`、`glScissor` | 动态状态（§4.7） |
| 状态栈 | `glPushAttrib`/`glPopAttrib`、`glPushClientAttrib` | 自己维护 |
| 显示列表 | `glNewList`/`glCallList`/`glGenLists` | **记录命令流并重放** |
| 像素读取 | `glReadPixels` | 需要 staging 回读 |
| FBO（ARB） | `glGenFramebuffers`、`glBindFramebuffer`、`glFramebufferTexture2D` | 动态渲染 + 自建附件 |

**桶 B：可以 no-op（构造上不可达或无视觉效果）**

- `glHint` —— 无对应
- `glFlush` —— Vulkan 无此语义
- `glPixelStorei` 中不影响我们上传路径的项

**桶 C：显式拒绝（fail loudly，不静默）**

- GL 3.x+ 核心：VAO、`glDraw*Instanced`、MRT、几何/曲面细分/计算着色器、GLSL 1.30+
- `glRenderMode(GL_SELECT / GL_FEEDBACK)` —— vanilla 用 Java 射线检测，不走这条路
- 累积缓冲 `glAccum`、求值器 `glMap*`/`glEvalMesh`（NURBS）、卷积/直方图/像素传输
- 任何现有渲染模组的接管点

#### 三个由此产生的硬成本

**1. FFP → shader 生成（最大一块）**

固定功能管线在 Vulkan 里不存在，必须**生成**等价 shader。需要覆盖：
光照（多光源 × 材质 × 双面）、纹理环境（含 combine）、雾（linear/exp/exp2）、
alpha 测试、裁剪面、shade model、双面光照。

参考量级：Actinium 的 `glsm` FFP 生成器约 **3317 行**。我们**不依赖**它的类
（渲染模组明确不支持），但可以从它的实现思路受益。

**2. 立即模式 + 显示列表 ⇒ 需要一层命令记录器**

`glBegin/glEnd` 要累积、`glEnd` 时 flush；显示列表要把一串 GL 调用**记录成命令流**
再重放。两者叠加意味着垫片内部不能是「直接映射到 Vulkan」，而要有
**GL 命令记录/回放层**。这是本契约最容易被低估的部分。

**3. 无 VAO ⇒ vertex layout → PSO 缓存**

GL 2.1 没有 VAO，顶点格式就是「当前 attrib 状态」，每次绘制都可能不同。
Vulkan 的 vertex input 是 PSO 静态状态（`VK_EXT_vertex_input_dynamic_state` 不是 core）。
所以必须按 layout 哈希缓存 PSO——**PSO 数量由顶点格式多样性决定，不是由材质决定**。

#### 这条契约顺带解决的旧问题

| 旧问题 | 新答案 |
|---|---|
| §8.4 原来的「对齐 Actinium 174 文件基础设施，还是另立最小面」 | **最小面**。消费者是老模组，不需要 `glsm/backend` 那套绑定抽象 |
| §3.3 的「节点内切 FBO 要结束/重开渲染实例」 | **大幅缩小**。GL 2.1 下没有任意 FBO 切换，触发点从「任意模组」收敛到「vanilla 自己的 framebuffer 路径」 |
| §6 的六项功能 | **全部不受影响**。它们在后端内部，不经过垫片 |

#### 先例：Zink

这是已经被解决过的问题。**Mesa 的 Zink**（OpenGL on Vulkan）做的就是同一件事，
且明确处理了 FFP、立即模式、显示列表、GLSL 版本映射。它的设计可以作为对照，
不需要我们从头推演。

#### 交付物：显式拒绝

不支持要**说出来**。启动时检测到已知渲染模组 → 明确报错并说明原因，
而不是让它们半工作后产生无法诊断的画面错误。这与 §2-6「不得静默降级」一致。

### 8.4.1 【已定】vanilla 自身路径：分界在 `GlStateManager`，不在 `GL11`

§8.4 只回答了「第三方老模组怎么办」，没有回答 **vanilla 自己的 GL 调用**怎么办。
而后者才是 M4「接真实数据」真正卡住的地方（`Display.create` 里 `drawable` 为 null）。

#### 实测事实（patched 源码，1.12.2）

| 平面 | 数量 |
|---|---|
| `GlStateManager.*` 调用点 | **2794**，分布在 **227** 个文件 |
| 绕过它的直接 GL | **60**（56 在 `OpenGlHelper`，4 在 `TileEntityRendererDispatcher`） |
| `GlStateManager` 自身 | **122** 个 `public static` 方法 / 1430 行 / 17 个状态分组内部类 |
| 立即模式 `glBegin` | **2**，都在 `FontRenderer` |
| 顶点上传终点 `glBufferData` | **3**（`VertexBuffer` × 1、`OpenGlHelper` × 2） |
| 显示列表 | **3**（`RenderGlobal` 的 sky / star / sky2） |
| `glLight` / `glMaterial` / `glFog` | **10**（`RenderHelper` 8、`EntityRenderer` 2） |
| 后处理 GLSL program | ~20 |
| `Tessellator.getInstance` | 99 |
| `Framebuffer` 使用点 | ~10 |

两个数字决定了整个结论：

1. **vanilla 已经把 GL 收敛到一个漏斗。** 改调用点是 2794 处 / 227 文件；改漏斗底部是
   122 个方法 / 1 个文件。**差 23 倍。**
2. **那个漏斗已经是状态机，不是薄转发层。**
   ```java
   public static void depthMask(boolean flagIn) {
       if (flagIn != depthState.maskEnabled) { depthState.maskEnabled = flagIn; GL11.glDepthMask(flagIn); }
   }
   ```
   具名状态 + 只在变化时下发——**这恰好就是 Vulkan 要的形状**。

#### 四桶

**A. 接管（垫不了）**——语义前提是「GL 上下文存在」

`Display.create/update`、`PixelFormat`、`ContextCapabilities`、`OpenGlHelper.initializeTextures`。
判据：垫它 = 让代码「以为」有上下文，然后更晚、更难诊断地失败。
Cleanroom 已有的 1409 行 `Display` 重写属于这一桶，M1 漏掉的 `drawable.makeCurrent()`
也属于这一桶。

**B. 在 `GlStateManager` 上原生实现**——不垫 GL API，换底部实现

| 子类 | 内容 | Vulkan 侧 |
|---|---|---|
| 状态 | blend / depth / cull / colorMask / stencil / viewport / scissor / polygonOffset（约 542 处） | 动态状态（§4.7），**零 shader 成本** |
| 开关 | lighting / alphaTest / rescaleNormal / shadeModel / texture2D / colorMaterial / texGen | **pipeline permutation key** |
| 参数 | `glLight` / `glMaterial` / `glTexEnvi` / `glFog` | uniform / push constant |
| 矩阵 | translate 275 + rotate 261 + push 185 + pop 184 + scale 140 + matrixMode 84 + loadIdentity 37 ≈ **1166 处** | CPU 栈 + push constant，**调用点一处不用改** |

**C. 真该改写**——GL 的形状承载了错误的结构

| | 规模 | 为什么 |
|---|---|---|
| 顶点上传终点 | 全游戏 **3 处** | 垫它 = 用 GL API 搬顶点再在另一侧拆一遍。terrain 那条已做（`ChunkMeshCapture`） |
| 显示列表 | **3 处** | 静态几何，应烘成 VBO 一次，不是录制命令流重放 |
| `Framebuffer` | ~10 处 | 垫它 = 造假 FBO 层；应成为渲染图 attachment |
| 后处理 GLSL | ~20 program | **未定**——这是真 GLSL→SPIR-V |

**D. 留给 §8.4 垫片**——老模组的 raw `GL11.*`、任意 FBO、GLSL 1.30+。
注意 vanilla 几乎不用立即模式（`glBegin` 只有 2 处），所以 §8.4 桶 A 里的
「立即模式 / 显示列表 / 命令记录器」**主要成本在模组侧，不在 vanilla 侧**。

#### 关键区分：permutation，不是生成

| | vanilla 的 FFP | 老模组的 raw GL |
|---|---|---|
| 用法集合 | **闭合**：`glLight`/`glMaterial`/`glFog` 只有 10 处，`glTexEnvi` 全是同一套 combine 样板 | **开放**：任意组合 |
| 技术 | **permutation**：从有限集合选预编译变体 | **生成**：从命令流生成 GLSL |
| 成本 | 几十个 pipeline 变体 | 3317 行量级生成器（§8.4，Zink 先例） |

vanilla 是**一个固定程序**，它的 FFP 用法就是它自己的源码，可以枚举。模组不是。
⇒ 两个不同的消费者，需要两个不同的东西：

- `GlStateManager` → **状态 → pipeline key 映射器**（无需命令记录，无需 shader 生成）
- `ShimGl` → **GL 2.1 命令记录器 + FFP 生成器**（§8.4）

这和 §3.3「阶段钩子是闭合集合、渲染图是开放集合」是同一条道理。

#### 为什么两个极端都不行

- **全垫**（连 `Display` / `Framebuffer` 都垫）= 在 Vulkan 上重建一个通用 GL，即 Zink。
  代价是 FFP 生成 + 命令记录 + PSO 爆炸，而产出里**没有我们真正要的东西：结构**
  （阶段、pass、附件）。GL API 恰好把结构抹掉了。
- **全改** = 改 2794 个调用点 / 227 个文件，等于重写 MC 所有渲染器（实体、GUI、粒子、
  方块实体、字体）。而它们**本来就已经过 `GlStateManager` 了**，改它们不产生任何结构收益。

#### 结论

> **垫片的边界画在 `GlStateManager`，不画在 `GL11`。**
> `GL11` 那一层的垫片是给老模组的兼容层，不是我们自己的后端路径。

#### 顺带：`pushAttrib` / `popAttrib`

vanilla 只有 4 处（`EntityRenderer` 2、`RenderItemFrame` 2），且都是 `glPushAttrib(8256)`
（`GL_ENABLE_BIT | GL_LIGHTING_BIT`）。属性栈没有 Vulkan 对应物，但它只在
`GlStateManager` 的具名状态上操作 ⇒ **在 B 桶实现为状态快照的 push/pop**，不需要
GL 命令记录。

#### 实测：启动链被逐层剥开（M4 第三步）

把客户端真跑起来，每一次崩溃都定位下一处 A 桶。已修的与待修的：

| # | 崩溃点 | 属于 | 状态 |
|---|---|---|---|
| 1 | `Display.create` → `drawable.makeCurrent()` NPE | A | **已修** |
| 2 | `OpenGlHelper.initializeTextures` → `GL11.glGetString(GL_VENDOR)` **SIGSEGV** | A | **已修** |
| 3 | `GLContext.<clinit>` → `new ContextCapabilities()` → `GL.getCapabilities()` 抛 `No GLCapabilities instance` | A | **已修** |
| 4 | `new Framebuffer(...)` → `createBindFramebuffer` → `GlStateManager.enableDepth()` → `GL11.glEnable` **SIGSEGV** | C + B | **已修**（§8.4.2 委派） |
| 5 | 异常已抛出，但**崩溃报告自己**调 `SplashProgress → GL11.glGetString` → **SIGSEGV**，可读的栈变成 core dump | A | **已修** |
| 6 | `SplashProgress.start` → `new SharedDrawable(...)` → `drawable.createSharedContext()` NPE | A | **已修**（跳过并记录） |
| 7 | `TextureUtil.<clinit>` → `DynamicTexture` → `glGenTextures` **SIGSEGV** | B | **已修** |
| 8 | `Minecraft.checkGLError` → `glGetError` **SIGSEGV** | B | **已修** |
| 9 | `enableTexture2D` → `BooleanState.setState` → `glEnable` **SIGSEGV** | B | **已修** |
| 10 | 带缓存的设置器（`depthFunc` / `blendFunc` / `colorMask` …） | B | **已修** |
| 11 | `getMaxTextureSize` 的 proxy-texture 技巧 | A | **已修**（设备能力） |
| 12 | `GLAllocation.glGenLists`（`RenderGlobal` 天空/星星列表） | C | **已修**（捕获重放） |
| 13 | `Display.setVSyncEnabled` → `SDL_GL_SetSwapInterval` 抛 SDL 异常 | A | **已修**（present mode） |
| 14 | `RenderGlobal.makeEntityOutlineShader` → `glCreateShader` **SIGSEGV** | A（能力声明错） | **已修**（不声明 2.1） |
| 15 | `CloudRenderer.reloadTextures` → `glGetTexLevelParameteri` **SIGSEGV** | B | **已修** |
| 16 | **初始化完成**，`Minecraft.runGameLoop` → `Framebuffer.bindFramebuffer` | 需要帧录制 | **已修**（帧意图） |
| 17 | `GuiMainMenu.drawPanorama` → `GLU.gluPerspective` → `glMultMatrixf` **SIGSEGV** | FFP 矩阵 | **已修**（走 `FrameState`） |
| 18 | `drawPanorama` → `depthMask` **SIGSEGV** | B | **已修** |
| 19 | `GlStateManager` 里剩余 35 个裸 GL 方法 | B/FFP | **已修**（真状态或指名抛错） |
| 20 | **帧循环运行，主菜单渲染到第一个四边形**（stride 24, mode 7, 4 顶点） | 需要 draw 录制 | **已修** |
| 21 | 全景模糊 `glCopyTexSubImage2D` | B | 跳过 + 记录一次 |
| 22 | `glBegin`（`FontRenderer`） | 立即模式 | **已修**（累加器） |
| 23 | `FramebufferVk.render` | — | 恒等，no-op |
| 24 | 索引绘制（GL_QUADS 无 Vulkan 对应） | — | **已修** |
| 25 | `glGetInteger`（snooper 遥测 / viewport） | B | viewport 真答，遥测 0 + 记录 |
| 26 | **主菜单在 Vulkan 后端上渲染**，0 段错误 / 0 验证层错误 | — | **达成** |

#### 实测：验证层抓到的两个真 bug

这一轮最有价值的信号不是崩溃，而是**27 条验证层错误**：

1. `VUID-vkCmdBindVertexBuffers-bindingCount-arraylength` —— `LongBuffer` 用
   `mallocLong(1).put(0, value).flip()` 构造，而 `put(int, long)` **不推进 position**，
   `flip()` 于是把 limit 置 0 ⇒ `bindingCount = 0` ⇒ **顶点缓冲根本没绑上**，之后每个 draw
   都是无效的。
2. `VUID-vkDestroyBuffer-buffer-00922` —— 单个共享缓冲在「等到当前 slot 的 fence」之后就释放，
   而另一个 slot 可能还在读它。改成一 slot 一份。

两条都是**代码读不出来、验证层一眼指出**的错。这也是为什么验证层要在开发分支默认开启。

第 19 条的做法值得记：把 `GlStateManager` 里所有残余 GL 调用分成两类——
**能做成真状态的**（`depthMask`、`color`）就做；**属于 FFP / 立即模式 / 顶点数组 / 查询**的
一律 `throw FrameStates.unimplemented(操作名, 需要什么)`。这比留着让 native 段错误好一个量级：
错误信息直接就是工作项。至此 `GlStateManager` 里**没有任何未守卫的 GL 调用**（审计脚本可复核）。

第 14 条是 §8.4.1 那条警告的实证：**能力表声明 2.1 使 vanilla 在启动期就去建
entity_outline 程序**，于是 `glCreateShader` 段错误。修法不是垫 shader，而是**让能力表
只说已实现的东西**——`OpenGL20/21` 与 ARB shader 全部置 false，vanilla 于是跳过这些路径
（全游戏每一处 `ShaderLinkHelper` 都在 `shadersSupported` 门内，不会 NPE）。
代价是可见且有日志的：无实体描边、无后处理。**GLSL 1.20 → SPIR-V 需要运行期编译器，
§8.2 只定下了分发格式，那是独立里程碑。**

第 5 条值得单独记住：**崩溃报告路径本身不能碰 GL。** 一个后端在启动期抛出的每个异常都要经过
`CrashReport.populateEnvironment`，而那里面原本有一次 `glGetString`。不修它的话，Vulkan 后端
之后**每一次**失败都会被伪装成一个 native 崩溃——诊断成本从「读一个栈」变成「读 core dump」。

第 6 条：加载画面。**初版这里写错了**，记下来。

当时写的是「共享 GL 上下文是非 GL 后端唯一给不出的东西」。**错。** Vulkan 不绑定线程，
多线程录命令缓冲比 GL **容易**。给不出的是「GL 共享上下文」——那是 `SplashProgress`
这个**实现**要的东西，不是渲染要的东西。

实测 `SplashProgress` 的形状：**24 处裸 GL 1.x**（`glBegin/glEnd`、`glOrtho`、
`glMatrixMode`、`glLoadIdentity`、`glVertex2f`、`glGenTextures`、`glFlush`），
外加 `SharedDrawable` + `makeCurrent`/`releaseContext` 的双线程上下文切换。
**那个双线程舞蹈存在的唯一原因是 GL 每线程只有一个 current context。**

⇒ 它属于 §8.4 的「裸 GL / 立即模式 / FFP」桶，而那一整条路径已明确推迟。
所以跳过是**依赖顺序**的结论，不是能力结论，而且**不阻塞任何东西**（主线程照常加载）。

Vulkan 侧唯一的真实额外成本是「两个线程向同一个 swapchain 呈现」需要显式同步
（GL 把这个藏起来了）。而且这大概不需要——加载线程可以只录制，由主循环呈现。

**便宜的诚实替代**：进度写进窗口标题（`SDL_SetWindowTitle`），零 GPU 工作但真的给了反馈。
比「黑窗且不知道在干嘛」好，且不依赖任何被推迟的东西。**未实施。**

第 2 条是个必须记住的形状：**LWJGL 的 native `gl*` 在无上下文时不抛异常，它直接段错误。**
所以「非 GL 后端下不得调用真实 GL」不是纪律问题，是进程存活问题。

第 4 条说明 B 桶和 C 桶在启动期是**交织**的：`Framebuffer` 要先建附件（C），而它
顺带做的 `enableDepth()` 又要 B 桶先把「不调 GL 的状态层」立起来。两者不能分开做。

#### 实测：`ShimGl` 的 RETARGET 平面够不到 vanilla

这是一个真实的接缝缺口，由实验发现而非阅读得出。

`Lwjgl2CompatTransformer.transform` 在进入重写前有两道门：

```java
if (!ConstantPoolRefs.mentionsLwjgl(basicClass) || !ConstantPoolRefs.hasLwjgl2MemberRef(basicClass)) {
    return basicClass;   // ← 这里直接返回，垫片 retarget 也就不会发生
}
```

而 `hasLwjgl2MemberRef` 要求类里引用了 `RewriteTableIndex.isRewritable(...)` 认可的
**LWJGL2 成员**。vanilla 打过 Cleanroom 的 patch 之后调的是 LWJGL3 名字
（`glLightfv` / `glGetFloatv` / …），**不在重写表里** ⇒ 返回 `false` ⇒ `GlStateManager`
被整个跳过。

⇒ 结论：**当前的 RETARGET 只覆盖「还没迁移到 LWJGL3 的模组类」，不覆盖 vanilla。**
两个平面被同一个 `hasLwjgl2MemberRef` 门绑在了一起，而它们本该是两个独立关注点：

| 平面 | 关心什么 | 应该由什么门控制 |
|---|---|---|
| LWJGL2→3 迁移 | 这个类的调用能不能被表重写 | `hasLwjgl2MemberRef` |
| 垫片 retarget | 这个类调不调 `GL11`/`GL20`，后端是不是非 GL | **`selection` 非空 + 类里出现 fromOwner** |

**这个「缺口」其实是正确的**——见 §8.4.2：vanilla 不该走 transform。它只说明了一件事，
**transform 平面不是通往 vanilla 的路**，而它本来也不该是。

### 8.4.2 【已定】机制：vanilla 走显式接口，模组走 transform

#### 上一版这里写错了什么

§8.4.1 的初版提出「保留 `GlStateManager` 的方法体，只把底部的 `GL11.glXxx` 换成一个 sink」。
**那是错的**，而且是同一种错的上一层重复。

判据很简单：**Vulkan 实现收到的是什么词汇？**

```java
// 叶子替换：sink 收到的是 GL 的词汇
sink.glTexEnvi(8960, 8704, 34165);     // 它必须知道 8960=GL_TEXTURE0、8704=TEXTURE_ENV、34165=INTERPOLATE

// 接口替换：Vulkan 实现收到的是 vanilla 的词汇
impl.texEnvCombine(TextureUnit.ZERO, CombineMode.INTERPOLATE);
```

前者要求 Vulkan 实现**懂 GL**，那就是在 Vulkan 上重建一个 GL —— 正是 §8.4 里反对的 Zink，
只是往上挪了一层。后者不需要。**所以分界线不是「在哪一层截」，而是「接口的契约是谁的」。**

#### 三个候选机制

| 机制 | 做法 | 判断 |
|---|---|---|
| A. 整类替换 | transformer 把 `GlStateManager` 换成 `GlStateManagerVk` | ❌ 无编译期校验：第二份类靠手写维护，漏一个方法没有任何提示。且不可读、不可下断点、不可单元测试 |
| B. 每个方法头 hook 后转发 | 方法头注入，命中则返回 | ❌ hook 不命中时**原方法体照样跑 GL**，正是要避免的崩溃；且 122 个注入点比 122 行委托更重 |
| C. 接口 + 两份实现，类身份不变 | `GlStateManager` 的 122 个方法体改成 `return IMPL.xxx(...)` | ✅ **采纳** |

#### 为什么 C 赢

先把代价说清：**A 的优势是零源码改动，C 的代价是 122 处机械改写。** 这不是免费的。
C 买回来的是：

- **接口就是清单。** 122 个方法由编译器强制覆盖。A 和 B 漏掉一个方法不会有任何提示，
  漂移是静默的——而这类静默漂移在这个项目里已经出现过（`TerrainVertexFormat` 的类型假设）。
- **可单元测试。** `GlStateManagerVk` 是普通类，状态 → pipeline key 的映射可以直接断言，
  不需要起客户端、不需要类加载。A/B 都是 load-time 变形，测试不到。
- **未实现的方法显式抛错并指名调用方**，而叶子替换会让每个未实现的方法**静默**路由到
  一个半成品 GL——两种失败模式的可诊断性差一个数量级。
- `GlStateManagerGl` 是**今天的代码原样搬过去**，零新逻辑；`GlStateManagerVk` 是本来就要写的
  那部分，只是现在按 vanilla 的词汇写。

**类身份在这里不是理由。** 注入这三个类的模组按 §8.4 已被显式拒绝，所以不需要为它们保留
任何兼容性——去掉这个约束反而让 C 更简单。

#### 于是 transform 平面回到它本来该管的事

| 谁 | 机制 | 理由 |
|---|---|---|
| **vanilla 类**（我们有源码，已经在打 patch） | **显式接口 + 两份实现** | 无 ASM，可读，可测 |
| **模组类**（我们没有源码） | transform / RETARGET → `ShimGl`（§8.4） | 唯一可行的路 |

这也让 §8.4.1 里那个「RETARGET 够不到 vanilla」的发现**不再是一个缺口**：
显式接口之后，垫片根本不需要够到 vanilla。

**模组的裸 GL 调用仍在，但现在不管。** 它们是 §8.4 的完整交付物（命令记录 + FFP 生成），
与「让 vanilla 跑在 Vulkan 上」是两件事，不该同时做。当前唯一的目标是后者。

#### 四个漏斗，不是 83 个方法

实测下来 vanilla 的 GL 出口只有四处，其余全是调用方：

| 漏斗 | 规模 | 覆盖什么 |
|---|---|---|
| `GlStateManager` | 122 个 `public static` 方法 | 状态、矩阵、纹理、FFP、立即模式、顶点数组、显示列表 |
| `VertexBuffer` | **53 行，6 处 GL** | **vanilla 全部非地形几何**（GUI / 实体 / 粒子 / 方块实体）——`Tessellator` 持有它 |
| `Framebuffer` | ~330 行 | 渲染目标 |
| `OpenGlHelper` | 56 处 GL | shader 对象 + buffer 对象的 API 转发 |

`VertexBuffer` 这个数字值得单独看：**整个非地形绘制路径只有一个 53 行的类**。

#### `GlStateManager` 的 122 个方法按成本分

| 组 | 数量 | Vulkan 侧 |
|---|---|---|
| 纯 CPU（矩阵栈、颜色累积） | ~16 | **零后端调用** |
| 状态与开关（blend/depth/cull/colorMask/logicOp/polygonOffset/alphaFunc/shadeModel） | ~53 | 动态状态 + permutation key，每个一行 |
| 纹理 | 11 | **真活**：image + sampler |
| FFP（light/texenv/fog/texGen/colorMaterial） | ~15 | permutation key / uniform |
| 立即模式 | ~5 | vanilla 只用 **2** 处（`FontRenderer`） |
| 顶点数组 + `glDrawArrays` | ~10 | layout → PSO 缓存 |
| 显示列表 | 5 | vanilla 只用 **3** 处（sky/star） |
| attrib 栈 / 查询 | ~9 | 自己维护 |

⇒ **真正难的只有 ~26 个**（纹理 11 + FFP 15）。其余要么纯 CPU，要么一行状态写入。
而这 26 个是**无论走哪条路都要写的**，区别只在于按谁的词汇写。

#### 顺序由启动链决定（§8.4.1 实测表）

1. **`GlStateManager` 接口 + 状态/开关/矩阵/颜色实现**（~85 个方法，全易）→
   `Framebuffer.createBindFramebuffer` 里的 `enableDepth()` 不再崩
2. **`Framebuffer` 接口 + Vulkan 渲染目标**
3. **`VertexBuffer` 接口 + Vulkan 缓冲** → 2D/GUI 绘制可通
4. **纹理**（`glTexImage2D` / `glTexSubImage2D` / sampler）→ 图集与字体
5. 地形已在（M4）

---

### 8.5 `ShimEnvironment.backendId()` 在 coremod 阶段能否确定

实测表明不确定也没关系（失败限制在调用点，类仍能加载）。若要 coremod 阶段就选规则集，
需要把「乐观选择 + 运行期降级」写进契约。

### 8.6 `phaseId` 粒度

一一映射可让 Actinium 侧最小改动；更粗（只留 `terrain` / `entities`）则 Actinium 重构量大。

### 8.7 与 Kirino 的关系

不是「谁先」，而是 Vulkan 后端是否复用 Kirino 那约 10 kLOC 的 GL 无关数据模型
（`ecs` / `schemata` / `engine/resource`）。未定。

### 8.8 CPU↔GPU 结构体对齐

范式文档明确警告：

> "It's important to match structure layouts between CPU-side and GPU-side... layouts might look
> the same but will actually be different due to how shading languages align struct members."

解法：`VK_EXT_scalar_block_layout`（Vulkan 1.2 core，可选特性）或手工 padding。
**这条对我们是硬需求**——`MatrixView` 已经在用 joml 的 `Matrix4f`，列主序与
Slang 的 `defaultMatrixLayoutMode` 必须对齐（范式里显式设了 `COLUMN_MAJOR`）。

### 8.9 渲染图的重建时机

图的「冻结」是单向的（同 §2-5），但下面三件事会要求重建：

- 资源包 / 光影包切换（§6.4、§6.5）
- 模组热重载（开发期）
- 窗口 resize 导致的图附件尺寸变化（若附件以固定尺寸描述）

重建时 GPU 可能正在飞行中。范式文档的做法是 `vkDeviceWaitIdle` 后重建全部
（swapchain 重建就是这么做的），但那会**卡帧**。

**未定**：是否值得做「双图 + 帧边界切换」（类似双缓冲），还是接受重建时的一次卡顿。
开发期肯定接受，发行期要看资源包切换的实际频率。

### 8.10 图是否按视图分层

Bevy 有 per-view 子图（`Core3d` 的 `ViewNode`）。MC 也有多个「视图」：
世界、GUI、可能还有 LOD 的远景区。

**未定**：是做成「一张图 + 多个视图子图」，还是「一张线性图，视图只是节点的附件选择」。
后者简单得多，但 GUI 与世界混在同一张图里会让模组插入点变模糊。

---

## 9. 建议的里程碑

```
M0  ✅ 门后机制(8.1) + 依赖落地(lwjgl-vulkan / lwjgl-vma, §7.1)
    → 注册 stub（probe=false）、具名优先级常量、启动日志
    → 验收：-D 指名能被拒、回落 vanilla、测试全绿
M1  ✅ instance / device / swapchain / depth / clear
    → 验收：Vulkan 窗口出现，背景色正确，resize 不崩，validation 零报错
M2  ✅ §3.3 渲染图的节点注册表（= 原 PhaseRegistry）+ 8 个空节点
    → 验收：8 个阶段全部有 provider，拓扑排序无环，画面错误但无崩溃
M3  ✅ terrain.layer 真画（占位几何体）
    → 第一个有意义的画面
M4  ✅ 真实世界与地形层闭环（ChunkMeshCapture / 视锥体剔除 / 光照管线 / 修复天空与 GUI）
    → 真实世界与 GUI 完整渲染
M5  ✅ 地形缓冲区池化与间接绘制（Arena Sub-allocation / IID + MDI / 顶点压缩）
    → 阶段一（✅ 已完成）：Arena 子分配池化 + 静态共享四边形索引缓冲 + 槽位延迟回收 + 增量流式上传，消除 250ms 全量重打包与 vkDeviceWaitIdle 停机
    → 阶段二（✅ 已完成）：IID (Instanced Indirect Draw) 与 MDI (Multi-Draw Indirect) 批量单指令调度：基于 BDA (Buffer Device Address) 与 Push Constants 传递区块世界坐标表，VkDrawIndexedIndirectCommand 通过 firstInstance 注入区块索引，实现每 Slab 仅 1 条 vkCmdDrawIndexedIndirect 指令调度全部区块绘制，将百余次 CPU 绘制调度与矩阵计算压缩为单次间接多重绘制
    → 阶段三（✅ 已完成）：透明顶点压缩 (Transparent Compaction) 将 28 字节 BLOCK 压至 16 字节缓存行对齐紧凑格式（VK_FORMAT_R16G16B16A16_SINT + VK_FORMAT_R16G16_UNORM + VK_FORMAT_R8G8B8A8_UNORM），单四边形 64 字节 1:1 匹配 GPU 缓存行；外部保持 28 字节 BLOCK 格式确保模组完全兼容；双 64 位宽存零分配快速转码，节省 ~43% 显存与传输带宽
```

**M2 已完成**（`net.minecraftforge.client.render.graph`）：

| 类 | 职责 |
|---|---|
| `RenderGraph` | label + 边、Kahn 拓扑排序、环检测、冻结、完整性校验 |
| `RenderGraphNode` | `label()` / `priority()` / `run(ctx)` |
| `RenderGraphContext` | 帧宽高 + `nativeContext()`（故意小，不预先加填不上的字段） |
| `RenderPhases` | 8 个稳定字符串 + `REQUIRED` + `dependencies()` |
| `RenderGraphs` | 进程级单例 |

契约要点：

- **锚点是声明式**：`insertBefore` / `insertAfter` 只给「之前/之后」，**不给「紧邻」**。
  多个节点可以锚在同一处，由 `priority()` 再按 label 决定——**注册顺序不决定任何事**
  （1.12.2 的模组加载顺序不完全可控）。
- **`replace` 必须沿用被替换的 label**。这样别的模组声明的 `insertAfter(terrain.layer, …)`
  会自动指向新节点，不存在「悬空锚点」这个状态。
- **冻结是一次性的，校验全在冻结时做**：悬空锚点、环、缺失的必需阶段——都是启动期
  带名字的报错，不是帧中途的崩溃。
- **8 个阶段出厂就是 no-op 节点**，不是「什么都没有」。因为 Vulkan 窗口里没有逐阶段回退，
  「谁负责这个阶段」从第一帧起就必须有答案，`replace` 才是实现阶段的入口。

**测试暴露的一个设计缺口**：8 个必需阶段之间原本没有边，拓扑排序退化成按标签字典序，
`block_entities` 排到了第一位——确定性，但是错的。已把真实依赖写成边
（`mark_dirty`/`reload` → `setup` → `query`/`sky` → `layer` → `block_entities` → `entities`），
顺序因此来自事实而非声明位置。

实测（真机，Wayland + RADV，validation 开启）：

```
Render graph: [terrain.mark_dirty, terrain.reload, terrain.setup, terrain.query,
               terrain.sky, terrain.layer, block_entities, entities]
presented 180 frames, final extent 500x400
```

零 validation 报错。495 测试全绿。

**M0 已完成**（`vulkan-backend` 分支）：

| 交付 | 位置 |
|---|---|
| `VulkanBackend` stub（probe 恒 `false`） | `client/render/vulkan/VulkanBackend.java` |
| 具名优先级 `BRANCH_DEVELOPMENT_PRIORITY = 1_000_000` | 同上 |
| 启动日志（可用则 WARN，不可用则 INFO） | `install()` |
| 安装点（纯客户端、早于建窗） | `FMLLaunchHandler.setupClient()` |
| 依赖 | `lwjgl3Api` + `compileOnly`（见 §7.1） |
| 测试 | `VulkanBackendTest`，5 个 |

验收结果：471 测试全绿（原 466 + 新 5），`verifyNoStaleLibraries` 通过。

**M1 已完成**：`isAvailable()` 现在是真实探测（loader 版本 + `VK_KHR_surface` + 有 1.4 设备的图形队列），
并带缓存。本机实测：`Vulkan is available: AMD Radeon RX 6650 XT (RADV NAVI23) (1.4.354)`。

| 交付 | 位置 |
|---|---|
| 探测（loader/扩展/设备/队列族） | `VulkanProbe` |
| instance + surface + device + queue | `VulkanContext` |
| swapchain + 深度 + 2 帧同步 | `VulkanSwapchain` |
| 校验层消息转日志 | `VulkanDebugMessenger` |
| `RenderBackend.present()` 帧钩子 | `client/render/RenderBackend.java` |
| `Display` 三处接线 | `createWindow` / `swapBuffers` / `destroy` |

实测（真机，Wayland + RADV）：
- 120 帧连续 present，无崩溃
- swapchain 960x540 / 4 images / B8G8R8A8_SRGB / FIFO / D32_SFLOAT_S8_UINT
- 连续 resize 800x600 → 1024x768 → 400x300 → 1280x720 全部正确重建

**M1 踩到并修掉的三个真问题**（都不是文档能预料的）：

1. **`lwjgl-vulkan` 只在 `compileOnly` 里 → 运行期 `NoClassDefFoundError: VK11`。**
   M0 时没有代码所以无害，M1 必须有。已改为进 `installer` + `runtimeOnly`，
   并按 §7.2 处理 natives 不对称（`lwjgl-vulkan` 只给 macOS，`lwjgl-vma` 全平台）。
2. **LWJGL 的 `MemoryStack` 默认 64KB，枚举设备扩展时爆栈。**
   改为 `VkExtensionProperties.malloc(n)` + `free()`，不受栈大小影响。
3. **Wayland 下 resize 不会产生 `VK_ERROR_OUT_OF_DATE_KHR`。**
   只靠驱动上报就永远不重建，swapchain 会卡在初始尺寸。改为每帧比对窗口像素尺寸与 extent。

**M3 已完成**：

| 交付 | 位置 |
|---|---|
| GLSL 源 + 已编译 `.spv`（提交进仓库） | `assets/outlands/shaders/terrain.{vert,frag,vert.spv,frag.spv}` |
| 构建期编译工具（`lwjgl-shaderc`，不进运行时） | `src/test/.../shader/ShaderCompiler.java` + `compileShaders` 任务 |
| SPIR-V 加载（唯一 shader 入口） | `VulkanShader` |
| 图形管线（固定顶点格式 + push constant） | `VulkanPipeline` |
| 顶点/索引缓冲（一次性、host-visible） | `VulkanBuffer` |
| `terrain.layer` 节点本体 | `VulkanTerrainLayer` |

关键决定：**运行期只有「消费 SPIR-V」一条路径**。loader 自己的 shader 也是离线编译后提交，
和 shader pack 走同一条路——不做第二条 GLSL 运行期编译路径。

**M3 验收**：真机截图确认立方体三面可见、光照正确、深度测试工作、winding 正确，
validation 零报错，240 帧 + 两次 resize。495 测试全绿。

**M3 未做、留给后续的**：真实区块数据（替换 `geometry()`）、纹理（需要描述符）、
VMA（现在是一次性裸分配）、相机跟随（现在是固定机位）。

**validation 已验收**（`vulkan-validationlayers 1.4.363.0-1` 装上后重跑）：

校验层一开就报出 **4 个真 VUID**，全部修掉后 180 帧 + 两次 resize **零 error / 零 warning**：

| VUID | 问题 | 修法 |
|---|---|---|
| `dynamicRendering-06446` | 动态渲染特性根本没启用 | 见下 |
| `synchronization2-03848` / `-03866` / `-07793` / `-07794` | 同上 | 见下 |
| `VkImageMemoryBarrier2-image-03320` | 深度屏障的 `aspectMask` 只给了 depth | 屏障用 `DEPTH\|STENCIL`（视图仍只 depth） |

**根因是一个 `.address()`**：`VkDeviceCreateInfo.pNext(enable14)` 传的是对象不是地址，
于是整条特性链被驱动忽略——设备以「什么特性都没启用」创建。同一个错误此前也毁掉了
特性查询（所以早期跑出「所有特性都不支持」的假象）。这类错误的特征是
**不报错、不崩溃，只是默默不生效**，只有校验层能抓到。

**M0 之前不写任何 Vulkan 代码。**
`M1` 的验收标准里「validation 零报错」是硬条件——范式文档反复强调，
没有 validation 的 Vulkan 代码在别的 GPU 上会崩。

**M4 已完成（真实世界与地形层闭环渲染）**：

| 交付 | 位置 |
|---|---|
| 真实区块网格捕获与安装 | `ChunkMeshCapture` + `VulkanBackend.installCaptures()` |
| 地形层分离绘制与视点对齐 | `RenderGlobal.patch` + `FrameStates.recordTerrainLayer()` |
| 地形视锥体剔除（Gribb-Hartmann） | `VulkanTerrainLayer.extractFrustumPlanes() / isBoxInFrustum()` |
| 半透明地形管线（Alpha 混合 + 深度只读） | `VulkanPipeline.PipelineState.translucentTerrain()` + `VulkanTerrainLayer` |
| GUI 光照泄漏隔离（按单元状态返回 null） | `DrawState.lightmapCoord()` |
| 物品栏 3D 方块标准解析光照 | `VertexConverter.applyNormalLighting()`（模型空间双光源解析向量） |
| 颜色通道字节序校正（消除正午天空泛红） | `DrawState.colorArgb()` |
| 实体法线归一化枚举映射（消除史莱姆外层缺失） | `Capability.NORMALIZE` (2977) |
| 状态矩阵零分配复用 | `FrameStateVk` 矩阵相乘与读取操作引入复用暂存缓冲 |

**M4 验收情况**：
- 真机进入游戏世界，地形、建筑、生物（村民/史莱姆）、天空、手部与物品栏均闭环渲染。
- 史莱姆半透明胶状外层恢复渲染。
- 正午天空蓝天白云恢复正常。
- GUI 背景在夜间/雨天不再受世界光照变暗，保持标准浅灰色。
- 物品栏 3D 方块光照方向与原版像素级一致（顶面 1.0、前左 0.75 浅亮、前右 0.44 阴影暗面）。

**M4 暴露的架构瓶颈与 M5 演进决议**：
实测发现当前单体全量重打包（`TerrainMeshPacker`）在动态世界中遇到严重的性能瓶颈：
1. **全量重打包风暴**：每 250ms 检测到有区块变动时，主线程将全部 5,190 个区块图层（136 万四边形，544 万顶点）全部从头到尾重建一次。
2. **堆垃圾与 GC 卡顿**：单次重打包产生 152MB 顶点 + 32MB 索引，主线程产生 ~2.2 GB/s 临时堆对象，导致 Young GC 频繁介入。
3. **GPU 停机等待**：重建前调用 `vkDeviceWaitIdle` 强行清空 GPU 队列，导致主线程 `root.display_update` 耗时飙升至 120~160ms，帧率跌至 20 FPS。

**M5 规划（地形缓冲池化与间接绘制）**：
- **Arena 分块增量缓冲池（Sub-allocation）**：预分配固定大小（如 128MB）的 Arena 显存池。单个区块发生变动时，仅通过 Staging 缓冲对变动的几十 KB 数据调用 `vkCmdCopyBuffer` 局部覆写，彻底消除全量打包与 `vkDeviceWaitIdle`。
- **IID + MDI（间接多重绘制）**：将每个区块绘制封装为 `VkDrawIndexedIndirectCommand`，利用 `firstInstance` 注入区块 ID，Shader 内通过 `gl_InstanceIndex` 查表读取世界坐标偏移，CPU 单条指令调度全部区块绘制。
- **透明顶点压缩（Transparent Ingestion）**：对外严格维持 28 字节 `DefaultVertexFormats.BLOCK` 确保所有 1.12.2 Forge 模组兼容；对内转码为 16 字节对齐的 GPU 紧凑格式，显存与带宽消耗减半。

---

## 11. 缺失能力实现计划：离屏渲染目标 + program 绘制路径

§8.2.1 把 shader 的**来源**问题解决了（原版 AOT 手写模块、模组 JIT），但对 shader **怎么画出去**没有回答。
本节规划剩下的两块，也就是灵光箭（`entity_outline`）跑起来、能力表能声明 GL 2.0/2.1 的全部前提。

### 11.1 灵光箭到底要什么

`RenderGlobal.makeEntityOutlineShader` 建立 `ShaderGroup`，之后每帧执行
`shaders/post/entity_outline.json` 的四个 pass：

| 顺序 | program（`shaders/program/*.json`） | 目标 | 采样 | 说明 |
|---|---|---|---|---|
| 0 | （实体本身） | `final` | — | 发光实体以 `setRenderOutlines(true)` 画进目标 |
| 1 | `entity_outline` = `sobel.vsh`+`entity_sobel.fsh` | `swap` | `final` | alpha 边检测，输出 alpha = 四邻差之和 |
| 2 | `blur`，`BlurDir=(1,0)` | `final` | `swap` | 横向模糊 |
| 3 | `blur`，`BlurDir=(0,1)` | `swap` | `final` | 纵向模糊 |
| 4 | `blit` | `final` | `swap` | 合成回 `final` |
| 5 | （`framebufferRenderExt`） | 窗口 | `final` | 叠加到画面 |

注意第 1 趟用的是 `entity_sobel.fsh` 而**不是** `outline.fsh`：后者属于 `shaders/post/outline.json`（另一套效果）。
两者都已手写为 Vulkan GLSL 并 AOT 编译（§8.2.1），但只有 `entity_sobel` 在这条链上。

也就是说：**2 张同尺寸离屏目标（都带深度）× 5 次目标切换 × 4 个 program 绘制 × 1 次合成**。
每帧还要重复一遍（`entityOutlinesRendered` 为真时）。

### 11.2 现状盘点

| 能力 | 现状 | 位置 |
|---|---|---|
| shader 对象模型（create/source/compile/link/uniform 按名） | ✅ 已可用 | `GlShaders` |
| 原版 program 的 SPIR-V（4 趟都在内） | ✅ 构建期 AOT | `ProgramShaderCompiler` |
| GLSL → SPIR-V（模组源） | ✅ 运行期 JIT | `GlslCompiler` + `GlslPreprocessor` |
| program → `VkPipeline` | ❌ 不存在：现有管线全部来自固定 shader（`terrain.*` / `stream.*`） | `VulkanPipeline` / `VulkanStream` |
| uniform block 上传 | ❌ `GlShaders.Program.blockBytes()` 只写内存，没有 UBO | `GlShaders` |
| program 作为绘制状态 | ❌ `DrawState` 无 program 字段；`glUseProgram` 只记了一个全局 | `DrawState` |
| 离屏目标（图像+深度） | ❌ `FramebufferVk` 只记 size/colour/filter，不分配 | `target/FramebufferVk` |
| 帧内切换目标 | ⚠️ 只有 `FramebufferCopy` 会 end/begin（`vkCmdEndRendering` + LOAD 重开） | `VulkanStream.replay` |
| 帧内 clear | ✅ `FrameDraws.Clear` → `vkCmdClearAttachments` | `VulkanStream` |
| 采样离屏图像 | ❌ `VulkanTextures` 只从 `TextureImage` 像素上传，没有"外部图像"入口 | `VulkanTextures` |
| 合成到屏幕 | ❌ `FramebufferVk.render` 是 no-op（建立在"只有一张图"的前提上） | `target/FramebufferVk` |
| 渲染实例抽象 | ⚠️ `FrameRenderTarget` 是 swapchain 私有实现，离屏目标实现不了 | `vulkan/FrameRenderTarget` |

### 11.3 四个形状决定

**D1 目标是「图像 + 深度 + 纹理句柄」，格式统一为 swapchain 格式。**
所有离屏目标都用 `swapchain.imageFormat()`（以及同一 depth 格式），于是**任何管线都能画进任何一个目标**，
不需要按格式缓存 PSO。代价是不支持 HDR 中间缓冲（暂不需要）。
目标自己持有图像、视图、深度与采样器，并把自己的颜色视图以**纹理句柄**注册进 `VulkanTextures`
（"外部图像"入口：不再从像素上传，而是直接指向已有 image view），这样 `glBindTexture(framebufferTexture)`
和 `ShaderGroup` 的 `addSamplerTexture` 都照旧成立。

**D2 切换目标是操作流里的一条记录，不是新的帧语义。**
`FrameDraws.Operation` 增加 `TargetBind(target, clearMask)`，录制时就是既有那套：
`vkCmdEndRendering` → 老目标 ATTACHMENT→SHADER_READ_ONLY 屏障、新目标 →ATTACHMENT 屏障 →
`beginRenderingInstance(新目标, CLEAR|LOAD)` → 重设 viewport/scissor。
这与 `FramebufferCopy` 已经用的 end/copy/begin 模式同构，所以不需要"多渲染实例"的新概念，只是把同一件事做完整。
目标布局只需 3 态（`UNDEFINED`/`ATTACHMENT_OPTIMAL`/`SHADER_READ_ONLY_OPTIMAL`），与 §4.3 的塌缩一致。

**D3 program 是绘制状态，快照进 `DrawState`；program 管线有自己的 layout。**
`glUseProgram` 记的是全局状态（GL 的 `glUseProgram` 不参与 `glPushAttrib`），所以 `DrawState` 快照时直接取
`GlShaders.currentProgram()`，与 `alphaTest`/`blend` 同一类字段。program 管线的 `VkPipelineLayout` 是新的一个：

| set | 内容 | 来源 |
|---|---|---|
| 0, binding 0 | uniform buffer（program 的 block） | `GlShaders.Program.blockBytes()`（每帧每 program 一段 ring） |
| 1, binding N | samplers | `SpirvReflection` 读到的 (set,binding) + `draw.state().texture()` 解析出的 image view |

顶点输入**从 program 声明的输入派生**：program 顶点阶段每个 `!output` 的 location，去查规范化顶点流
（§6.7.2 的四属性布局）里同 location 的属性，用它的格式与偏移；查不到就报错点名（不静默）。
这与 §8.4「无 VAO ⇒ vertex layout → PSO 缓存」是同一条，只是最小面：只支持规范化流里已有的 location。

**D4 合成走已有的 stream 路径，不新造 blit 路径。**
`Framebuffer.framebufferRenderExt` 在 GL 下就是"绑纹理 + Tessellator 画一个 `POSITION_TEX_COLOR` 四边形"。
所以 `FramebufferVk.render` 实现为：合成一个 4 顶点的规范化 `Draw`（uv 0..1、color 白、lightmap 白），
用调用时刻的状态（混合/颜色掩码/viewport/矩阵）快照，纹理 id 指向目标自己。
好处：混合因子、`colorMask(true,true,true,false)`、深度关闭这些语义全部由既有 `DrawState`+stream 管线承担，
不需要第二套合成管线。

### 11.4 六个 slice

每个 slice 都能独立提交、独立验收，且**不改变画面**（除非 slice 明确打开了某个效果）。

#### S1 目标对象化（大小：中）— ✅ 已完成

落地时定下的形状（与初版规划的两处差异写在下面）：

- `RenderTargets`（新，`render/target/`）：`Factory` 接口 + 安装/卸载。只提供"分配一个目标"，
  没有"登记窗口目标"这一步 —— 见下一条。
- `RenderTarget`（新）：`textureId()` / `width()` / `height()` / `useDepth()` / `resize()` / `setFilter()` / `close()`。
- `VulkanRenderTarget`（新）：颜色图（`COLOR_ATTACHMENT|SAMPLED|TRANSFER_SRC`）+ 深度图（`DEPTH_STENCIL_ATTACHMENT|SAMPLED`，
  aspect=DEPTH）+ 稳定纹理句柄；`currentLayout` 已开始跟踪，留给 S2 发屏障。
- `VulkanImage` 长出 `createColorAttachment` / `createDepth`：usage 与 view aspect 变成参数（原来硬编码成纹理）。
- `VulkanTextures` 增加 `createExternal/attachExternal/releaseExternal/hasExternal/renderTargetSampler`：
  目标是"外部图像"，按句柄解析成描述符集，不走像素上传。
- `FramebufferBackend` 增加 `textureId()`；`FramebufferVk` 从"只记状态"变成"持有 `RenderTarget`"，
  分窗内/窗外两种：窗口目标的 `bind/clear/render` 照旧（帧本来就在这张图上），离屏目标明确抛
  "缺 `TargetBind`，见 §11.4 S2"。

**差异 1：窗口目标用构造参数指定，不是事后登记。** 初版想的是 `Minecraft` 建完再 `markWindowTarget()`，
但 `Framebuffer` 的构造函数里就会 `createBindFramebuffer`，而 `framebufferTexture` 必须在那一刻就有值
（`ShaderManager` 直接读字段）。改成 `new Framebuffer(w, h, useDepth, windowTarget)`——多一个构造器，
`Minecraft.init` 传 `true`。比"事后标记 + 延迟分配"少一条状态机。

**差异 2：游戏里还有第二个整窗 framebuffer。** `LoadingScreenRenderer` 自己 `new Framebuffer(w, h, false)`：
清屏 → 画加载文字 → 全窗口 1:1 合成回去、无深度，所以离屏这层是恒等变换。它也传 `windowTarget=true`
（`LoadingScreenRenderer` 补丁里写了理由）。这条是实测撞出来的：S1 第一版把它当离屏，进世界时
`framebufferClear()` 直接抛了 `UnsupportedOperationException`，崩在 `Minecraft.loadWorld`。

游戏里 `new Framebuffer(` 只有四处：`Minecraft.init`（窗口）、`Minecraft.drawSplashScreen`（非 GL 路径不跑）、
`ShaderGroup.addFramebuffer`（真正的离屏，S2/S5）、`LoadingScreenRenderer`（窗口别名）。

- `setStencil(true)` 现在**抛异常**而不是记下来：给一个没有 stencil 附件的目标会让模组的遮罩静默失效。
- 验收：`FramebufferVkTest` 八个用例（窗口目标不分配、离屏分配一次、resize 复用手柄、delete 释放、
  无渲染器时报原因、无帧时报原因、离屏绑定报缺 S2、filter 生效）；
  `-Doutlands.render.selftest.targets=true` 在真机上分配/缩放/释放一个目标并打日志；主菜单与进世界均无变化。

#### S2+S3 帧内切换目标 + 合成（大小：中，核心）— ⏳ 实现完成，待视觉验收

S2 与 S3 合并实现：只有切换没有合成的话，画面只是"画进一张没人看的图"，无法验收。

**落地形状**（与初版规划的一处差异：合成几乎不用写代码）

- `FrameDraws.TargetBind(target, width, height, setViewport)`：目标用**纹理句柄**标识，`0` = 窗口图像。
  句柄而不是对象，是为了让操作流保持后端中立——GL 后端重放同一条流就是 `glBindFramebuffer`。
- `vulkan/RenderInstance`（新）：把"开/关一个渲染实例"从 swapchain 里提出来，窗口与离屏目标共用。
  视口/剪刀在每次开启时重设（重开的实例这两项是未定义的）。
- `FrameRenderTarget` 增加 `prepareForRendering` / `prepareForSampling`：进入目标前把颜色（和首次的深度）
  移到 attachment 布局，离开目标时（内容刚完成、仍在 attachment 布局）移到 sampled 布局。
- `VulkanRenderTarget` 实现上述接口；颜色 3 态布局（UNDEFINED/ATTACHMENT/SHADER_READ_ONLY）+ 深度一次性迁移。
- `VulkanRenderTargets` 维护 `纹理句柄 → 目标` 的注册表，重放时解析；目标释放时移除（否则会画到已释放内存）。
- `VulkanStream.replay` 记录"当前目标"，处理 `TargetBind`：结束实例 → 离开方转 sampled → 进入方转 attachment
  → 开新实例 → 清空视口/剪刀/管线/描述符缓存。
- `FramebufferVk.bind/unbind` 在离屏目标上记录 `TargetBind`；窗口目标仍走帧自身的 `targetDefault`。

**合成不用新代码。** 规划里 D4 说"合成走既有 stream 路径"，实现下来更彻底：
`Framebuffer` 的 `framebufferClear` / `framebufferRenderExt` / `bindFramebufferTexture` 在**非窗口**目标上
直接跑原版方法体（补丁只加 `backend.isWindow()` 判断）。它们调用的每一个 GL 入口都已经接在垫片上——
`bindFramebuffer`→`TargetBind`，`GlStateManager.clear`→`Clear` 操作，`bindTexture(framebufferTexture)`→
按句柄采样的普通绘制。于是"合成"就是一个普通的 `POSITION_TEX_COLOR` 四边形，不需要新的 blit 路径。

**实测撞出来的三个 bug**（都只有真机 + validation 层能发现）：

| 现象 | 原因 | 修法 |
|---|---|---|
| `pImageMemoryBarriers[1].image has depth/stencil format ... aspectMask does not include both` | 深度格式是 `D32_SFLOAT_S8_UINT`，设备未开 `separateDepthStencilLayouts`，迁移时只写了 `DEPTH` | barrier 的 aspect 用 `DEPTH\|STENCIL` |
| `vkCmdClearAttachments(): pRects[0].rect extent {854,480} is not contained in the render pass instance` | 帧内 clear 的矩形用了**窗口**尺寸，但此时绑定的是 256×256 的目标 | 矩形改用当前目标的 extent（剪刀框也按它的高度翻 y） |
| 关闭时 `vkDestroyImageView/image ... currently in use by VkCommandBuffer` | 目标在游戏侧释放（`deleteFramebuffer`/resize），与在飞帧无关 | 销毁前 `vkDeviceWaitIdle`（目标创建/销毁是低频事件；若将来变高频，改用 terrain arena 那套退役队列） |

**自检开关**：`-Doutlands.render.selftest.targets=composite`。每帧走一遍真实路径：建一个 256×256 带深度的
`Framebuffer`、清成红色、在目标左上画一个 96×96 蓝色方块（走普通顶点流）、再合成回窗口。
方块的位置同时是方向检查。另有 `-Doutlands.render.trace.ops=true` 打印一帧的操作流（一次性）。

#### 方向与绕序：两次实测才定下来的两件事

**R1 的答案：离屏目标的视口必须竖直反转。** 游戏的投影把"屏幕上方"放在 NDC `-1`，这正好是 Vulkan 的约定，
对窗口是对的（framebuffer 第 0 行就是屏幕顶行）。但离屏目标的图像是**被采样**的，而 GL 的纹理约定是
v=0 在底部 —— 原版每一个后处理着色器都这么假设（`sobel.vsh`/`blit.vsh` 结尾都是 `texCoord.y = 1.0 - y`）。
不反转的话图像上下颠倒：对称的边缘检测看不出问题，最后合成时表现为描边与实体错位。
实测对照：把同一个方块画进目标再合成，它落在合成后正方形的**左下**；直接画到窗口上的同一坐标落在窗口左上。
修法：`FrameRenderTarget.flipsVertically()`，离屏目标返回 true，`applyViewport` 用负高度（Vulkan 1.1 起的标准做法）。

**负高度会反转绕序。** 反转视口之后自检方块整个消失了——因为它被背面剔除吃掉：y 反转把逆时针变成了顺时针。
所以前表面必须是管线状态的一部分：`PipelineState.flippedWinding`，离屏目标用 `VK_FRONT_FACE_CLOCKWISE`。
这条对灵光箭是硬需求：实体是用剔除画的，绕序反了会把剪影整个剔掉。

**viewport/scissor 的 y 翻转基准是"当前目标"的高度，不是窗口。** 这一条最初写成窗口高度：256 高的视口
在 480 高的帧里被放到 y=224，于是在 256 高的目标里只露出 32 行——96×96 的方块出来是 96×32。

#### S2 帧内切换目标（原规划，保留作对照）

- `FrameDraws.TargetBind` + `TargetClear`（复用既有 `Clear` 的话要带目标）。
- `VulkanSwapchain.record` 里把当前实例打包成 `RenderSession`（attachments + extent + layout 状态），
  支持 `switchTo(target, loadOp)`；`FramebufferCopy` 改为走同一个 session 抽象。
- `FrameRenderTarget` 泛化：swapchain 与离屏目标都实现它（`colorImage/View/Format/width/height` 已有）。
- 布局跟踪：每个目标记 `currentLayout`，切换时按需要发屏障。
- 验收：写一个临时自检（`-Doutlands.render.selftest=targets`）：把离屏目标清成红色，再合成回窗口，
  屏幕左半红；validation 零报错；关闭自检后画面与今天完全一致。

#### S3 合成（大小：小）

- `FramebufferVk.render` 落地（D4 的合成 quad）；`bindTexture/unbindTexture` 变成注册/注销纹理句柄（不再是 throw）。
- 验收：自检升级为"半红 + 贴图方向正确"（用非对称图案验证方向，§11.7 风险 R1）。

#### S4 program 管线与 uniform（大小：大）— ✅ 已完成

- `DrawState` 增加 program 快照：`Program` 引用 + `programBlock`（block 字节的**副本**）+ `programSamplers`
  （每个 sampler 当时指向的纹理句柄）。两条都必须复制：block 是每个 program 一个数组，后续 `glUniform*`
  会**原地**改它（outline 链就是靠改 `BlurDir` 复用同一个 program），纹理单元也是全局状态。
- `GlShaders.Program` 在 link 时算出后端需要的三样：`samplerUniforms()`（按 (set,binding) 排序、按
  (set,binding) 去重——两个 stage 都声明同一 sampler 时反射会给出两条）、`vertexInputLocations()`、
  `blockSize()/hasBlock()`。
- `VulkanProgram`（新）：program → 两个 `VkShaderModule` + 每个 set 一个 `VkDescriptorSetLayout` + 一个
  `VkPipelineLayout` + 按 `PipelineState` 缓存的 PSO。`describe()` 是**纯函数**（把 binding 按 set 分组，
  拒绝 set 空洞与同 set 同 binding 冲突），所以布局可以在无设备时断言。
- `VulkanPrograms`（新）：registry + 每帧一池 descriptor set + 每个 frame slot 一个 128 KiB 的 uniform arena
  （`UNIFORM_BUFFER_DYNAMIC` + 动态偏移，按 `minUniformBufferOffsetAlignment` 对齐）。
- `VulkanStream`：`Draw` 带 program 时走 program 分支，之后清空所有流式绑定缓存；viewport/scissor 提到分支
  之前，两条路径共用；`vkCmdDraw(Indexed)` 抽成 `issueDraw`，避免两条路径各自演化。
- 顶点输入**按 program 声明的 location 声明**，而不是恒定的 4 个：多声明的属性 validation 会按"not consumed
  by vertex shader"每 program 报两条 warning（实测），并且 D3 本来就说派生。

**落地形状与初版规划的差异**

| 初版 | 实际 | 原因 |
|---|---|---|
| set 0 恒为 UBO、set 1 恒为 sampler | 从模块的 decoration 反射，按 set 分组 | 原版手写 shader 是 (0:block, 1:samplers)，而 `shaderc` 自动绑定给 mod shader 的是 (set 0: block@0, sampler@1)。写死 set 1 会让后者绑不上 |
| UBO "每帧 ring" | 每个 program 一份 block 字节进 arena，动态偏移选片 | 同一个 program 一帧内会被画多次（两个方向的 blur），每 program 一份区段不够 |
| 单独 `ProgramDescriptors` 类 | 布局在 `VulkanProgram`，池与 arena 在 `VulkanPrograms` | 布局与 program 同寿命，池与 arena 与帧同寿命，分开放反而要多一层 |
| `VulkanShader.load(byte[])` 变体 | `VulkanShader.create(device, bytes, name)` | 与 `load` 共用同一套长度/words 检查，不让"已经检查过"的路径少检查 |

**自检开关**：`-Doutlands.render.selftest.program=true`。每帧用 `GlShaders` 现编译链接一个 program（GLSL
450，一个 plain uniform + 一个显式 `(set=1,binding=0)` 的 sampler），把左半屏画成 75% 透明的全景图。
alpha 取 0.75 是刻意的：block 没上传的话 quad 全透明、只在日志里留痕；上传了就能在主菜单上看出左半屏被"蒙住"。
实测日志：`program 1 linked, block 16 bytes at set 0 binding 0, vertex inputs [0, 2], samplers [DiffuseSampler@1:0]`，
画面左半屏为半透明全景图，validation 零 error 零 warning。

**截图钩子**：`-Doutlands.render.shot.frame=N` 在第 N 帧（1 起）用后端自己的读回路径存一张 PNG 到
`screenshots/`。桌面截图工具不一定看得见 Vulkan 窗口（这台机器上 X11/XWayland 与 KDE portal 抓到的是纯白/纯黑），
而后端自己的 `vkCmdCopyImageToBuffer` 一定能。

#### S5 之前必须先定的一件事：program 绘制的 y 方向

`writeMvp` 给**流式**路径的 mvp 第 1 行取负，把 GL 的 NDC 翻成 Vulkan 的；program 路径拿不到这个：矩阵是游戏
自己写进 uniform 的，`gl_Position` 由 shader 算。而原版后处理 shader 的 `ProjMat` 是 `m11 = -2/h, m13 = +1`，
即 clip y=+1 表示屏幕上方。两种情况：

- 画进**窗口**：不翻 → 整条链的结果上下颠倒（描边出现在实体上方/下方错位）；
- 画进**离屏目标**：目标视口已经是负高度（§11.4 S2/S3 的 R1 结论），再翻一次就是双重翻转。

自检刻意**不**碰这个：它的顶点着色器直接写 clip 坐标、自己定 uv，因此只证明"机制通了、方向和采样自洽"。
灵光箭那条链要拿 GL 后端的截图逐像素比对，再决定翻在哪儿（viewport 负高度 / 目标存储顺序 / 两者），
这条写进 S5 的验收里。

#### S5 目标作为 sampler + 端到端（大小：中）

- 目标图像在 `unbind` 时转 `SHADER_READ_ONLY`，`descriptorSet(target.textureId)` 直接返回其 set。
- 验收：临时把能力表打开（`-Doutlands.render.declareGl2=true`），主菜单 blur **不动**，
  进世界后 `/summon minecraft:zombie ~ ~ ~ {Glowing:1b}` 能看到描边；四趟 pass 的 `GLSL` 均未触发 JIT（应走 AOT 模块）。

#### S6 打开能力表（大小：小）

- `GlAnswers.declareShimContract()`：`OpenGL20/21` 与三个 `ARB_*shader*` 打开；
  `VulkanBackend` 的启动警告改成"着色器与后处理已支持"。
- 回归清单：主菜单（7 段 blur 仍正确）、世界内（地形/实体/GUI）、截图、`F3` 调试屏、开光影模组时的显式拒绝。
- 验收：灵光箭可见 + 上述回归全过 + 663 测试全绿。

### 11.5 验收标准（定义"完成"）

1. `/summon minecraft:zombie ~ ~ ~ {Glowing:1b}` 在 Vulkan 下出现与原版一致的描边（截图对比 GL 后端）。
2. 主菜单背景模糊与今天逐像素一致（防止 D2/D4 改动破坏 §4.3 的 copy 路径）。
3. validation 层零 error（warning 允许，需逐条记录原因）。
4. 每帧 `vkCmdBeginRendering` 次数 ≤ 目标切换次数 + 1，`vkCmdCopyImageToBuffer` 不因目标切换而增加。
5. 目标内存可复用：`ShaderGroup` 删除后图像释放（`vkDestroyImage` 计数对齐 `vkCreateImage`）。

### 11.6 明确不做（本里程碑）

- MRT / 多颜色附件（原版 1.12 的后处理不用）。
- 采样深度附件、stencil 附件（`entity_outline` 不需要；`enableStencil` 保持今天的"显式不支持"）。
- sRGB 精确性（目标沿用 swapchain 格式；correctness 问题已由今天的快照/flip 规则承担）。
- 任意长度 chain 的 aux 纹理（`Shader#addAuxFramebuffer` 的上游用法；先按同一机制支持，不专门优化）。
- 光影包（Aperture）的管线图，见 §8.3。

### 11.7 已知风险与探测手段

| # | 风险 | 为什么危险 | 先探什么 |
|---|---|---|---|
| R1 | 目标图像与窗口图像**方向不一致** | 合成出来上下颠倒，且不抛异常 | S3 用非对称图案（左上角红块）验证；若颠倒，在目标视图或 UV 上修正，并记录在哪一侧修正 |
| R2 | 目标格式若与 swapchain 不同 | 管线不能复用，PSO 数量翻倍 | D1 直接锁死同格式；若将来要 HDR，先在 `RenderTargets` 里加格式维度 |
| R3 | 帧内 end/begin 与深度 `storeOp` 交互 | 上一轮已踩过（主菜单 blur 时好时坏，见 `VulkanSwapchain.beginRenderingInstance` 注释） | 切换目标时深度一律 `STORE`；对模糊场景做 4 次连续运行对比 |
| R4 | `glCopyTexSubImage2D` 与目标切换的顺序 | 主菜单 blur 依赖"复制当前帧" | S2 后重跑主菜单；若画错，把 copy 也纳入 `TargetBind` 的序号 |
| R5 | UBO 每帧分配导致停机 | 违反 §4.4（不许 `vkDeviceWaitIdle`） | S4 用 ring + 槽位退休，与 `VulkanStream` 现有 staging 一致 |
| R6 | program 声明的顶点输入不在规范化流里 | 静默画错或 validation 报错 | 派生失败直接报错点名 location 与 program；不允许回退到"随便绑一个" |
| R7 | `ShaderGroup` 在 resize 后重建目标 | 图像尺寸与 extent 不同、描述符陈旧 | S1 单测覆盖 create→delete→create；resize 回归 |

### 11.8 与里程碑的关系

M6 = S1+S2+S3（离屏目标闭环，画面不变）
M7 = S4+S5（program 绘制闭环，画面不变）
M8 = S6（打开 GL 2.0/2.1，灵光箭可见）

`M8` 之后，§8.4 的垫片契约中的「FBO（ARB）」与「GL 2.1 子集」才算真正兑现了前半部分；
FFP 生成、立即模式、显示列表仍在垫片侧（§8.4 桶 A 的剩余部分），与本节无交叉。

## 10. 这份文档相对 v1 的修正清单

| # | v1 的判断 | v2 的修正 | 依据 |
|---|---|---|---|
| 1 | 基线倾向 1.4 | **定 1.3 core** | 1.4 只多 `maintenance5`，1.3 覆盖广得多 |
| 2 | 描述符是 🔴 最大未知 | 🟡 只用于图像；缓冲区用 BDA + push constant | §4.2 |
| 3 | 图像布局是第二风险 | 🟢 塌缩为 3 条固定迁移 | `ATTACHMENT_OPTIMAL` 是 1.3 core |
| 4 | 显存分配要自建策略 | 🟢 VMA `MEMORY_USAGE_AUTO` | §4.4 |
| 5 | 缓冲区上传要 staging | 🟢 持久映射 VRAM + `ALLOW_TRANSFER_INSTEAD` | §4.4 |
| 6 | 未提 shader 管线 | 新增，且是 **阻塞项**（Java 无 Slang 绑定） | §8.2 |
| 7 | 未提 natives 分发差异 | 新增：Win/Linux **没有** natives，靠系统 loader | §7.2 |
| 8 | 未提 CPU↔GPU 对齐 | 新增硬需求：`scalar_block_layout` + 列主序 | §8.8 |
| 9 | 「七项里两个设计岔路口」 | 改为「硬骨头只剩同步、阶段语义、程序图」 | §5 |
| 10 | 基线 1.3 | **改回 1.4**：15 个提升扩展里 7 条直接减轻垫片工作 | §4.1 |
| 11 | Slang 是阻塞项 | **解除**：消费 SPIR-V，不处理 Slang | §8.2 |
| 12 | 未提功能范围 | 新增动态光源 / Sodium Extra / 连续 LOD / 光影包，**四者全在垫片之上** | §6 |
| 13 | 未提 `local_read` 结论 | 它已是 1.4 core，旧悬案关闭 | §4.1 |
| 14 | 未提 8 阶段对光影包不充分 | 新增：Iris 系需几十个程序阶段，走程序图而非垫片 | §6.4 |
| 15 | 动态光源在范围内 | **推迟**：与 shaderpack 自带光照模型冲突，且真做要改游戏逻辑层 | §6.1 |
| 16 | 未提动态贴图 / CTM | 新增，且两者大部分是**换 UV** 而非换绘制，成本远低于直觉 | §6.5, §6.6 |
| 17 | 「六件全在垫片之上」 | 一处例外：CTM 要求 `ChunkView` 支持 8 邻域查询 | §6.6 |
| 18 | 只有阶段钩子 | 新增**渲染图**：阶段钩子是闭合集合，图是开放集合；`PhaseRegistry` 并入图的节点表 | §3.3 |
| 19 | 未提屏障自动化 | 新增：资源声明 ⇒ 图推导 barrier/layout，这是采用渲染图的最强理由 | §3.3 |
| 20 | 未提渲染图 × 垫片交叉点 | 新增：`ShimGl` 需 current-node 上下文；节点内切 FBO 需结束/重开渲染实例 | §3.3 |
| 21 | 垫片接口面未定（阻塞） | **已定：GL 2.1 子集**，最小面，不依赖 Actinium 的绑定抽象 | §8.4 |
| 22 | 未提 FFP / 立即模式 / 显示列表 | 新增三大硬成本；量级参考 Actinium 的 3317 行生成器 | §8.4 |
| 23 | 未提不支持声明 | 新增交付物：**显式检测并拒绝**现有渲染模组 | §8.4 |
| 24 | 门后机制是阻塞项 | **已定**：不造门后开关，分支默认启用（M0 probe false，M1 起 true） | §8.1 |
| 25 | 垫片契约只管第三方模组 | **补齐 vanilla 自身路径**：分界在 `GlStateManager`（122 方法），不在 `GL11`（2794 调用点） | §8.4.1 |
| 26 | FFP 一律需要「生成」 | **vanilla 侧是 permutation**：它的 FFP 用法是闭合集合（`glLight`/`glMaterial`/`glFog` 仅 10 处） | §8.4.1 |
| 27 | 立即模式 / 显示列表是 vanilla 的成本 | **错位**：`glBegin` 全游戏仅 2 处、显示列表 3 处；这三项成本主要在模组侧 | §8.4.1 |
| 28 | 「在 `GlStateManager` 底部换 sink」 | **错**：sink 收到 GL 词汇 ⇒ 仍是 GL 模拟器。改成接口 + 两份实现 | §8.4.2 |
| 29 | 靠 transform 够到 vanilla 的 `GlStateManager` | **不需要**：vanilla 走显式接口，transform 只管模组 | §8.4.2 |
| 30 | GL 出口是 83 个 GL 方法 | **是四个漏斗**：`GlStateManager`(122) / `VertexBuffer`(53 行) / `Framebuffer` / `OpenGlHelper` | §8.4.2 |
| 31 | 史莱姆半透明层缺失 | 2977 错误映射到 SCISSOR_TEST，修正为 NORMALIZE | §8.4.1 |
| 32 | 正午天空偏红/黄昏色 | DrawState.colorArgb 字节序 R/B 颠倒，已修复 | §8.8 |
| 33 | GUI 夜间变暗污染 | 纹理单元 1 禁用时 lightmap 返回 null，隔离世界光照 | §8.4.2 |
| 34 | 物品栏方块阴影反转 | 推导模型空间标准双光源向量 (0.394, 0.917, -0.058) 与 (-0.577, 0.617, 0.536) 精确求解 | §8.4.1 |
| 35 | 地形相机世界坐标脱节 | CameraMatrix 补全 (-viewX, -viewY, -viewZ) 平移，与原版 preRenderChunk 对齐 | §3.3 |
| 36 | 全量重打包性能瓶颈 | 确认单体全量打包不可行（2.2GB/s 堆风暴），M5 定位为 Arena 增量池化与 MDI | §4.4, §5 |
| 37 | 物品与实体光照异常 | 根因为静态模型空间光照硬编码。升级为：1) 门控 Capability.LIGHTING；2) glLight 捕获眼空间光源向量；3) normalMatrix 动态将法线变至眼空间，统一修复 2D 物品暗化、箱子/盾牌等 TEISR 模型翻转、村民/实体黑脸 | §8.4.1 |
| 38 | M5 阶段一完成：地形 Arena 子分配池化与增量上传 | 1) 实现 Best-Fit O(1) 合并 BlockAllocator；2) 多 Slab 128MB 显存池与静态共享四边形索引缓冲（省去逐 chunk 索引生成与 32MB 显存占用）；3) 帧槽位延迟退休机制（消除 vkDeviceWaitIdle）；4) 区块生命周期感知与无效化（杜绝视野外幽灵区块积累），彻底消灭 2.2GB/s 堆风暴与 250ms 卡顿 | §4.4, §5, §6.7 |
| 39 | M5 阶段三完成：透明顶点压缩 | 1) 将 28 字节 DefaultVertexFormats.BLOCK 压为 16 字节紧凑 GPU 布局（8 字节 s16 相对坐标 * 512 + 2x u8 光照；4 字节 u16 unorm UV；4 字节 u8 unorm 烘焙色），单四边形 64 字节 1:1 匹配 GPU 缓存行；2) 外部保持 28 字节输入确保模组完全兼容；3) 基于双 64 位宽存与 VarHandle 的流式零 GC 转码；4) 更新 terrain.vert/frag 着色器并重新编译 SPIR-V；节省 ~43% 显存容量与内存传输带宽 | §4.4, §8.8 |
| 40 | M5 阶段四完成：帧间缓存复用与层分区 | 1) 两级时间脏标记缓存（Tier 1 GPU Buffer Hit: 零内存触碰直调指令；Tier 2 Host Staging Hit: 零哈希查表一次性 memcpy；Tier 3 Cache Miss: 全量构建并回填双层缓存）；2) 间接缓冲四层独立分区（每层 16,384 条指令/偏移，16 字节对齐，层间变动互不干扰）；3) 消除 FrameStates 中 visibleChunks.clone() 堆垃圾（引入 4 组预分配 LongArrayList 池化复用，节省 19.2MB/s GC 压力）；消灭静止与视锥平移时的 240 万次/秒哈希查表与 84MB/s 主线程拷贝 | §4.4, §5 |
