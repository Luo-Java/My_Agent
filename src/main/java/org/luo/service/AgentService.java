package org.luo.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.dto.UpsertAgentRequest;
import org.luo.entity.Agent;
import org.luo.entity.Conversation;
import org.luo.exception.AiBusinessException;
import org.luo.exception.AiErrorCode;
import org.luo.mapper.AgentMapper;
import org.luo.mapper.ConversationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能体（Agent）业务服务：负责 agent 表的增删改查，以及删除时解除会话绑定。
 * <p>
 * 每次增删改后会把全部智能体的系统提示词同步写入本地文件 {@code agent_code.md}
 * （位于应用启动工作目录，通常是项目根目录），供人工查阅/备份。
 */
@Slf4j
@Service
public class AgentService {

    /** 智能体提示词本地归档文件（相对应用启动工作目录）。 */
    private static final Path AGENT_PROMPT_FILE = Path.of("agent_code.md");

    private final AgentMapper agentMapper;
    private final ConversationMapper conversationMapper;

    public AgentService(AgentMapper agentMapper, ConversationMapper conversationMapper) {
        this.agentMapper = agentMapper;
        this.conversationMapper = conversationMapper;
    }

    /**
     * 创建智能体（ID 由数据库自增生成）。
     *
     * @param req 智能体数据（名称、描述、人设、模型参数等）
     * @return 新建的智能体记录（包含数据库生成的 id）
     */
    @Transactional
    public Agent createAgent(UpsertAgentRequest req) {
        if (req == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "请求参数不能为空");
        }
        log.info("创建智能体：名称={}", req.name());
        validateRequired(req, req.agentCode());
        Agent a = new Agent();
        applyFields(a, req);
        ensureUniqueCode(a);
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        agentMapper.insert(a);
        syncPromptFile();
        return a;
    }

    /**
     * 更新智能体。
     *
     * @param req 待更新的智能体数据（必须包含 id）
     * @return 更新后的智能体记录
     * @throws AiBusinessException ID 为空或智能体不存在时抛出
     */
    @Transactional
    public Agent updateAgent(UpsertAgentRequest req) {
        if (req.id() == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "智能体 ID 不能为空");
        }
        Agent a = agentMapper.selectById(req.id());
        if (a == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "智能体不存在");
        }
        validateRequired(req, a.getAgentCode()); // 编辑不允许改编码，以库内既有编码为准校验
        log.info("更新智能体：id={}", req.id());
        String oldCode = a.getAgentCode(); // 编辑不允许修改编码：先记下原值，映射后恢复
        applyFields(a, req);
        a.setAgentCode(oldCode);
        ensureUniqueCode(a);
        a.setUpdatedAt(LocalDateTime.now());
        agentMapper.updateById(a);
        syncPromptFile();
        return a;
    }

    /**
     * 删除智能体，并解除所有会话对其的绑定（会话与消息保留，退回默认助手）。
     *
     * @param id 要删除的智能体 ID
     */
    @Transactional
    public void deleteAgent(Long id) {
        if (id == null) return;
        log.info("删除智能体：id={}", id);
        QueryWrapper<Conversation> qw = new QueryWrapper<>();
        qw.eq("agent_id", id);
        for (Conversation c : conversationMapper.selectList(qw)) {
            c.setAgentId(null);
            conversationMapper.updateById(c);
        }
        agentMapper.deleteById(id);
        syncPromptFile();
    }

    /**
     * 智能体列表，按最近更新时间倒序。
     *
     * @return 全部智能体列表
     */
    public List<Agent> listAgents() {
        QueryWrapper<Agent> qw = new QueryWrapper<>();
        qw.orderByDesc("updated_at");
        return agentMapper.selectList(qw);
    }

    /**
     * 根据 ID 查询单个智能体。
     *
     * @param id 智能体 ID
     * @return 智能体对象，不存在则返回 null
     */
    public Agent getAgent(Long id) {
        return agentMapper.selectById(id);
    }

    /**
     * 根据智能体编码（agent_code）查询单个智能体。
     * <p>
     * 供多智能体协作时按编码路由到具体智能体（如「把这个问题交给 translator 处理」）。
     *
     * @param code 智能体唯一编码
     * @return 智能体对象，不存在则返回 null
     */
    public Agent getByCode(String code) {
        if (code == null || code.isBlank()) return null;
        return agentMapper.selectOne(new QueryWrapper<Agent>()
                .eq("agent_code", code.trim())
                .last("LIMIT 1"));
    }

    /**
     * 校验创建/更新智能体时必填字段非空：智能体编码、描述、系统提示词均不可为空。
     *
     * @param req           请求对象（描述/提示词取自 req）
     * @param effectiveCode 生效的编码（创建时为 req.agentCode；更新时取库内既有编码）
     * @throws AiBusinessException 任一必填为空时抛出
     */
    private void validateRequired(UpsertAgentRequest req, String effectiveCode) {
        if (effectiveCode == null || effectiveCode.isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "智能体编码(agentCode)不能为空");
        }
        if (req.description() == null || req.description().isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "智能体描述不能为空");
        }
        if (req.systemPrompt() == null || req.systemPrompt().isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "系统提示词不能为空");
        }
    }

    /**
     * 将请求对象的字段映射到已有的 Agent 实例上（跳过空值，trim 字符串字段）。
     * 这是内部辅助方法，不在业务层暴露。
     *
     * @param a 目标 Agent 实例
     * @param req 来源请求对象
     */
    private void applyFields(Agent a, UpsertAgentRequest req) {
        if (req.name() != null) a.setName(req.name().trim());
        // agentCode：创建时若提供则采用（已校验非空）；更新时即便 req 带值也会被上层恢复为库内原值
        if (req.agentCode() != null && !req.agentCode().isBlank()) a.setAgentCode(req.agentCode().trim());
        if (req.icon() != null) a.setIcon(req.icon().trim());
        if (req.description() != null) a.setDescription(req.description().trim());
        if (req.systemPrompt() != null) a.setSystemPrompt(req.systemPrompt());
        if (req.paramSchema() != null) a.setParamSchema(req.paramSchema());
        a.setModel(req.model() != null && !req.model().isBlank() ? req.model().trim() : null);
        a.setTemperature(req.temperature());
        a.setAvatarColor(req.avatarColor() != null && !req.avatarColor().isBlank() ? req.avatarColor().trim() : null);
    }

    /**
     * 确保智能体编码非空且全局唯一：编码为空（创建未填或历史数据）时由名称自动生成；
     * 更新时已存在的编码保持原值不变（编辑不允许修改编码）。
     *
     * @param a 智能体（id 为空表示创建，非空表示更新）
     * @throws AiBusinessException 编码与其他智能体冲突时抛出
     */
    private void ensureUniqueCode(Agent a) {
        if (a.getAgentCode() == null || a.getAgentCode().isBlank()) {
            a.setAgentCode(genAgentCode(a.getName()));
        }
        QueryWrapper<Agent> qw = new QueryWrapper<>();
        qw.eq("agent_code", a.getAgentCode());
        if (a.getId() != null) qw.ne("id", a.getId());
        if (agentMapper.selectCount(qw) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "智能体编码已存在：" + a.getAgentCode());
        }
    }

    /**
     * 根据名称生成智能体编码：名称转小写、非字母数字转连字符；冲突时追加 -2/-3… 后缀，空名兜底 agent。
     */
    private String genAgentCode(String name) {
        String base = name == null ? "" : name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "agent";
        if (base.length() > 40) base = base.substring(0, 40);
        String code = base;
        int n = 2;
        while (agentMapper.selectCount(new QueryWrapper<Agent>().eq("agent_code", code)) > 0) {
            code = base + "-" + n++;
        }
        return code;
    }

    /**
     * 把当前全部智能体的系统提示词同步写入本地文件 {@code agent_code.md}（Markdown 格式）。
     * 在创建 / 更新 / 删除智能体后调用，保证文件始终与数据库一致。
     * 写文件失败不阻断业务，仅记录错误日志。
     */
    private void syncPromptFile() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# 智能体提示词库\n\n");
            sb.append("> 本文件由系统自动生成，汇总全部智能体的系统提示词；如需修改请通过页面操作，勿直接编辑本文件。\n\n");
            sb.append("生成时间：").append(LocalDateTime.now()).append("\n\n");
            List<Agent> list = listAgents();
            if (list.isEmpty()) {
                sb.append("（当前没有智能体）\n");
            } else {
                int i = 0;
                for (Agent a : list) {
                    i++;
                    sb.append("---\n\n");
                    sb.append("## ").append(i).append(". ").append(a.getName()).append("\n\n");
                    sb.append("- 编码（agent_code）：").append(a.getAgentCode() == null || a.getAgentCode().isBlank() ? "（无）" : a.getAgentCode()).append("\n");
                    sb.append("- 图标：").append(a.getIcon() == null || a.getIcon().isBlank() ? "（无）" : a.getIcon()).append("\n");
                    sb.append("- 描述：").append(a.getDescription() == null || a.getDescription().isBlank() ? "（无）" : a.getDescription()).append("\n");
                    sb.append("- 模型：").append(a.getModel() == null || a.getModel().isBlank() ? "（默认模型）" : a.getModel()).append("\n");
                    sb.append("- 温度：").append(a.getTemperature() == null ? "（默认）" : a.getTemperature()).append("\n");
                    sb.append("- 主题色：").append(a.getAvatarColor() == null || a.getAvatarColor().isBlank() ? "（默认）" : a.getAvatarColor()).append("\n\n");
                    sb.append("系统提示词：\n\n```text\n");
                    sb.append(a.getSystemPrompt() == null ? "" : a.getSystemPrompt()).append("\n```\n\n");
                }
            }
            Files.writeString(AGENT_PROMPT_FILE, sb.toString(), StandardCharsets.UTF_8);
            log.info("智能体提示词已同步到本地文件：{}", AGENT_PROMPT_FILE.toAbsolutePath());
        } catch (IOException e) {
            log.error("同步智能体提示词到本地文件 {} 失败", AGENT_PROMPT_FILE.toAbsolutePath(), e);
        }
    }
}
