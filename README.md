# My_Agent 多智能体对话平台

基于 **Spring Boot 4 + Spring AI 2.0** 的多智能体（Multi-Agent）对话平台：支持自定义智能体、智能路由、参数追问补全、动态规划编排、长期记忆与工具调用，前端为内置静态页面，开箱即用。

## 核心特性

- **自定义智能体**：页面创建任意角色（翻译、天气、代码助手…），配置人设提示词、模型与温度；系统自动生成提示词库 `agent_code.md`。
- **智能路由**：未绑定智能体的会话由 LLM 三态路由（命中智能体 / 普通对话 / 正在回答追问），并携带最近对话上下文识别「北京呢？」这类承接上一轮的短追问。
- **参数追问补全**：智能体声明 `paramSchema` 后，缺失必填参数时自动追问（上限 3 轮），参数齐全才执行；跟进任务继承上一轮已明确的参数（如「今天」→ 日期=今天）。
- **动态规划（Planner）**：规划模式会话不预配置步骤，由 LLM 运行时根据用户目标规划多智能体步骤并顺序执行，执行过程实时展示但不写入记忆。
- **双层记忆**：短期窗口（`chat_message` 多轮原文）+ 长期滚动摘要（`summary` / `core_facts`），超出窗口的历史自动压缩、异步合并，先返回数据再处理记忆。
- **流式输出**：SSE 推送 `token`（正文，进记忆）与 `progress`（执行过程，不进记忆）两类事件，前端逐字渲染。
- **工具调用**：全局能力池（天气查询、日期解析等）随请求挂载，AI 自主决定是否调用；工具使用日志可追踪。

## 技术栈

| 组件 | 版本 / 说明 |
|---|---|
| JDK | 17 |
| Spring Boot | 4.0.7 |
| Spring AI | 2.0.0（`spring-ai-starter-model-openai`，兼容 OpenAI 协议） |
| MyBatis-Plus | 3.5.16（`mybatis-plus-spring-boot4-starter`） |
| MySQL | 8.x（`mysql-connector-j`） |
| Hutool | 5.8.38（JSON 解析统一用 `JSONUtil`，规避 Jackson ObjectMapper） |
| 前端 | 内置静态页面（`static/index.html` + Vue3 + 原生 CSS） |

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.9+（本机开发环境见「开发备注」）
- MySQL 8.x（连接串会自动建库）

### 2. 初始化数据库

首次启动前手动执行建表脚本（幂等，可重复执行）：

```bash
mysql -uroot -p < src/main/resources/sql/schema.sql   # 建表
mysql -uroot -p < src/main/resources/sql/alter.sql    # 旧库补列（新库可跳过）
```

### 3. 配置

`src/main/resources/application.yaml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/agent?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:root@123}
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:sk-xxx}                     # 推荐用环境变量注入
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1  # OpenAI 兼容服务地址
      chat:
        model: qwen3.7-plus
server:
  port: 8080
```

> 使用 DeepSeek / Ollama 等其他兼容服务时，改 `base-url` 与 `model` 即可。

### 4. 启动与访问

```bash
mvn spring-boot:run
# 或
mvn clean package && java -jar target/my_agent-0.0.1-SNAPSHOT.jar
```

浏览器访问 <http://localhost:8080>。

## 架构与核心机制

### 一次对话的编排流程

```
POST /api/chat/send | /stream
        │
        ▼
ChatService（编排门面）—— 同步 chat() / 流式 doStream()
   │
   ├─ 规划模式会话（conversation.planner=1）
   │    └─ runPlannerRound：PlannerService 规划步骤 → executeSteps 顺序执行 → savePlannerExchange 落库
   │
   └─ 普通对话
        └─ runAgentRound：
             ① determineAgent（显式绑定 / CLARIFY 绑定 / 智能路由）
             ② 话题切换预检（仅 CLARIFY 绑定，防止含城市词的新话题被误当补全）
             ③ decideClarify（参数抽取 → 缺失必填则追问，齐全则进入正式回答）
             ④ buildRequest（人设 + 长期记忆 + 已确认参数 + 工具）→ 主模型 call()
        └─ afterReply（touchConversation + 异步滚动摘要合并）
```

### 关键机制

| 机制 | 说明 |
|---|---|
| 绑定来源 `agent_bind_source` | `EXPLICIT`=用户显式选择（粘住不解绑）；`CLARIFY`=追问临时绑定（话题切换自动解绑）；空=自由路由 |
| 参数追问 | `paramSchema` JSON 数组声明参数；LLM 从最近 12 条历史抽取已确认取值；缺失必填项生成 `🔎 还需补充信息` 追问，上限 3 次 |
| 智能路由 | 裸 ChatModel 三态 JSON 决策（`{"route":true,"agentCode":"..."}` / `{"route":false}` / `{"route":false,"continue":true}`），解析用 Hutool |
| 记忆体系 | `chat_message` 存短期窗口；`conversation.summary/core_facts` 存长期滚动摘要；`DbChatMemory` 提供 `ChatMemory` 实现 |
| 过程可见 | SSE 事件字段名即类型：`{"token":"正文分片"}`（进记忆）/ `{"progress":"执行过程"}`（不进记忆） |
| 工具调用 | `ToolRegistry` 启动时预解析全局能力池；仅当请求命中/绑定智能体才挂载工具；`ToolUsageLoggingAdvisor` 记录工具使用 |
| 流式策略 | Spring AI 2.0.0 的 `stream()` 在工具调用场景会崩（见开发备注），统一 `call()` 拿完整答案后按 4 字切片模拟流式 |

## API 一览

### 对话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat/send` | 同步对话，返回 `{"content":"完整回复"}` |
| POST | `/api/chat/stream` | SSE 流式对话，事件 data 为 `{"token":"..."}` 或 `{"progress":"..."}` |

### 会话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat/conversation` | 开新会话（可传 `agentId` 绑定智能体，或 `planner:true` 建规划会话） |
| PUT | `/api/chat/conversation/{id}` | 重命名会话 |
| DELETE | `/api/chat/conversation/{id}` | 删除会话及全部消息 |
| GET | `/api/chat/conversations` | 会话列表（按最近更新倒序） |
| GET | `/api/chat/history?conversationId=` | 读取会话历史消息 |

### 智能体

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/agent` | 智能体列表 |
| GET | `/api/agent/{id}` | 单个智能体详情 |
| GET | `/api/agent/by-code/{code}` | 按编码查询（多智能体协作路由入口） |
| POST | `/api/agent` | 创建智能体 |
| PUT | `/api/agent/{id}` | 更新智能体 |
| DELETE | `/api/agent/{id}` | 删除智能体（自动解除会话绑定） |
| POST | `/api/agent/generate-prompt` | 用 AI 根据名称/描述生成系统提示词（不落库） |

## 数据库表

| 表 | 说明 |
|---|---|
| `agent` | 智能体：名称、编码（唯一）、图标、描述、系统提示词、`param_schema`（参数清单 JSON）、模型/温度覆盖 |
| `conversation` | 会话：标题、绑定 agent_id、`planner` 标记、`agent_bind_source`、滚动摘要 `summary`/`core_facts`/`summarized_count` |
| `chat_message` | 消息明细：conversation_id、role（user/assistant）、content、created_at |

## 目录结构

```
src/main/java/org/luo/
├── controller/        # AgentController / ConversationController / ChatController（SSE）
├── service/           # ChatService（编排门面）、AgentRouter、ParamFillingService、
│                      # PlannerService、MemoryMergeService、ConversationService、AgentService、PromptService
├── memory/            # DbChatMemory（Spring AI ChatMemory 的 DB 实现）
├── tool/              # ToolRegistry（全局能力池）、WeatherTools、DateResolver、ToolProvider
├── advisor/           # ToolUsageLoggingAdvisor（工具使用监控）
├── entity/ mapper/    # 实体与 MyBatis-Plus Mapper
├── dto/ config/ exception/
src/main/resources/
├── application.yaml   # 数据源 / LLM 配置
├── sql/schema.sql     # 建表（幂等）
├── sql/alter.sql      # 旧库补列
└── static/            # 前端（index.html / js/app.js / css/style.css）
```

## 开发备注

- **本机编译**：本机 `mvnw` 脚本已损坏，使用真实 Maven 离线编译（仓库在 `D:\software\Java\maven\apache-maven-3.9.16`），命令：
  ```bash
  cd /d/MyProject/My_Agent && MAVEN_HOME="D:/software/Java/maven/apache-maven-3.9.16" && PROJ="D:/MyProject/My_Agent" && \
  java -classpath "${MAVEN_HOME}/boot/plexus-classworlds-2.11.0.jar" -Dmaven.home="${MAVEN_HOME}" \
    -Dmaven.multiModuleProjectDirectory="${PROJ}" -Dclassworlds.conf="${MAVEN_HOME}/bin/m2.conf" \
    org.codehaus.plexus.classworlds.launcher.Launcher -q compile -o
  ```
- **已知缺陷**：Spring AI 2.0.0 的 `stream()` 合并工具调用分片时抛 `NoSuchElementException`（`OpenAiChatModel$ChunkMerger` 对工具调用 `Optional` 直接 `.get()`）。带工具的对话必须用 `call()` 走完工具循环再切片模拟流式。
- **代码约定**：LLM 返回 JSON 的解析统一用 Hutool `cn.hutool.json.JSONUtil`，不引入 Jackson `ObjectMapper` 解析路径。
- **生效方式**：改动 Java/SQL 需重启服务（`alter.sql` 负责旧库自动补列）；改动前端后 `mvn compile` 同步 `target/classes`，浏览器 Ctrl+F5。
- `agent_code.md` 由系统自动生成（智能体提示词汇总），修改请通过页面操作，勿直接编辑。
