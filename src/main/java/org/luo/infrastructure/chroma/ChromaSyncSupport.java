package org.luo.infrastructure.chroma;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Chroma 副本同步的「提交后执行」统一入口。
 * <p>
 * <b>为什么写副本必须延后到 MySQL 事务提交之后</b>：Chroma 不参与 MySQL 事务。若在 {@code @Transactional}
 * 方法体内直接写副本，一旦 MySQL 回滚、副本已经变了——轻则留下<b>孤儿向量</b>，重则检索走 Chroma 优先、
 * 直接用副本 content 构造命中，返回库里<b>已不存在的 chunk</b>（幽灵引用）。安排到 {@code afterCommit} 后，
 * 副本只会跟随「已提交的事实」；最坏退化为副本短暂落后，可由 {@code POST /api/kb/chroma/sync} 幂等回填，
 * 方向始终是「MySQL 为源、副本追赶」。
 * <p>
 * <b>失败只 warn、绝不外抛</b>：{@code afterCommit} 里抛出的异常会冒到业务方法，把一次「已经提交成功」的写
 * 操作变成接口报错，反而误导调用方重试（重试会再写一遍）。无事务上下文（如回填接口）时立即执行。
 */
@Slf4j
@Component
public class ChromaSyncSupport {

    /**
     * 把副本写动作安排到当前事务提交之后执行；无事务时立即执行。
     *
     * @param action       动作描述，仅用于日志（例如「上传文件 xxx（kbId=1）」）
     * @param chromaAction 副本写动作；其内部异常被吞掉并降级为 warn
     */
    public void afterCommit(String action, Runnable chromaAction) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    run(action, chromaAction);
                }
            });
        } else {
            run(action, chromaAction);
        }
    }

    /** 执行副本动作并把异常降级为告警（MySQL 已是源，副本可回填）。 */
    private void run(String action, Runnable chromaAction) {
        try {
            chromaAction.run();
        } catch (Exception e) {
            log.warn("Chroma 副本同步失败（MySQL 已提交，可用 POST /api/kb/chroma/sync 幂等回填）：{}，原因={}",
                    action, e.getMessage());
        }
    }
}
