package org.luo.edu.dto;

import lombok.Data;
import org.luo.common.BaseBO;

/** 节次分页条件：页码/每页条数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
public class PeriodDTO extends BaseBO {

    private Integer periodNo; // 节次序号，精确匹配
}
