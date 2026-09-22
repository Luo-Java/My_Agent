package org.luo.common;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

/**
 * 分页请求基类：页码与每页条数的兜底、限幅只在这一处做，子类只声明业务筛选条件。
 * <p>
 * 各表分页入参都继承本类，服务层直接用 {@link #toPage()} 取分页对象，不再各自判空。
 */
@Data
public class BaseBO {

    /** 每页条数缺省值。 */
    public static final int DEFAULT_SIZE = 10;

    /** 每页条数上限，防止一次把整表拉出来。 */
    public static final int MAX_SIZE = 100;

    private Integer page;

    private Integer size;

    /** 生效页码：缺省 1，0 与负数同样按 1 处理。 */
    public long pageNo() {
        return page == null || page < 1 ? 1L : page;
    }

    /** 生效每页条数：缺省 {@link #DEFAULT_SIZE}，超过 {@link #MAX_SIZE} 按上限收敛。 */
    public long pageSize() {
        return size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /** 供 Mapper 使用的分页对象。 */
    public <T> Page<T> toPage() {
        return new Page<>(pageNo(), pageSize());
    }
}
