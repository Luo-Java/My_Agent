package org.luo.edu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 排课表（business.sql）。 */
@Data
@NoArgsConstructor
@TableName("course_arrangement")
public class CourseArrangement {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 课程，关联 course.id。 */
    private Integer courseId;

    /** 星期几：1 周一 ~ 7 周日。 */
    private Integer dayOfWeek;

    /** 开始节次，关联 period.id。 */
    private Integer startPeriodId;

    /** 结束节次（含），关联 period.id。 */
    private Integer endPeriodId;

    private String classroom;
}
