package org.luo.edu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/** 节次表（business.sql）。 */
@Data
@NoArgsConstructor
@TableName("period")
public class Period {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 节次序号，如 1~8（唯一）。 */
    private Integer periodNo;

    private LocalTime startTime;

    private LocalTime endTime;
}
