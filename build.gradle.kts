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
            handshake, discovers every tool, resource and prompt, and gives you a schema-generated
            argument template to call any of them with — no client code, no restart, no guessing.</p>

            <h3>Highlights</h3>
            <ul>
                <li><b>Three transports</b> — stdio (child process), Streamable HTTP and the legacy
                HTTP+SSE. Session ids, protocol version negotiation and capabilities are all handled for
                you.</li>
                <li><b>Auto-discovery</b> — the whole tool list comes from <code>tools/list</code>
                (including cursor pagination). Resources and prompts land in the same tree, and a
                <code>tools/list_changed</code> notification refreshes it live.</li>
                <li><b>Arguments as JSON, prefilled</b> — picking a tool fills the JSON tab with a
                template generated from its schema: every declared parameter is listed, defaults are
                already in place, and <code>${'$'}ref</code> / <code>allOf</code> are resolved. Edit the
                values you care about, delete the rest, press <code>Ctrl+Enter</code>. The raw tool
                definition (annotations, output schema) is one tab away.</li>
                <li><b>Call history</b> — the arguments of every call are kept per tool, so retrying with
                one value changed takes a click instead of retyping.</li>
                <li><b>Results, verbatim</b> — the JSON-RPC result is shown as-is, in a highlighted,
                scrollable editor, with one copy button and the round-trip time next to the status.</li>
                <li><b>Wire-level console</b> — every request, response, notification and stderr line is
                logged with direction and timing, which is what you need when a server misbehaves.</li>
                <li><b>Import existing configs</b> — paste or import the familiar
                <code>mcpServers</code> JSON from Claude Desktop, Cursor, VS Code or a project
                <code>.mcp.json</code>; MCP Debugger also scans your project for them.</li>
                <li><b>English and Chinese UI</b> — follows the IDE's own language out of the box,
                with a one-click switch in the toolbar and a <b>Follow IDE language</b> reset.</li>
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
                <li><b>参数直接写 JSON，进来就有模板</b>：选中工具即按其 JSON Schema 生成一份参数模板——声明的每个
                参数都在，默认值已填好，<code>${'$'}ref</code> 与 <code>allOf</code> 会被展开。改掉关心的几个值、
                删掉多余的字段，<code>Ctrl+Enter</code> 调用；工具原始定义（annotations、输出 schema）就在隔壁页签。</li>
                <li><b>调用历史</b>：每个工具的每次调用都留档入参，点一下即填回 JSON 页签，改一个值再重发只要一次点击。</li>
                <li><b>结果原样呈现</b>：调用结果直接显示为 JSON（语法高亮、可滚动），一个「复制」按钮拿走同一份
                内容，调用耗时显示在状态旁边。</li>
                <li><b>报文级日志</b>：请求、响应、通知、子进程 stderr 全部按方向与时间落盘到控制台，
                排查服务端问题时最有用。</li>
                <li><b>导入现成配置</b>：粘贴或导入 Claude Desktop / Cursor / VS Code 的
                <code>mcpServers</code> JSON，插件也会自动扫描你工程里的 MCP 配置文件。</li>
                <li><b>中英双语界面</b>：默认跟随 IDE 自身语言，工具栏可一键切换，
                「管理」菜单末尾的「跟随 IDE 语言」一键还原。</li>
            </ul>
        """.trimIndent()
        changeNotes = """
            <b>1.0.4</b>
            <ul>
                <li><b>New: English and Chinese UI.</b> Every label, tooltip, dialog button and error
                message now has both wordings. By default the plugin follows the IDE's own UI language —
                nothing to configure; the toolbar carries a one-click switch, and <b>Follow IDE
                language</b> (last item in the Manage menu) hands control back to the IDE.</li>
                <li>The switch re-labels the live tool window in place: tree expansion, selection,
                scroll position, splitter sizes and whatever you have already typed stay exactly as they
                were.</li>
                <li>Copy trimmed throughout — repeated navigation hints, and the overlap between the
                empty-state guidance, the history tooltips and the security notes, are now one clear
                sentence each.</li>
                <li><b>Fixed:</b> toggling the language walked the toolbar's action group by hand, which
                newer IDEs reject outright (<code>Do not call getChildren(null)</code>) — the switch
                itself threw.</li>
                <li><b>Fixed:</b> a few labels kept the old language after a switch — the JSON and
                Definition tab titles (they were looked up by the editor instead of the tab's actual
                component, so the rename silently did nothing), the server dialog's Cancel button, and
                the Yes/No/OK buttons of the platform confirmation dialogs, which follow the IDE's
                language rather than the plugin's and now carry our own labels.</li>
            </ul>
            <b>1.0.3</b>
            <ul>
                <li><b>New: call history.</b> Every call keeps the exact arguments that were sent —
                success, tool-reported error and transport failure alike (the failed ones are usually the
                ones worth reusing). One click on an entry writes those arguments back into the JSON tab
                and switches to it, ready to re-send with <code>Ctrl+Enter</code>;
                <code>Ctrl+Z</code> restores whatever was there before.</li>
                <li>Repeating a call with the same arguments refreshes the newest entry instead of
                stacking duplicates. History is kept per server + tool (20 entries each, 400 in total)
                and is cleared together with the server it belongs to.</li>
                <li><b>Fixed:</b> the history list showed up as an empty area — its cell renderer built
                the two text labels but never attached them to the row, so every row had the right
                height and nothing in it.</li>
                <li><b>Fixed:</b> the tool detail panel was never registered as a disposable, so closing
                and reopening the tool window leaked one live instance (result timer + message-bus
                subscriptions) each time.</li>
            </ul>
            <b>1.0.2</b>
            <ul>
                <li>Request headers (a bearer token, and anything else the endpoint needs) sit in their
                own tab next to the arguments, so they can be edited without going back into the server
                dialog. Applies to <code>http</code> / <code>sse</code> servers; changes take effect on
                the next request.</li>
            </ul>
            <b>1.0.0</b>
            <ul>
                <li>First public release.</li>
                <li>Connect to MCP servers over stdio, Streamable HTTP and HTTP+SSE.</li>
                <li>Discover and call tools with a schema-generated argument template, or raw JSON.</li>
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
