package org.luo.edu.vo;

import lombok.Data;

import java.time.LocalTime;

/** 班级课表行：跨表 join 后的扁平投影，列名与 XML 里的 AS 别名一一对应。 */
@Data
public class TimetableVO {

    /** 排课ID（course_arrangement.id） */
    private Integer id;

    /** 班级名（join class） */
    private String className;

    /** 科目名（join subject） */
    private String subjectName;

    /** 授课老师姓名（join teacher） */
    private String teacherName;

    /** 星期几：1 周一 ~ 7 周日 */
    private Integer dayOfWeek;

    /** 教室（course_arrangement.classroom） */
    private String classroom;

    /** 开始节次序号（join period） */
    private Integer startPeriod;

    /** 开始时间（join period） */
    private LocalTime startTime;

    /** 结束节次序号（join period） */
    private Integer endPeriod;

    /** 结束时间（join period） */
    private LocalTime endTime;
}
