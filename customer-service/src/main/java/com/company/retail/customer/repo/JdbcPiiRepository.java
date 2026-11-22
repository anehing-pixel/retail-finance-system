package com.company.retail.customer.repo;

import com.company.retail.customer.app.CustomerPiiData;
import com.company.retail.customer.infra.PiiCryptoService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * pii.customers + pii.customer_pii 的 JDBC 仓储（R06 §4.1）。
 * 明文只在此与 {@code PiiCryptoService} 之间出现；密文落 BYTEA，检索走 HMAC hash 列。
 */
@Repository
public class JdbcPiiRepository {

    private final JdbcTemplate jdbc;
    private final PiiCryptoService crypto;

    public JdbcPiiRepository(JdbcTemplate jdbc, PiiCryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    /** 建客户主行 + 加密 PII 行，返回 customer_id。 */
    public long createCustomer(CustomerPiiData data, String createdVia, String traceId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pii.customers (external_ref, status, created_via, trace_id) "
                            + "VALUES (?, 'ACTIVE', ?, ?)", new String[]{"customer_id"});
            if (data.externalRef() == null) {
                ps.setNull(1, Types.VARCHAR);
            } else {
                ps.setString(1, data.externalRef());
            }
            ps.setString(2, createdVia == null ? "PORTAL" : createdVia);
            ps.setString(3, traceId);
            return ps;
        }, kh);
        long customerId = kh.getKey().longValue();

        jdbc.update(""" 
                        INSERT INTO pii.customer_pii (customer_id, encrypted_name, encrypted_name_kana,
                            encrypted_address, encrypted_birth_date, encrypted_phone,
                            encrypted_employer, encrypted_annual_income,
                            name_hash, name_kana_hash, key_version, trace_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                customerId,
                crypto.encryptField(data.name()),
                crypto.encryptField(data.nameKana()),
                crypto.encryptField(data.address()),
                crypto.encryptField(data.birthDate() == null ? null : data.birthDate().toString()),
                crypto.encryptField(data.phone()),
                crypto.encryptField(data.employer()),
                crypto.encryptField(data.annualIncome()),
                crypto.hmacHash(data.name()),
                crypto.hmacHash(data.nameKana()),
                crypto.getKeyVersion(),
                traceId);
        return customerId;
    }

    /** 解密读取 PII（仅 PiiService 用例调用；调用方负责写 pii_access_logs）。 */
    public Optional<CustomerPiiData> loadDecrypted(long customerId) {
        var rows = jdbc.queryForList("""
                SELECT c.external_ref, p.encrypted_name, p.encrypted_name_kana, p.encrypted_address,
                       p.encrypted_birth_date, p.encrypted_phone, p.encrypted_employer,
                       p.encrypted_annual_income
                FROM pii.customers c JOIN pii.customer_pii p ON p.customer_id = c.customer_id
                WHERE c.customer_id = ?
                """, customerId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        String birthDate = crypto.decryptField(bytes(row.get("encrypted_birth_date")));
        return Optional.of(new CustomerPiiData(
                (String) row.get("external_ref"),
                crypto.decryptField(bytes(row.get("encrypted_name"))),
                crypto.decryptField(bytes(row.get("encrypted_name_kana"))),
                crypto.decryptField(bytes(row.get("encrypted_address"))),
                birthDate == null ? null : LocalDate.parse(birthDate),
                crypto.decryptField(bytes(row.get("encrypted_phone"))),
                crypto.decryptField(bytes(row.get("encrypted_employer"))),
                crypto.decryptField(bytes(row.get("encrypted_annual_income")))));
    }

    private static byte[] bytes(Object v) {
        return v == null ? null : (byte[]) v;
    }

    /** 客户是否存在（不含解密）。 */
    public boolean exists(long customerId) {
        Boolean ok = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pii.customers WHERE customer_id = ?)", Boolean.class, customerId);
        return Boolean.TRUE.equals(ok);
    }

    /** 全字段名清单（脱敏/全量视图与访问日志共用口径）。 */
    public static Map<String, String> maskedFields(CustomerPiiData data) {
        Map<String, String> m = new HashMap<>();
        m.put("name", com.company.retail.customer.domain.PiiViewPolicy.maskName(data.name()));
        m.put("nameKana", com.company.retail.customer.domain.PiiViewPolicy.maskName(data.nameKana()));
        m.put("address", com.company.retail.customer.domain.PiiViewPolicy.maskAddress(data.address()));
        m.put("birthDate", com.company.retail.customer.domain.PiiViewPolicy.maskBirthDate(
                data.birthDate() == null ? null : data.birthDate().toString()));
        m.put("phone", com.company.retail.customer.domain.PiiViewPolicy.maskPhone(data.phone()));
        m.put("employer", com.company.retail.customer.domain.PiiViewPolicy.maskEmployer(data.employer()));
        m.put("annualIncome", com.company.retail.customer.domain.PiiViewPolicy.maskAnnualIncome(data.annualIncome()));
        return m;
    }
}
