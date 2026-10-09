package org.luo.ai.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.EvalBatchResult;
import org.luo.ai.dto.EvalBatchSummary;
import org.luo.ai.dto.EvalCompare;
import org.luo.ai.dto.PromptGateStatus;
import org.luo.ai.entity.PromptSnapshot;
import org.luo.ai.mapper.PromptSnapshotMapper;
import org.luo.ai.properties.PromptGateProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 提示词改动门禁：在提示词变更的那一刻给出「变了没有 / 跑没跑 / 变好还是变差」的结论。
 * <p>
 * <b>它补的是哪个缺口</b>：{@code EvalService} 已经能做「跑批 + 跨批对比 + 优先看 broken」，但<b>只有手动
 * 触发</b>。于是真实流程是「改了 prompts.yaml → 上线 → 觉得哪里不对 → 想起来还有评测 → 手动跑一批」，
 * 中间那段「不知道变了没有」全靠人记。本服务的价值不在跑批（那是 {@code EvalService} 的活），而在
 * <b>把「提示词内容」与「评测结论」绑起来</b>：
 * <ul>
 *   <li>启动或按需时算出 {@code prompts.yaml} 的内容指纹；</li>
 *   <li>与最近一次快照比对 —— 一样就什么都不做，不一样就在 {@code prompt_snapshot} 留一条 {@code PENDING}；</li>
 *   <li>开启 {@code agent.prompt-gate.auto-run} 时顺带把批跑掉，用 {@code EvalService.compare} 判 PASS / DEGRADED；</li>
 *   <li>结论经 {@code GET /api/eval/gate} 暴露给评测面板。</li>
 * </ul>
 * <b>为什么指纹比对不花钱</b>：它只读文件、算哈希，不碰模型；所以 {@code enabled} 可以默认开。真正花钱的是
 * {@code auto-run}（跑批 = 真实模型调用），默认关，必须显式打开。
 * <p>
 * <b>刻意不做的事</b>：不 diff 内容（只报「变了」）、不在启动时<b>阻塞</b>等待跑批结果之外的额外校验、
 * 不因 DEGRADED 而阻止应用启动（这是本地开发项目，硬拦等于把人挡在门外；「拒绝合入」的正确落点是 CI，
 * 本服务只负责把结论摆出来）。
 */
@Slf4j
@Service
public class PromptGateService {

    /** 被监控的提示词文件（经 spring.config.import 导入，位于 classpath 根）。 */
    private static final String PROMPTS_RESOURCE = "classpath:prompts.yaml";

    /** 指纹取 SHA-256 十六进制的前若干位：够避免碰撞，又不至于在界面上显示成一长串。 */
    private static final int FINGERPRINT_LEN = 16;

    /** 保留的快照条数（含最新）：门禁的价值在「和上一版比」，不需要长期归档。 */
    private static final int KEEP_SNAPSHOTS = 30;

    /** 前端展开历史时返回的条数上限。 */
    private static final int RECENT_LIMIT = 10;

    private final PromptGateProperties props;
    private final ResourceLoader resourceLoader;
    private final EvalService evalService;
    private final PromptSnapshotMapper snapshotMapper;

    public PromptGateService(PromptGateProperties props,
                             ResourceLoader resourceLoader,
                             EvalService evalService,
                             PromptSnapshotMapper snapshotMapper) {
        this.props = props;
        this.resourceLoader = resourceLoader;
        this.evalService = evalService;
        this.snapshotMapper = snapshotMapper;
    }

    // ------------------------------------------------------------------
    // 启动自检
    // ------------------------------------------------------------------

    /**
     * 应用就绪后比对一次指纹：变更即留 {@code PENDING} 快照并落 WARN，开启 {@code auto-run} 时顺带跑批。
     * <p>
     * 用 {@link ApplicationReadyEvent} 而非 {@code @PostConstruct}：此时 Web 容器已就绪，即便开了自动跑批
     * 也只是延后「Started」日志，不影响服务可用；且失败完全不影响启动（本方法自己吞异常）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!props.enabledOn()) return;
        try {
            String fp = currentFingerprint();
            if (fp == null) {
                log.warn("提示词门禁：读不到 {}，本轮跳过指纹比对", PROMPTS_RESOURCE);
                return;
            }
            PromptSnapshot latest = latest();
            if (latest != null && fp.equals(latest.getFingerprint()) && !isPending(latest)) {
                log.info("提示词门禁：与最近一次验证过的版本一致（{}），无需处理", fp);
                return;
            }
            if (props.autoRunOn()) {
                log.warn("提示词门禁：检测到提示词变更（指纹 {}），按 auto-run 配置自动跑批验证", fp);
                verify(true);
            } else {
                log.warn("提示词门禁：检测到提示词变更（指纹 {}），尚未验证。"
                        + "跑 POST /api/eval/gate 验证，或将 agent.prompt-gate.auto-run 置 true 自动跑。", fp);
                verify(false);
            }
        } catch (Exception e) {
            // 门禁是旁路：它自己坏掉绝不能影响服务启动
            log.error("提示词门禁启动自检失败（不影响服务可用）", e);
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 当前门禁状态（前端据此在评测面板顶部渲染横幅）。 */
    public PromptGateStatus status() {
        String fp = currentFingerprint();
        PromptSnapshot latest = latest();
        boolean changed = latest == null || (fp != null && !fp.equals(latest.getFingerprint()));
        boolean verified = latest != null && fp != null && fp.equals(latest.getFingerprint()) && !isPending(latest);
        return new PromptGateStatus(fp, changed, verified, latest, recent());
    }

    // ------------------------------------------------------------------
    // 跑批验证
    // ------------------------------------------------------------------

    /**
     * 验证当前提示词：算出指纹 → （可选）跑一批 → 与上一批对比 → 落快照。
     *
     * @param runBatch true = 真跑批（真实模型调用、计入成本）；false = 只记录「已变更待验证」（零成本）
     * @return 本次落下的快照（未跑批且内容未变时，返回最近那条既有快照，不重复写行）
     */
    public synchronized PromptSnapshot verify(boolean runBatch) {
        String fp = currentFingerprint();
        if (fp == null) {
            return recordError(null, "读不到提示词文件：" + PROMPTS_RESOURCE);
        }
        PromptSnapshot existing = latest();
        if (!runBatch) {
            // 内容与最近快照一致 → 不重复写 PENDING（否则每次重启都多一行噪声）
            if (existing != null && fp.equals(existing.getFingerprint())) {
                return existing;
            }
            return insert(PromptSnapshot.VERDICT_PENDING, fp, null, null, null, null, null, null, null,
                    "{\"note\":\"检测到提示词变更，尚未跑批验证\"}");
        }

        // 上一批必须在跑批之前取：跑完再取会拿到自己
        String prevBatchId = latestBatchId();
        EvalBatchResult result;
        try {
            result = evalService.run(null);
        } catch (Exception e) {
            log.warn("提示词门禁跑批失败：{}", e.getMessage());
            return recordError(fp, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        EvalCompare cmp = null;
        if (prevBatchId != null && !prevBatchId.equals(result.batchId())) {
            try {
                cmp = evalService.compare(prevBatchId, result.batchId());
            } catch (Exception e) {
                // 上一批可能全是配置错误（compare 会 404）——退化成本批自评，不当作失败
                log.warn("提示词门禁：与上一批对比失败，本批只给出自身结果：{}", e.getMessage());
            }
        }
        int broken = cmp == null ? 0 : cmp.broken().size();
        int fixed = cmp == null ? 0 : cmp.fixed().size();
        String verdict = decide(result.failed(), broken);
        String detail = detailJson(cmp, null);
        log.info("提示词门禁结论：{}（指纹 {}，批次 {}，通过 {}/{}，broken {}，fixed {}）",
                verdict, fp, result.batchId(), result.passed(), result.total(), broken, fixed);
        return insert(verdict, fp, result.batchId(), result.total(), result.passed(), result.failed(),
                result.configErrors(), broken, fixed, detail);
    }

    /** 结论判定：有 broken 即劣化；无 broken 但仍有失败 → 仍未过；全过 → 通过。 */
    private String decide(int failed, int broken) {
        if (broken > 0) return PromptSnapshot.VERDICT_DEGRADED;
        if (failed > 0) return PromptSnapshot.VERDICT_STILL_FAILED;
        return PromptSnapshot.VERDICT_PASS;
    }

    /** 该快照是否为「等验证」态（PENDING 或跑批失败）。 */
    private static boolean isPending(PromptSnapshot s) {
        return PromptSnapshot.VERDICT_PENDING.equals(s.getVerdict())
                || PromptSnapshot.VERDICT_ERROR.equals(s.getVerdict());
    }

    // ------------------------------------------------------------------
    // 指纹
    // ------------------------------------------------------------------

    /**
     * 当前 {@code prompts.yaml} 的内容指纹（SHA-256 前 {@value #FINGERPRINT_LEN} 位十六进制）；
     * 文件不存在或读失败返回 {@code null}（调用方据此跳过比对，不把「读不到」误判成「变了」）。
     */
    public String currentFingerprint() {
        Resource res = resourceLoader.getResource(PROMPTS_RESOURCE);
        if (!res.exists()) return null;
        try (InputStream in = res.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, FINGERPRINT_LEN);
        } catch (Exception e) {
            log.warn("提示词指纹计算失败：{}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 落库
    // ------------------------------------------------------------------

    /** 最近一条快照（无记录返回 null）。 */
    private PromptSnapshot latest() {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<PromptSnapshot>()
                .orderByDesc(PromptSnapshot::getId).last("LIMIT 1"));
    }

    /** 最近若干条快照（时间倒序）。 */
    private List<PromptSnapshot> recent() {
        return snapshotMapper.selectList(new LambdaQueryWrapper<PromptSnapshot>()
                .orderByDesc(PromptSnapshot::getId).last("LIMIT " + RECENT_LIMIT));
    }

    /** 最近一个评测批次 ID（无批次返回 null）。 */
    private String latestBatchId() {
        List<EvalBatchSummary> batches = evalService.listBatches();
        return batches.isEmpty() ? null : batches.get(0).getBatchId();
    }

    /** 记录一条「跑批本身失败」的快照。 */
    private PromptSnapshot recordError(String fp, String message) {
        return insert(PromptSnapshot.VERDICT_ERROR, fp, null, null, null, null, null, null, null,
                detailJson(null, message));
    }

    /** 明细 JSON：broken / fixed 用例名清单（可为空）+ 可选错误说明。 */
    private static String detailJson(EvalCompare cmp, String error) {
        JSONObject o = new JSONObject();
        JSONArray broken = new JSONArray();
        JSONArray fixed = new JSONArray();
        if (cmp != null) {
            broken.addAll(cmp.broken());
            fixed.addAll(cmp.fixed());
        }
        o.set("broken", broken);
        o.set("fixed", fixed);
        if (error != null) o.set("error", error);
        return o.toString();
    }

    /** 写一行快照并清理旧行（落库失败只记日志：结论已经算出来了，不该因为存不下就不返回）。 */
    private PromptSnapshot insert(String verdict, String fp, String batchId, Integer total, Integer passed,
                                  Integer failed, Integer configError, Integer broken, Integer fixed,
                                  String detailJson) {
        PromptSnapshot s = new PromptSnapshot();
        s.setVerdict(verdict);
        s.setFingerprint(fp);
        s.setBatchId(batchId);
        s.setTotal(total);
        s.setPassed(passed);
        s.setFailed(failed);
        s.setConfigError(configError);
        s.setBrokenCount(broken);
        s.setFixedCount(fixed);
        s.setDetailJson(detailJson);
        s.setCreatedAt(LocalDateTime.now());
        try {
            snapshotMapper.insert(s);
            snapshotMapper.delete(new LambdaQueryWrapper<PromptSnapshot>()
                    .lt(PromptSnapshot::getId, s.getId() - KEEP_SNAPSHOTS + 1));
        } catch (Exception e) {
            log.error("提示词快照落库失败（结论已返回，仅未持久化）", e);
        }
        return s;
    }
}
