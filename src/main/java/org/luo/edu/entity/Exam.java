package org.luo.edu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/** 考试表（business.sql）。 */
@Data
@NoArgsConstructor
@TableName("exam")
public class Exam {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 学期，关联 semester.id。 */
    private Integer semesterId;

    /** 班级，关联 class.id。 */
    private Integer classId;

    /** 科目，关联 subject.id。 */
    private Integer subjectId;

    private LocalDate examDate;

    private LocalTime examTime;
}
