package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * 类别仓储（挑战需求 A · A.1.2 两级类别）。
 *
 * <p>返回顺序统一按 id 升序，保证前端展示稳定（类别创建后顺序不跳动）。
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 一级类别（parentId 为空） */
    List<Category> findByParentIdIsNullOrderByIdAsc();

    /** 某一级类别下的二级类别 */
    List<Category> findByParentIdOrderByIdAsc(Long parentId);

    /** 同一父级下是否已有同名类别（防止重复建同名类别） */
    boolean existsByParentIdAndName(Long parentId, String name);

    /** 是否存在挂着子类别的一级类别（删除一级类别前的校验） */
    boolean existsByParentId(Long parentId);

    /** 某一级类别下的二级类别数量 */
    long countByParentId(Long parentId);

    /** 按名称与父级查找（迁移脚本定位「未分类 / 历史商品」用） */
    java.util.Optional<Category> findByParentIdAndName(Long parentId, String name);
}
