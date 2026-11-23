package com.company.retail.customer.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 客户主数据与 PII 金库 Mapper（pii.customers / pii.customer_pii，R06 §4.1）。
 *
 * <p>注解 SQL（无 XML）；SQL 与改造前的 JdbcTemplate 版逐字一致（仅占位符由 ? 改为 #{xxx}）。
 * <b>本 Mapper 只搬运密文字节，不做任何加解密</b>：字段级 AES-256-GCM 加密与 HMAC 检索列的生成
 * 在 {@code PiiCryptoService}（机制层），脱敏与访问留痕在仓储/用例层（R08 §5 / R09 §5）。</p>
 *
 * <p>参数约定：自增主键的插入统一用 {@code Map<String,Object>} 单参数（全仓范式），
 * 主键由驱动回填到同名 key（未回填 = 未插入，按“未插入”处理，等价原 RETURNING 返回空集）；
 * 无自增主键且含 BYTEA 密文列的插入用带类型字段的参数对象，
 * 保证走 {@code ByteArrayTypeHandler}（ps.setBytes），与原 JdbcTemplate 写 BYTEA 的口径一致。</p>
 */
public interface PiiMapper {

    /**
     * PII 密文行插入参数（pii.customer_pii 一客户一行，主键即 customer_id）。
     *
     * <p>密文列一律 BYTEA 原始字节，检索列一律 HMAC 指纹——<b>禁止明文入库</b>（红线 B-1 / R08 §5）。</p>
     */
    static class NewCustomerPii {
        /** customer_id（主键，外键指向 pii.customers）。 */
        public long customerId;
        /** encrypted_name（姓名密文）。 */
        public byte[] encryptedName;
        /** encrypted_name_kana（假名密文，ADR-RF-011）。 */
        public byte[] encryptedNameKana;
        /** encrypted_address（住址密文）。 */
        public byte[] encryptedAddress;
        /** encrypted_birth_date（生辰密文，明文为 yyyy-MM-dd）。 */
        public byte[] encryptedBirthDate;
        /** encrypted_phone（电话密文）。 */
        public byte[] encryptedPhone;
        /** encrypted_employer（雇主密文）。 */
        public byte[] encryptedEmployer;
        /** encrypted_annual_income（年收入密文）。 */
        public byte[] encryptedAnnualIncome;
        /** name_hash（HMAC-SHA256 指纹，精确检索用）。 */
        public String nameHash;
        /** name_kana_hash（HMAC-SHA256 指纹）。 */
        public String nameKanaHash;
        /** key_version（密钥版本，支持轮换）。 */
        public String keyVersion;
        /** trace_id（链路追踪）。 */
        public String traceId;
    }

    /** 建客户主行（status 固定 'ACTIVE'；created_via 的缺省 'PORTAL' 由仓储层补齐，与原实现同口径）。 */
    @Options(useGeneratedKeys = true, keyProperty = "customerId", keyColumn = "customer_id")
    @Insert("INSERT INTO pii.customers (external_ref, status, created_via, trace_id) "
            + "VALUES (#{externalRef}, 'ACTIVE', #{createdVia}, #{traceId})")
    int insertCustomer(Map<String, Object> params);

    /** 建 PII 密文行（BYTEA 密文 + HMAC 检索列；本 Mapper 不解密、不脱敏）。 */
    @Insert("INSERT INTO pii.customer_pii (customer_id, encrypted_name, encrypted_name_kana, "
            + "encrypted_address, encrypted_birth_date, encrypted_phone, encrypted_employer, "
            + "encrypted_annual_income, name_hash, name_kana_hash, key_version, trace_id) "
            + "VALUES (#{customerId}, #{encryptedName}, #{encryptedNameKana}, #{encryptedAddress}, "
            + "#{encryptedBirthDate}, #{encryptedPhone}, #{encryptedEmployer}, #{encryptedAnnualIncome}, "
            + "#{nameHash}, #{nameKanaHash}, #{keyVersion}, #{traceId})")
    int insertCustomerPii(NewCustomerPii param);

    /** 解密读取所需的密文列（显式列清单，不用 SELECT *；列名保持小写下划线，不做驼峰转换）。 */
    @Select("SELECT c.external_ref, p.encrypted_name, p.encrypted_name_kana, p.encrypted_address, "
            + "p.encrypted_birth_date, p.encrypted_phone, p.encrypted_employer, p.encrypted_annual_income "
            + "FROM pii.customers c JOIN pii.customer_pii p ON p.customer_id = c.customer_id "
            + "WHERE c.customer_id = #{customerId}")
    List<Map<String, Object>> findCustomerPiiRows(@Param("customerId") long customerId);

    /** 客户是否存在（不含解密）。 */
    @Select("SELECT EXISTS (SELECT 1 FROM pii.customers WHERE customer_id = #{customerId})")
    Boolean existsCustomer(@Param("customerId") long customerId);
}
