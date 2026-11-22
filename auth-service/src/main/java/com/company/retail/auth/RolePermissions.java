package com.company.retail.auth;

import com.company.retail.domain.model.Permission;

import java.util.Map;
import java.util.Set;

/**
 * 角色 → 权限点展开（W2 地基版：只保证机制真实可用）。
 *
 * <p>岗位角色清单与两层职责分离（SOD 对）按 {@code R08} 在 W7 收口时全量落表；
 * 本类的职责是把「角色进 token、权限点进 perms」的链路打通，
 * {@code ADMIN} 全量、其余角色 W2 无预置权限（最小授权原则——宁可后配，不可先滥）。</p>
 */
public final class RolePermissions {

    private RolePermissions() {
    }

    private static final Map<String, Set<String>> MAPPING = Map.of(
            "ADMIN", Permission.ALL
    );

    public static Set<String> effectivePermissions(java.util.List<String> roles) {
        Set<String> perms = new java.util.HashSet<>();
        for (String role : roles) {
            Set<String> granted = MAPPING.get(role);
            if (granted != null) {
                perms.addAll(granted);
            }
        }
        return perms;
    }
}
