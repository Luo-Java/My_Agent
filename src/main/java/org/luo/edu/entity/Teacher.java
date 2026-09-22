package org.luo.edu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 老师表（business.sql）。 */
@Data
@NoArgsConstructor
@TableName("teacher")
public class Teacher {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String name;

    /** 男/女。 */
    private String gender;

    /** 主教学科，关联 subject.id。 */
    private Integer subjectId;

    /** 职称。 */
    private String title;

    private String phone;
}
