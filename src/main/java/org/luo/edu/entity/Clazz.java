package org.luo.edu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 班级表（business.sql）。表名用 Clazz 避免与 java.lang.Class 混淆。 */
@Data
@NoArgsConstructor
@TableName("class")
public class Clazz {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 班级名称，如 3年级2班。 */
    private String name;

    /** 年级 1-6。 */
    private Integer grade;

    /** 班主任，关联 teacher.id。 */
    private Integer headTeacherId;
}
