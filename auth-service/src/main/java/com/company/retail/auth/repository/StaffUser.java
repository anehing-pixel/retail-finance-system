package com.company.retail.auth.repository;

/**
 * 员工账号视图（core.users 的一行）。只用于登录校验与令牌载荷，不含个人信息。
 */
public record StaffUser(long userId, String username, String passwordHash,
                        String displayName, boolean enabled) {
}
