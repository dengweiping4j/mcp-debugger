# MCP Debugger

> Postman-style console for **MCP (Model Context Protocol)** servers, right inside your IDE.

指向一个 MCP 服务端（本地 `stdio` 命令，或远端 `HTTP` / `SSE` 地址），插件自动完成 MCP 握手、
拉取**全部工具**并列出；点开任意工具，参数区已经按它的 JSON Schema 预填好一份模板，改完调用即可。
不用写客户端代码，不用重启 IDE，也不用猜参数长什么样。

适用于 **PyCharm / IntelliJ IDEA / WebStorm / GoLand** 等所有 JetBrains IDE。

---

## 功能

| 能力 | 说明 |
| --- | --- |
| **三种传输** | `stdio`（子进程）、**Streamable HTTP**、旧版 **HTTP+SSE**。会话 id、协议版本协商、能力声明全部自动处理 |
| **自动获取工具** | 工具列表来自 `tools/list`（含游标翻页）；资源、资源模板、提示词一并列出。服务端发 `tools/list_changed` 通知时自动刷新 |
| **参数模板自动预填** | 按工具的 JSON Schema 生成参数 JSON：**声明的参数一个不少**，值取 `default`、枚举首项或按类型的空值（`""` / `0` / `false` / `[]` / `{}`）；`$ref` 与 `allOf` 会被展开，嵌套对象递归展开 |
| **定义随时可查** | 旁边一个「定义」页签就是工具的原始 JSON（含 `annotations`、`outputSchema`），参数的类型、必填、说明、默认值都在里面 |
| **结果原样呈现** | 调用结果直接摊成一份 JSON（编辑器级高亮、可滚动、可复制），不再做二次渲染；调用耗时显示在状态旁边 |
| **报文级日志** | 请求、响应、通知、子进程 stderr 全部按方向与时间落到控制台 |
| **导入现成配置** | 粘贴 / 导入 Claude Desktop、Cursor、VS Code 的 `mcpServers` JSON；也会自动扫描工程内的 MCP 配置文件 |
| **自动定位当前工程** | 配置按「全局」与「本工程」两档存放，工程级配置可随仓库共享 |

---

## 安装

### 从发行包安装（推荐）

```bash
./gradlew buildPlugin
# 产物： build/distributions/mcp-debug-idea-plugin-1.0.0.zip
```

Windows 下也可直接双击 `package.bat`，它等价于上面这一步并打印产物路径。

然后在 IDE 里：**Settings → Plugins → ⚙ → Install Plugin from Disk…** 选择该 zip，重启 IDE。

### 从源码运行（开发调试）

用 IDEA 打开本工程，等待 Gradle 同步完成，执行：

```bash
./gradlew runIde
```

会拉起一个装了本插件的沙箱 IDE。

> **注意**：本工程的 Gradle Wrapper jar 未随仓库提交，若 `./gradlew` 报
> `找不到主类 GradleWrapperMain`，请改用本机 gradle 发行版，例如：
> ```bash
> "$HOME/.gradle/wrapper/dists/gradle-8.13-bin/<hash>/gradle-8.13/bin/gradle" buildPlugin
> ```

---

## 快速上手

1. 重启后在 IDE 右侧边栏找到 **MCP Debugger** 工具窗口。
2. 点工具栏的 **➕** 新建一个服务端配置，或者点 **📋 粘贴导入** 把现成的 `mcpServers` JSON 贴进去。
3. 选好服务端，点 **Connect**。状态点变绿、工具树开始填充，就说明握手成功了。
4. 左侧树里选中一个工具 → 右侧参数区已经按它的定义带出了参数 JSON → 改好点 **调用**（或按 `Ctrl+Enter`）。
5. 调用结果以 JSON 直接显示在下方（可滚动，点状态条右侧的 **复制** 拿走同一份内容），**Console** 里是报文流水。

### 三种传输怎么填

**stdio**（本地子进程）

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "D:/data"],
      "env": { "LOG_LEVEL": "debug" }
    }
  }
}
```

也支持 `uvx` / `docker` / 任意可执行文件。插件会以 JSON-RPC 逐行读写子进程的 stdout/stdin，
stderr 原样转进 Console 面板。

**Streamable HTTP**

```json
{
  "mcpServers": {
    "remote": {
      "type": "http",
      "url": "https://example.com/mcp",
      "headers": { "Authorization": "Bearer xxx" }
    }
  }
}
```

会话 id 从响应头 `Mcp-Session-Id` 自动拾取并回带。

**旧版 HTTP + SSE**

```json
{
  "mcpServers": {
    "legacy": {
      "type": "sse",
      "url": "https://example.com/sse"
    }
  }
}
```

先 GET 建 SSE 长连接，从 `endpoint` 事件拿到回传地址，之后 POST 走该地址，响应从 SSE 流里读。

---

## 界面布局

工具栏刻意分成两行，为的是适配右侧边栏（PyCharm / IDEA 里这个窗口默认停靠在右边，
宽度通常只有 300~400px）：下拉框独占一行，动作按钮压缩到 5 个图标按钮，
编辑 / 删除 / 导入导出收进「管理」。

```
┌──────────────────────────────────────────────────────────────────────┐
│ [ 服务端 ▾ ......................... ]                               │  工具栏
│ ➕ ↻ ✏️ ⚙️ ▤          ● 已连接 · 12 工具                              │
├───────────────────────────┬──────────────────────────────────────────┤
│ 目录树                     │  详情 / 调用区                            │
│  ├ 工具 (12)               │  ┌────────────────────────────────────┐  │
│  │   ├ add                 │  │ 名称 · 描述 · 来源                  │  │
│  │   ├ echo                │  ├────────────────────────────────────┤  │
│  │   └ …                   │  │ JSON │ 定义                        │  │
│  ├ 资源 (3)                │  │ ┌────────────────────────────────┐ │  │
│  ├ 资源模板 (1)            │  │ │  {                              │ │  │
│                            │  │ │    "a": 0,                      │ │  │
│                            │  │ │    "b": 0                       │ │  │
│                            │  │ │  }                              │ │  │
│                            │  │ └────────────────────────────────┘ │  │
│                            │  │            [ 调用 ]                │  │
│                            │  ├────────────────────────────────────┤  │
│                            │  │ ● 调用成功 · 12 ms           [复制] │  │
│                            │  │ {                                  │  │
│                            │  │   "content": [ … ]                 │  │
│                            │  └────────────────────────────────────┘  │
├───────────────────────────┴──────────────────────────────────────────┤
│ Console  10:42:01.113  ← {"jsonrpc":"2.0","id":3,"method":"tools/call"… │
│          10:42:01.115  → {"jsonrpc":"2.0","id":3,"result":{"content"… │
└──────────────────────────────────────────────────────────────────────┘
```

**窗口窄了会自动换排法。** 宽度小于 520px（右侧边栏的常态）时，「目录树 / 详情」从左右并排
改成上下堆叠——并排至少需要"目录 180px + 详情 300px"，窄边栏里那样只会把目录挤成一条缝。
把工具窗口拖宽到 520px 以上会自动变回左右分栏。

**三条分隔线都能拖**（目录/详情、详情/日志，以及详情区内部的参数/结果）：鼠标移到分隔线上
光标会变成调整形状，拖就行。日志区除了拖，还能用工具栏的 **▤** 开关或日志标题栏左侧的
**▼** 折叠/展开——折叠状态会被记住。

---

## 工程结构

```
src/main/java/com/tool4j/mcp/
├── model/          # 纯数据模型
│   ├── McpServerConfig.java    # 一份服务端配置（传输、命令/地址、环境变量、headers）
│   ├── McpServerInfo.java      # initialize 的返回：协议版本 + 能力
│   ├── McpTool.java            # 工具：name / title / description / inputSchema
│   ├── McpResource.java        # 资源与资源模板
│   ├── McpPrompt.java          # 提示词及其参数
│   ├── McpCallResult.java      # 统一结果载体（归一化 content/contents/messages 三种形态）
│   ├── TransportType.java      # STDIO / HTTP / SSE
│   └── KeyValue.java           # 键值对（env / headers）
│
├── protocol/       # 协议层，不依赖任何 IDE 类 → 可单独测试
│   ├── JsonRpc.java            # JSON-RPC 2.0 信封的构造与拆解
│   ├── JsonUtil.java           # Gson 的薄封装（取值、建对象、错误描述）
│   ├── McpClient.java          # 会话主类：握手、tools/list 翻页、tools/call、反向请求应答
│   ├── McpConfigParser.java    # 解析 mcpServers JSON
│   ├── SchemaUtil.java         # JSON Schema 解析：$ref / allOf / enum / default / required
│   └── McpException.java       # 带 JSON-RPC error 详情与 data 的异常
│
├── transport/      # 三种传输，统一 McpTransport 接口
│   ├── McpTransport.java       # 接口：send / listen / close
│   ├── AbstractTransport.java  # 公共：ID 生成、待响应表、监听器派发
│   ├── StdioTransport.java     # 子进程 stdio
│   ├── HttpTransport.java      # Streamable HTTP
│   ├── SseTransport.java       # 旧版 HTTP+SSE
│   └── Transports.java         # 工厂
│
├── service/
│   └── McpProjectService.java  # Project 级 Service：持会话、工程关闭时回收子进程
│
├── settings/
│   └── McpSettings.java        # @State 持久化 + 变更广播（MessageBus Topic）
│
├── util/
│   ├── McpConfigFiles.java     # 扫描工程内常见 MCP 配置文件位置
│   ├── Os.java                 # 路径与命令的平台差异
│   └── PluginInfo.java
│
└── ui/
    ├── McpToolWindowFactory.java  # 工具窗口入口
    ├── McpPanel.java              # 主面板：工具栏 + 目录树 + 详情区 + 日志
    ├── ImportConfigDialog.java    # 从文件 / 粘贴导入配置
    ├── ServerEditDialog.java      # 新建 / 编辑服务端（按传输切换表单）
    ├── ToolDetailPanel.java       # 工具详情 + 调用入口
    ├── ResourceDetailPanel.java   # 资源 / 资源模板读取
    ├── PromptDetailPanel.java     # 提示词渲染
    ├── SchemaFormPanel.java       # JSON Schema → 表单（当前未接入界面，见文件头说明）
    ├── ResultView.java            # 调用结果：直接显示 JSON + 复制原文
    ├── LogConsole.java            # 报文日志
    ├── KeyValueTable.java         # env / headers 表格控件
    ├── Editors.java / Ui.java / Bg.java  # 编辑器、主题色、后台任务的小工具
```

**设计约定**

- 只依赖 `com.intellij.modules.platform` 基础模块 → 同一个包能装进所有 JetBrains IDE。
- 唯一的第三方依赖是 **Gson**（MCP 是 JSON-RPC over JSON，足够）。
  *不*引入官方 Java MCP SDK——它带 reactor / slf4j，在插件类加载器里容易和平台自带版本打架。
- 协议层（`protocol` / `transport` / `model`）不引用任何 IDE 类，可以直接用 JUnit 测。
- 所有网络/子进程调用都在 `Task.Backgroundable` 里跑，绝不在 EDT 上阻塞。

---

## 端到端冒烟测试

`smoke/` 下放着两套可在命令行直接跑的冒烟测试，配套两个**最小 MCP 服务端**（纯 Python 标准库，无依赖）：

| 文件 | 作用 |
| --- | --- |
| `smoke/mini_mcp_server.py` | 最小 stdio 服务端：echo / add / screenshot（返 base64 图）/ boom（返错误）+ 资源 + 提示词 + 反向 `roots/list` |
| `smoke/mini_mcp_http.py` | 最小 HTTP 服务端：`/mcp` Streamable + `/sse` 旧版 |
| `smoke/SmokeMain.java` | stdio 全链路，**37 项断言** |
| `smoke/SmokeHttp.java` | Streamable HTTP + 旧版 SSE + 会话可恢复性 + 报错可读性，**21 项断言** |
| `smoke/dump_server.py` | 排障用：把收到的原始 HTTP 报文逐字节打出来（看客户端到底发了什么头/体） |
| `smoke/run.sh` | 一键跑（自动找 JDK / Python / gson，编译并执行） |

一条命令跑全部：

```bash
./gradlew classes    # 先编主代码
./smoke/run.sh       # stdio + Streamable HTTP + 旧版 SSE
./smoke/run.sh stdio # 只跑 stdio
./smoke/run.sh http  # 只跑两种 HTTP 传输
```

`run.sh` 会自动探测 JDK（依次看 `MCP_SMOKE_JAVA`、`JAVA_HOME`、`PATH`）、Python
（`MCP_SMOKE_PYTHON` 或 `PATH` 上的 `python3`/`python`）以及 gson jar（先从构建沙箱里找，
再从 Gradle 缓存找）。需要覆盖时用环境变量：

```bash
MCP_SMOKE_JAVA=/path/to/jdk-17 \
MCP_SMOKE_PYTHON=/path/to/python3 \
MCP_SMOKE_PORT=8931 \
./smoke/run.sh
```

覆盖点包括：握手与协议版本协商、`tools/list` 游标翻页、`tools/call` 成功与错误、
`resources/list` + `resources/read`、`prompts/list` + `prompts/get`、反向 `roots/list` 应答、
子进程 stderr 转发、非 ASCII 内容（中文往返）、会话复用与关闭、**关闭后能重建会话**、**连不上时报错可读**。

> 最近一次全量运行：**stdio 37/37 通过，HTTP 21/21 通过**。

> 两个 fixture 都**显式把 stdio 钉成 UTF-8**。MCP 规定 stdio 传输一律 UTF-8，而 Windows 上
> Python 面向管道时默认取系统 ANSI 代码页（cp936），不钉住的话中文会以 GBK 字节发出去，
> 客户端按 UTF-8 解就是乱码——而且这个默认值随宿主 locale 漂移，会让测试时绿时红。

> `mini_mcp_http.py` 还会**主动拒绝 h2c Upgrade 协商**（回 400 `Parse error`），
> 如实模仿 uvicorn 的行为。这是为了让冒烟测试守住"HTTP 传输必须钉死 HTTP/1.1"这条线——
> 见下方"已知坑"。

---

## 构建环境

| 项 | 值 |
| --- | --- |
| 构建系统 | Gradle 8.13 + **IntelliJ Platform Gradle Plugin 2.5.0** |
| JDK | 17 |
| 目标平台 | IDEA Community 2023.3.8（`pluginSinceBuild = 233`，不设上界） |
| 第三方依赖 | Gson 2.10.1（打进发行包）；Lombok（仅编译期） |
| 测试 | JUnit 5.10.2（限协议层纯逻辑） |

`gradle.properties` 里可改 `platformType` / `platformVersion` / `pluginSinceBuild`，
换目标 IDE 或版本只需改这里。

---

## FAQ

**Q：为什么不做成 PyCharm 专用插件？**
A：因为没必要。只依赖平台基础模块，一份包同时适用 PyCharm / IDEA / WebStorm / GoLand，
维护成本反而更低。

**Q：能用 PyCharm 社区版吗？**
A：可以。`stdio` 与 `HTTP`/`SSE` 都是纯 JDK 能力，社区版完全够用。

**Q：调用报 `Method not found`？**
A：不同 MCP 服务端实现的能力差异较大。先在 Console 里看 `initialize` 返回的 `capabilities`，
确认它声明了 `tools` / `resources` / `prompts` 中的哪几项。

**Q：stdio 服务端起不来？**
A：多半是命令不在 IDE 的 PATH 里（`npx` / `uvx` 最常见）。在配置里写绝对路径，
或把 `env` 里的 `PATH` 显式补全。子进程的报错会原样出现在 Console 面板。

**Q：连 Python 写的 MCP 服务端（FastMCP / uvicorn）报 `HTTP 400 Parse error`？**
A：已经处理掉了，不用管——但值得知道原因。JDK 的 `HttpClient` 默认走 HTTP/2，
明文 http 下会先发一轮 **h2c Upgrade 协商**（`Connection: Upgrade, HTTP2-Settings`
加 `Upgrade: h2c`）。curl 和 Python 的 `http.server` 会忽略这组头，所以用 curl 试是通的；
但 **uvicorn（httptools/h11）不实现 h2c**，解析不了就把请求体当成空的交给上层，
于是服务端报 `Parse error: Expecting value: line 1 column 1`。
插件的 HTTP / SSE 传输因此**都钉死了 HTTP/1.1**。MCP 报文很小，用不上 HTTP/2。

**Q：第一次连接失败之后，再点"连接"就没反应了（日志只有一句"连接失败：连接已关闭"）？**
A：这是 1.0.0 的一个已修 bug。握手失败时会话会自己关闭，而旧实现把已关闭的实例
继续缓存在服务里还回去，导致后续重试不做任何网络请求就直接失败。
现在缓存发现实例已关闭会就地重建。若你用的是旧包，改一下服务器配置（或重启 IDE）
就能绕开。

**Q：能存敏感 token 吗？**
A：建议用 `env` / `headers` 引用系统环境变量。配置本身以明文存在 IDE 配置目录，
不要直接提交带密钥的工程级配置。

---

## License

见 [LICENSE](LICENSE)。
