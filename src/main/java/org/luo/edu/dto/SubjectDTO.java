package org.luo.edu.dto;

import lombok.Data;
import org.luo.common.BaseBO;

/** 科目分页条件：页码/每页条数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
public class SubjectDTO extends BaseBO {

    private String name; // 科目名称，模糊匹配
}
