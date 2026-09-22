package org.luo.edu.dto;

import lombok.Data;
import org.luo.common.BaseBO;

/** 学期分页条件：页码/每页条数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
public class SemesterDTO extends BaseBO {

    private String name; // 学期名称，模糊匹配
}
