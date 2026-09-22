package org.luo.edu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 学期表（business.sql）。 */
@Data
@NoArgsConstructor
@TableName("semester")
public class Semester {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 学期名称，如 2025-2026-1（唯一）。 */
    private String name;

    private LocalDate startDate;

    private LocalDate endDate;
}
