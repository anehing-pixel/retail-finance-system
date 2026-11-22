package com.company.retail.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 员工账号仓储（auth-service 真相源 core.users / core.user_roles，R02 §3 / V2 迁移）。
 *
 * <p>只服务登录：查用户、查角色。角色→权限展开在 {@link RolePermissions}。</p>
 */
@Repository
public class JdbcStaffUserRepository {

    private final JdbcTemplate jdbc;

    public JdbcStaffUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StaffUser> findByUsername(String username) {
        List<StaffUser> rows = jdbc.query(
                "SELECT user_id, username, password_hash, display_name, enabled FROM core.users "
                        + "WHERE username = ?",
                (rs, i) -> new StaffUser(rs.getLong("user_id"), rs.getString("username"),
                        rs.getString("password_hash"), rs.getString("display_name"),
                        rs.getBoolean("enabled")),
                username);
        return rows.stream().findFirst();
    }

    public List<String> findRoles(long userId) {
        return jdbc.queryForList(
                "SELECT role_key FROM core.user_roles WHERE user_id = ? ORDER BY role_key",
                String.class, userId);
    }

    public record StaffUser(long userId, String username, String passwordHash,
                            String displayName, boolean enabled) {
    }
}
