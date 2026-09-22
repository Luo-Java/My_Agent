package org.luo.edu.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 下拉选项：id + 可读文案。
 * <p>
 * 各表 {@code GET /list} 统一返回本类型，供其他表的表单下拉与列表筛选用。
 * 文案需要跨表信息的（课程/考试/排课/成绩）在各自 Mapper XML 里 concat 拼好，
 * 避免同一份拼装逻辑在前端再写一遍。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OptionVO {

    private Long id;

    private String label;
}
