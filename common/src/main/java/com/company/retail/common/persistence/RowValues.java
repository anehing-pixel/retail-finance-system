package com.company.retail.common.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Map;

/**
 * 行值读取工具（MyBatis 化的行映射辅助，机制层）。
 *
 * <p>MyBatis Mapper 的查询统一返回 {@code Map<String,Object>}（列标签即键），
 * 领域对象的组装由本工具负责——<b>类型收敛口径与原 JdbcTemplate RowMapper 保持一致</b>：
 * {@code rs.getLong} 遇 NULL 返回 0、{@code rs.getBoolean} 遇 NULL 返回 false、
 * {@code rs.getString} 遇 NULL 返回 null。</p>
 *
 * <p>时间列：PostgreSQL 的 {@code TIMESTAMPTZ} 经 JDBC 驱动可能给出
 * {@link Timestamp} / {@link OffsetDateTime} / {@link Instant}，
 * 本工具统一收敛为 {@link Instant}（同一时刻，不引入时区漂移）。</p>
 */
public final class RowValues {

    private RowValues() {
    }

    /** NULL → 0（对齐 {@code ResultSet.getLong}）。 */
    public static long asLong(Map<String, Object> row, String col) {
        Object v = value(row, col);
        return v == null ? 0L : ((Number) v).longValue();
    }

    /** NULL → null（可空外键列）。 */
    public static Long asLongOrNull(Map<String, Object> row, String col) {
        Object v = value(row, col);
        return v == null ? null : ((Number) v).longValue();
    }

    /** NULL → 0（对齐 {@code ResultSet.getInt}）。 */
    public static int asInt(Map<String, Object> row, String col) {
        Object v = value(row, col);
        return v == null ? 0 : ((Number) v).intValue();
    }

    /** NULL → null。 */
    public static String asString(Map<String, Object> row, String col) {
        Object v = value(row, col);
        return v == null ? null : String.valueOf(v);
    }

    /** NULL → false（对齐 {@code ResultSet.getBoolean}；兼容 0/1 数值列）。 */
    public static boolean asBoolean(Map<String, Object> row, String col) {
        Object v = value(row, col);
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /** 时间列 → {@link Instant}（多类型收敛；NULL → null）。 */
    public static Instant asInstant(Map<String, Object> row, String col) {
        Object v = value(row, col);
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        if (v instanceof Timestamp t) {
            return t.toInstant();
        }
        if (v instanceof Date d) {
            return d.toInstant();
        }
        if (v instanceof LocalDateTime l) {
            return l.toInstant(OffsetDateTime.now().getOffset());
        }
        throw new IllegalStateException("不支持的时间列类型：" + v.getClass().getName() + "（列 " + col + "）");
    }

    /** 日期列 → {@link LocalDate}（NULL → null）。 */
    public static LocalDate asLocalDate(Map<String, Object> row, String col) {
        Object v = value(row, col);
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof Timestamp t) {
            return t.toLocalDateTime().toLocalDate();
        }
        if (v instanceof LocalDateTime l) {
            return l.toLocalDate();
        }
        throw new IllegalStateException("不支持的日期列类型：" + v.getClass().getName() + "（列 " + col + "）");
    }

    /** 数值列 → {@link BigDecimal}（NULL → null；金额一律整数日元，此处仅用于比例/汇率）。 */
    public static BigDecimal asBigDecimal(Map<String, Object> row, String col) {
        Object v = value(row, col);
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(String.valueOf(v));
    }

    /** 时间列 → {@link Timestamp}（与原 {@code ResultSet.getTimestamp} 同型；NULL → null）。 */
    public static Timestamp asTimestamp(Map<String, Object> row, String col) {
        Object v = value(row, col);
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp t) {
            return t;
        }
        if (v instanceof OffsetDateTime o) {
            return Timestamp.from(o.toInstant());
        }
        if (v instanceof Instant i) {
            return Timestamp.from(i);
        }
        if (v instanceof Date d) {
            return new Timestamp(d.getTime());
        }
        if (v instanceof LocalDateTime l) {
            return Timestamp.valueOf(l);
        }
        throw new IllegalStateException("不支持的时间列类型：" + v.getClass().getName() + "（列 " + col + "）");
    }

    /** 二进制列 → byte[]（NULL → null）。 */
    public static byte[] asBytes(Map<String, Object> row, String col) {
        Object v = value(row, col);
        return v == null ? null : (byte[]) v;
    }

    private static Object value(Map<String, Object> row, String col) {
        return row == null ? null : row.get(col);
    }
}
