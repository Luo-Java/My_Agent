package org.luo.edu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.StudentDTO;
import org.luo.edu.entity.Student;
import org.luo.edu.vo.OptionVO;
import org.luo.edu.vo.StudentVO;
import java.util.List;

/**
 * 学生业务：单表 CRUD/批量由 IService 提供，本接口只声明学生自己的分页、下拉选项与写操作。
 */
public interface StudentService extends IService<Student> {

    /** 学生分页（join 出可读名，SQL 见 Mapper XML）。 */
    PageResult<StudentVO> page(StudentDTO dto);

    /** 下拉选项：id + 可读文案（供其他表的表单与筛选用）。 */
    List<OptionVO> options();

    /** 新增：先做唯一性校验，通过后落库并回填自增主键。 */
    Student saveStudent(Student e);

    /** 编辑：id 取自入参，先做唯一性校验（排除自身），不存在则 404。 */
    Student updateStudent(Student e);

    /** 删除：先校验是否被其他表引用，未被引用才删，不存在则 404。 */
    void deleteStudentById(Integer id);
}
