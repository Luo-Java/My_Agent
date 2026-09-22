package org.luo.edu.dto;

import lombok.Data;
import org.luo.common.BaseBO;

/** 班级课表分页条件：页码/每页条数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
public class TimetableDTO extends BaseBO {

    private Integer classId; // 班级
    private Integer teacherId; // 授课老师
    private Integer dayOfWeek; // 星期（1-7）
}
