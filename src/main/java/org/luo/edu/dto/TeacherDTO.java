package org.luo.edu.dto;

import lombok.Data;
import org.luo.common.BaseBO;

/** 老师分页条件：页码/每页条数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
public class TeacherDTO extends BaseBO {

    private String name; // 姓名，模糊匹配
    private Integer subjectId; // 主教学科，精确匹配
}
