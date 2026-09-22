package org.luo.edu.dto;

import lombok.Data;
import org.luo.common.BaseBO;

/** 成绩统计分页条件：页码/每页条数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
public class ScoreStatsDTO extends BaseBO {

    private Integer classId; // 班级
    private Integer subjectId; // 科目
    private Integer semesterId; // 学期
}
