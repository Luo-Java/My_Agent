package org.luo.edu.vo;

import lombok.Data;
import org.luo.edu.entity.Teacher;

/** 老师列表项：单表字段 + 跨表 join 出的可读名，前端不再做 id → 名称翻译。 */
@Data
public class TeacherVO extends Teacher {

    /** 主教学科名（join subject） */
    private String subjectName;
}
