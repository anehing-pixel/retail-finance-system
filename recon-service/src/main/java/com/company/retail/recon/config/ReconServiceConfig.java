package com.company.retail.recon.config;

import com.company.retail.recon.application.ReconBatchRunRepository;
import com.company.retail.recon.application.ReconDiffRepository;
import com.company.retail.recon.application.ReconQueryPort;
import com.company.retail.recon.application.ReconRunAppService;
import com.company.retail.recon.infrastructure.JdbcReconBatchRunRepository;
import com.company.retail.recon.infrastructure.JdbcReconDiffRepository;
import com.company.retail.recon.infrastructure.JdbcReconQueryPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 对拍域精确装配（R02 §3.1 范式 1）。
 */
@Configuration
public class ReconServiceConfig {

    @Bean
    public ReconQueryPort reconQueryPort(JdbcTemplate jdbcTemplate) {
        return new JdbcReconQueryPort(jdbcTemplate);
    }

    @Bean
    public ReconDiffRepository reconDiffRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcReconDiffRepository(jdbcTemplate);
    }

    @Bean
    public ReconBatchRunRepository reconBatchRunRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcReconBatchRunRepository(jdbcTemplate);
    }

    @Bean
    public ReconRunAppService reconRunAppService(ReconQueryPort queryPort,
                                                 ReconDiffRepository diffRepository,
                                                 ReconBatchRunRepository batchRunRepository) {
        return new ReconRunAppService(queryPort, diffRepository, batchRunRepository);
    }
}
