package com.company.retail.contract.config;

import com.company.retail.contract.application.ContractApplicationService;
import com.company.retail.contract.domain.ContractPolicy;
import com.company.retail.contract.domain.StatutoryDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 合同域策略装配（C-08：模板可配 + 冷静期 8 日占位，口径变更走配置不改代码，P-12）。
 */
@Configuration
public class ContractPolicyConfig {

    @Bean
    public ContractPolicy contractPolicy(
            @Value("${retail.contract.required-doc-types:"
                    + StatutoryDocument.PRE_CONTRACT_STATEMENT + "," + StatutoryDocument.CONTRACT_DOCUMENT + "}")
            String requiredDocTypes,
            @Value("${retail.contract.cooling-off-enabled:true}") boolean coolingOffEnabled,
            @Value("${retail.contract.cooling-off-days:8}") int coolingOffDays) {
        Set<String> types = new LinkedHashSet<>(Arrays.asList(requiredDocTypes.split(",")));
        types.removeIf(String::isBlank);
        return new ContractPolicy(types, coolingOffEnabled, coolingOffDays);
    }
}
