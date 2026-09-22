package org.luo.edu.vo;

import lombok.Data;
import org.luo.edu.entity.CourseArrangement;

/** 排课列表项：单表字段 + 跨表 join 出的可读名，前端不再做 id → 名称翻译。 */
@Data
public class CourseArrangementVO extends CourseArrangement {

    /** 课程可读描述（班级 · 科目 · 老师 · 学期） */
    private String courseLabel;

    /** 开始节次可读描述（第 N 节 + 起始时间） */
    private String startPeriodLabel;

    /** 结束节次可读描述（第 N 节 + 结束时间） */
    private String endPeriodLabel;
}
