package org.luo.edu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 成绩表（business.sql）。 */
@Data
@NoArgsConstructor
@TableName("score")
public class Score {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 学生，关联 student.id。 */
    private Integer studentId;

    /** 考试，关联 exam.id。 */
    private Integer examId;

    private BigDecimal score;
}
