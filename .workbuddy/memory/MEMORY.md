# 项目长期记忆（My_Agent）

## 约定与偏好
- **解析 LLM 返回的 JSON 不用 Jackson `ObjectMapper`**：用户明确"不要使用 ObjectMapper 处理结果"。改用 **Hutool** 的 `cn.hutool.json.JSONUtil` / `JSONObject` 解析（已在 pom.xml 引入 `cn.hutool:hutool-all:5.8.38`）。跨轮参数抽取、路由结果等一律 Hutool，不引入 ObjectMapper 解析路径。
- 用户偏好直接执行、小步快跑迭代；每次改动后必须真实编译验证（本机 Maven 在 `D:\software\Java\maven`，本地仓库同路径 `repository`，离线 `-o` 可用，启动器用 `java -cp boot/plexus-classworlds-2.11.0.jar` 绕过损坏的 mvn 脚本）。

## 已知缺陷 / 坑
- **Spring AI 2.0.0：`stream()` + `@Tool` 必崩 `NoSuchElementException`**（`OpenAiChatModel$ChunkMerger` 对工具调用 `Optional` 直接 `.get()`）。带工具的智能体（天气）流式输出要改用 `call()` 走完工具循环拿完整答案，再分块模拟流式（`Flux.fromIterable(...).delayElements(...)`）。`call()` 路径无此问题。

## 架构要点
- 多 Agent 平台（Spring Boot 4 + Spring AI 2.0 + MyBatis-Plus）。`ChatService` 为编排门面，参数补全/路由/记忆合并/提示词分别在 `ParamFillingService`/`AgentRouter`/`MemoryMergeService`/`PromptService`。
- Agent 声明参数：`paramSchema`（JSON 数组）驱动对话中的追问补全，追问上限 `ParamFillingService.MAX_CLARIFY=3`；跨轮参数靠 AI 自带记忆(DBChatMemory)累积，不改动 Conversation 结构。
