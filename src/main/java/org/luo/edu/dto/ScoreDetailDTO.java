package org.luo.edu.dto;

import lombok.Data;
import org.luo.common.BaseBO;

/** 成绩明细分页条件：页码/每页条数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
public class ScoreDetailDTO extends BaseBO {

    private Integer classId; // 班级
    private Integer subjectId; // 科目
    private Integer semesterId; // 学期
    private String keyword; // 学生姓名或学号，模糊匹配
}
