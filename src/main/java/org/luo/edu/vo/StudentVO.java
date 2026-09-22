package org.luo.edu.vo;

import lombok.Data;
import org.luo.edu.entity.Student;

/** 学生列表项：单表字段 + 跨表 join 出的可读名，前端不再做 id → 名称翻译。 */
@Data
public class StudentVO extends Student {

    /** 班级名（join class） */
    private String className;
}
