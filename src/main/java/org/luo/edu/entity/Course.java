package org.luo.edu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 课程表（business.sql）。 */
@Data
@NoArgsConstructor
@TableName("course")
public class Course {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 科目，关联 subject.id。 */
    private Integer subjectId;

    /** 授课老师，关联 teacher.id。 */
    private Integer teacherId;

    /** 上课班级，关联 class.id。 */
    private Integer classId;

    /** 学期，关联 semester.id。 */
    private Integer semesterId;
}
