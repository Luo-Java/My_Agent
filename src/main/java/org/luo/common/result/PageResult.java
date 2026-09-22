package org.luo.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Getter;

import java.util.List;

/**
 * 统一分页结果：{@code {records, total, page, size, pages}}。
 * <p>
 * 把 MyBatis-Plus 的 {@link IPage} 收敛成固定字段的对外契约，避免直接把框架分页对象
 * 序列化出去（其内部字段随框架版本变动）。字段名与前端既有约定保持一致。
 *
 * @param <T> 记录类型
 */
@Getter
public class PageResult<T> {

    private final List<T> records;
    private final long total;
    private final long page;
    private final long size;
    private final long pages;

    private PageResult(List<T> records, long total, long page, long size, long pages) {
        this.records = records;
        this.total = total;
        this.page = page;
        this.size = size;
        this.pages = pages;
    }

    /** 由 MyBatis-Plus 分页对象转换（记录列表取自分页对象）。 */
    public static <T> PageResult<T> of(IPage<T> p) {
        return of(p, p.getRecords());
    }

    /**
     * 由分页对象 + 记录列表转换。
     * <p>
     * Mapper 方法返回 {@code List} 时，分页插件只把 total/pages 写回入参 {@link IPage}，
     * 不负责回填 {@code records}（{@code IPage#getRecords()} 为 null），故需显式传入列表。
     */
    public static <T> PageResult<T> of(IPage<T> p, List<T> records) {
        return new PageResult<>(records, p.getTotal(), p.getCurrent(), p.getSize(), p.getPages());
    }

    /**
     * 由原始分页对象 + 转换后的记录列表构造。
     * <p>
     * 与 {@link #of(IPage, List)} 的区别：记录类型可与分页对象的元素类型不同，用于
     * 「按实体分页查询、对外输出 VO」的场景（{@code of} 的两个参数要求同一类型，无法直接表达）。
     * total / page / size / pages 原样搬运自查询结果。
     */
    public static <T> PageResult<T> ofMapped(IPage<?> source, List<T> records) {
        return new PageResult<>(records, source.getTotal(), source.getCurrent(), source.getSize(), source.getPages());
    }
}
