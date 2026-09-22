package org.luo.edu.vo;

import lombok.Data;
import org.luo.edu.entity.Clazz;

/** 班级列表项：单表字段 + 跨表 join 出的可读名，前端不再做 id → 名称翻译。 */
@Data
public class ClazzVO extends Clazz {

    /** 班主任姓名（join teacher） */
    private String headTeacherName;
}
