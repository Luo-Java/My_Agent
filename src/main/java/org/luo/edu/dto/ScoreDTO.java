package org.luo.edu.dto;

import lombok.Data;
import org.luo.common.BaseBO;

/** 成绩分页条件：页码/每页条数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
public class ScoreDTO extends BaseBO {

    private Integer classId; // 班级（按学生关联）
    private Integer subjectId; // 科目（按考试关联）
    private Integer studentId; // 学生
}
