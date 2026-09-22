package org.luo.edu.dto;

import lombok.Data;
import org.luo.common.BaseBO;

/** 学生分页条件：页码/每页条数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
public class StudentDTO extends BaseBO {

    private String name; // 姓名，模糊匹配
    private Integer classId; // 班级，精确匹配
}
