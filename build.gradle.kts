import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
            providers.gradleProperty("platformVersion").get()
        )
    }

    // ---- 打包进插件的第三方库（会被复制到插件发行包 lib/ 下）----
    // MCP 是 JSON-RPC 2.0 over JSON，Gson 负责全部报文的解析与构造。
    // 只依赖这一个库，不引入官方 Java MCP SDK（它带 reactor / slf4j，
    // 在插件类加载器里容易和平台自带版本打架）。
    implementation("com.google.code.gson:gson:2.10.1")

    // Lombok（仅编译期，用于 model 层的 getter/setter）
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")

    // 单元测试：协议层里不依赖 IDE 的部分（JSON Schema 解析、配置导入解析）可直接测
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.tool4j.mcp.debug"
        name = "MCP Debugger"
        version = providers.gradleProperty("pluginVersion").get()
        vendor {
            name = "tool4j"
            email = "weipingdeng@qq.com"
            url = "https://tool4j.com"
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
            // 不设上界，兼容后续版本
            untilBuild = provider { null }
        }
        description = """
            <p><b>Postman-style console for MCP (Model Context Protocol) servers, inside your IDE.</b></p>
            <p>Point MCP Debugger at a local <code>stdio</code> server (npx / uvx / docker / a plain
            executable) or a remote <code>HTTP</code> / <code>SSE</code> endpoint. It performs the full MCP
            handshake, discovers every tool, resource and prompt, and gives you a generated form to call
            any of them with — no client code, no restart, no guessing.</p>

            <h3>Highlights</h3>
            <ul>
                <li><b>Three transports</b> — stdio (child process), Streamable HTTP and the legacy
                HTTP+SSE. Session ids, protocol version negotiation and capabilities are all handled for
                you.</li>
                <li><b>Auto-discovery</b> — the whole tool list comes from <code>tools/list</code>
                (including cursor pagination). Resources and prompts land in the same tree, and a
                <code>tools/list_changed</code> notification refreshes it live.</li>
                <li><b>Generated parameter forms</b> — the tool's JSON Schema becomes a real form: enums
                turn into combo boxes, booleans into tri-state selectors, nested objects into grouped
                sections, arrays into one-per-line lists. Required fields are marked, defaults are
                prefilled, and <code>${'$'}ref</code> / <code>allOf</code> are resolved. Prefer raw JSON?
                Switch to the JSON tab and the typed payload wins.</li>
                <li><b>Results, verbatim</b> — the JSON-RPC result is shown as-is, in a highlighted,
                scrollable editor, with one copy button and the round-trip time next to the status.</li>
                <li><b>Wire-level console</b> — every request, response, notification and stderr line is
                logged with direction and timing, which is what you need when a server misbehaves.</li>
                <li><b>Import existing configs</b> — paste or import the familiar
                <code>mcpServers</code> JSON from Claude Desktop, Cursor, VS Code or a project
                <code>.mcp.json</code>; MCP Debugger also scans your project for them.</li>
            </ul>

            <h3>中文说明</h3>
            <p>把 <b>MCP（Model Context Protocol）调试台</b>装进 IDE：填一个本地 <code>stdio</code> 命令
            （npx / uvx / docker 或任意可执行文件）或远端 <code>HTTP</code> / <code>SSE</code> 地址，插件自动完成
            MCP 握手、拉取全部工具并列出，点开任意工具即可填参数调用。</p>
            <ul>
                <li><b>三种传输</b>：stdio（子进程）、Streamable HTTP、旧版 HTTP+SSE；会话 id、协议版本协商、
                能力声明全部自动处理。</li>
                <li><b>自动获取工具</b>：工具列表来自 <code>tools/list</code>（含游标翻页），资源与提示词一并列出；
                服务端发 <code>tools/list_changed</code> 通知时自动刷新。</li>
                <li><b>自动生成参数表单</b>：按工具的 JSON Schema 生成真实表单——枚举变下拉框、布尔变三态选择、
                嵌套对象分组折叠、数组按行填写；必填项带标记、默认值自动填入，<code>${'$'}ref</code> 与
                <code>allOf</code> 会被展开。想直接写 JSON？切到 JSON 页签，以你当前所在页签为准。</li>
                <li><b>结果原样呈现</b>：调用结果直接显示为 JSON（语法高亮、可滚动），一个「复制」按钮拿走同一份
                内容，调用耗时显示在状态旁边。</li>
                <li><b>报文级日志</b>：请求、响应、通知、子进程 stderr 全部按方向与时间落盘到控制台，
                排查服务端问题时最有用。</li>
                <li><b>导入现成配置</b>：粘贴或导入 Claude Desktop / Cursor / VS Code 的
                <code>mcpServers</code> JSON，插件也会自动扫描你工程里的 MCP 配置文件。</li>
            </ul>
        """.trimIndent()
        changeNotes = """
            <b>1.0.0</b>
            <ul>
                <li>First public release.</li>
                <li>Connect to MCP servers over stdio, Streamable HTTP and HTTP+SSE.</li>
                <li>Discover and call tools with a schema-generated parameter form, or raw JSON.</li>
                <li>Browse resources and prompts; read resources and render prompts.</li>
                <li>Wire-level console with request/response/stderr logging.</li>
                <li>Import <code>mcpServers</code> JSON and scan the project for MCP config files.</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity,
                providers.gradleProperty("platformVersion").get())
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
