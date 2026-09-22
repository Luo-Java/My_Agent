package org.luo.edu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 科目表（business.sql）。 */
@Data
@NoArgsConstructor
@TableName("subject")
public class Subject {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String name;

    /** 科目编码（唯一）。 */
    private String code;
}
