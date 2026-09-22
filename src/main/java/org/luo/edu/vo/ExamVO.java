package org.luo.edu.vo;

import lombok.Data;
import org.luo.edu.entity.Exam;

/** 考试列表项：单表字段 + 跨表 join 出的可读名，前端不再做 id → 名称翻译。 */
@Data
public class ExamVO extends Exam {

    /** 班级名（join class） */
    private String className;

    /** 科目名（join subject） */
    private String subjectName;

    /** 学期名（join semester） */
    private String semesterName;
}
