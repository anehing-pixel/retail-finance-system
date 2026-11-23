package com.company.retail.auth.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 员工账号 Mapper（auth-service 真相源 core.users / core.user_roles，R02 §3 / V2 迁移）。
 *
 * <p>只服务登录：查用户、查角色。角色→权限展开在 {@code RolePermissions}。</p>
 */
public interface StaffUserMapper {

    /** 查用户（返回行，由仓储层组装领域记录）。 */
    @Select("SELECT user_id, username, password_hash, display_name, enabled FROM core.users "
            + "WHERE username = #{username}")
    List<Map<String, Object>> findUserRows(@Param("username") String username);

    /** 查角色（单列，直接映射为 String）。 */
    @Select("SELECT role_key FROM core.user_roles WHERE user_id = #{userId} ORDER BY role_key")
    List<String> findRoles(@Param("userId") long userId);
}
