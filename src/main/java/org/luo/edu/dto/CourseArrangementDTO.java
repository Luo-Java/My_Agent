package org.luo.edu.dto;

import lombok.Data;
import org.luo.common.BaseBO;

/** 排课分页条件：页码/每页条数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
public class CourseArrangementDTO extends BaseBO {

    private Integer classId; // 上课班级（按课程关联）
    private Integer teacherId; // 授课老师（按课程关联）
}
