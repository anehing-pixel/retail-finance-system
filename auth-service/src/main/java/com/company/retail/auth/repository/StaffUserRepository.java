package com.company.retail.auth.repository;

import com.company.retail.auth.mapper.StaffUserMapper;
import com.company.retail.common.persistence.RowValues;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 员工账号仓储（auth-service 真相源 core.users / core.user_roles，R02 §3 / V2 迁移）。
 *
 * <p>只服务登录：查用户、查角色。角色→权限展开在 {@code RolePermissions}。</p>
 */
@Repository
public class StaffUserRepository {

    private final StaffUserMapper mapper;

    public StaffUserRepository(StaffUserMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<StaffUser> findByUsername(String username) {
        List<Map<String, Object>> rows = mapper.findUserRows(username);
        return rows.stream().findFirst().map(StaffUserRepository::toUser);
    }

    public List<String> findRoles(long userId) {
        return mapper.findRoles(userId);
    }

    private static StaffUser toUser(Map<String, Object> row) {
        return new StaffUser(RowValues.asLong(row, "user_id"),
                RowValues.asString(row, "username"),
                RowValues.asString(row, "password_hash"),
                RowValues.asString(row, "display_name"),
                RowValues.asBoolean(row, "enabled"));
    }
}
