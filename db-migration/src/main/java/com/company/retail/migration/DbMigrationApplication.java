package com.company.retail.migration;

import org.flywaydb.core.Flyway;

import java.net.URL;

/**
 * 独立数据库迁移作业（R11 P0-10）。
 *
 * <p>迁移不再绑定任何服务运行期：本作业以迁移账号执行 Flyway，成功后退出 0；
 * 服务（含 retail-core）全部 {@code spring.flyway.enabled=false}。发布顺序固定为
 * <b>先跑迁移作业，再滚动发布服务</b>，从而支持多副本发布与独立回滚。</p>
 *
 * <p>环境变量：{@code RETAIL_DB_MIGRATION_URL}（缺省回落 {@code SPRING_DATASOURCE_URL}，
 * 再缺省本地 5435）、{@code RETAIL_DB_MIGRATION_USER}（缺省 {@code postgres}）、
 * {@code RETAIL_DB_MIGRATION_PASSWORD}（必填）。<b>不打印口令</b>。</p>
 */
public final class DbMigrationApplication {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5435/retail_finance";

    private DbMigrationApplication() {
    }

    public static void main(String[] args) {
        String url = firstNonBlank(
                System.getenv("RETAIL_DB_MIGRATION_URL"),
                System.getenv("SPRING_DATASOURCE_URL"),
                DEFAULT_URL);
        String user = firstNonBlank(System.getenv("RETAIL_DB_MIGRATION_USER"), "postgres");
        String password = System.getenv("RETAIL_DB_MIGRATION_PASSWORD");

        if (password == null || password.isBlank()) {
            System.err.println("[db-migration] 缺少 RETAIL_DB_MIGRATION_PASSWORD（迁移账号口令必须注入）");
            System.exit(2);
        }
        URL migrations = DbMigrationApplication.class.getClassLoader().getResource("db/migration");
        if (migrations == null) {
            System.err.println("[db-migration] classpath 缺少 db/migration（迁移脚本未打包）");
            System.exit(2);
        }

        System.out.println("[db-migration] 目标库=" + url + " 迁移账号=" + user);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(url, user, password)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .validateOnMigrate(true)
                    .load();
            flyway.migrate();
            System.out.println("[db-migration] Flyway 迁移完成");
        } catch (Exception e) {
            // 只打印异常消息，不打印连接串中的口令（本作业也不接受带口令的 URL）
            System.err.println("[db-migration] 迁移失败：" + e.getMessage());
            System.exit(1);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
