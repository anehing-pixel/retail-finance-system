package com.company.retail.customer.repo;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.customer.app.CustomerPiiData;
import com.company.retail.customer.infra.PiiCryptoService;
import com.company.retail.customer.mapper.PiiMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * pii.customers + pii.customer_pii 的 MyBatis 仓储（R06 §4.1）。
 * 明文只在此与 {@code PiiCryptoService} 之间出现；密文落 BYTEA，检索走 HMAC hash 列。
 */
@Repository
public class PiiRepository {

    private final PiiMapper mapper;
    private final PiiCryptoService crypto;

    public PiiRepository(PiiMapper mapper, PiiCryptoService crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    /** 建客户主行 + 加密 PII 行，返回 customer_id。 */
    public long createCustomer(CustomerPiiData data, String createdVia, String traceId) {
        Map<String, Object> params = new HashMap<>();
        params.put("externalRef", data.externalRef());
        params.put("createdVia", createdVia == null ? "PORTAL" : createdVia);
        params.put("traceId", traceId);
        mapper.insertCustomer(params);
        // 自增主键由驱动回填到入参 Map（未回填 = 未插入，与原 GeneratedKeyHolder 取值同口径）
        long customerId = ((Number) params.get("customerId")).longValue();

        PiiMapper.NewCustomerPii pii = new PiiMapper.NewCustomerPii();
        pii.customerId = customerId;
        pii.encryptedName = crypto.encryptField(data.name());
        pii.encryptedNameKana = crypto.encryptField(data.nameKana());
        pii.encryptedAddress = crypto.encryptField(data.address());
        pii.encryptedBirthDate = crypto.encryptField(data.birthDate() == null ? null : data.birthDate().toString());
        pii.encryptedPhone = crypto.encryptField(data.phone());
        pii.encryptedEmployer = crypto.encryptField(data.employer());
        pii.encryptedAnnualIncome = crypto.encryptField(data.annualIncome());
        pii.nameHash = crypto.hmacHash(data.name());
        pii.nameKanaHash = crypto.hmacHash(data.nameKana());
        pii.keyVersion = crypto.getKeyVersion();
        pii.traceId = traceId;
        mapper.insertCustomerPii(pii);
        return customerId;
    }

    /** 解密读取 PII（仅 PiiService 用例调用；调用方负责写 pii_access_logs）。 */
    public Optional<CustomerPiiData> loadDecrypted(long customerId) {
        List<Map<String, Object>> rows = mapper.findCustomerPiiRows(customerId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        String birthDate = crypto.decryptField(RowValues.asBytes(row, "encrypted_birth_date"));
        return Optional.of(new CustomerPiiData(
                RowValues.asString(row, "external_ref"),
                crypto.decryptField(RowValues.asBytes(row, "encrypted_name")),
                crypto.decryptField(RowValues.asBytes(row, "encrypted_name_kana")),
                crypto.decryptField(RowValues.asBytes(row, "encrypted_address")),
                birthDate == null ? null : LocalDate.parse(birthDate),
                crypto.decryptField(RowValues.asBytes(row, "encrypted_phone")),
                crypto.decryptField(RowValues.asBytes(row, "encrypted_employer")),
                crypto.decryptField(RowValues.asBytes(row, "encrypted_annual_income"))));
    }

    /** 客户是否存在（不含解密）。 */
    public boolean exists(long customerId) {
        Boolean ok = mapper.existsCustomer(customerId);
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
