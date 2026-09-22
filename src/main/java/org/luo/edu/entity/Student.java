package org.luo.edu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 学生表（business.sql）。 */
@Data
@NoArgsConstructor
@TableName("student")
public class Student {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String name;

    /** 男/女。 */
    private String gender;

    /** 所属班级，关联 class.id。 */
    private Integer classId;

    private LocalDate birthDate;

    /** 学号（唯一）。 */
    private String studentNo;

    /** 入学年份。 */
    private Integer enrollYear;

    /** 是否寄宿：0 走读 / 1 寄宿。 */
    private Integer isBoarding;

    private String parentName;

    private String parentPhone;

    private String address;

    /** 毕业去向（在读为 null）。 */
    private String graduationTo;
}
