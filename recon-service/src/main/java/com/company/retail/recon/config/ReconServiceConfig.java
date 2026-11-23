package com.company.retail.recon.config;

import com.company.retail.recon.application.ReconBatchRunRepository;
import com.company.retail.recon.application.ReconDiffRepository;
import com.company.retail.recon.application.ReconQueryPort;
import com.company.retail.recon.application.ReconRunAppService;
import com.company.retail.recon.infrastructure.MyBatisReconBatchRunRepository;
import com.company.retail.recon.infrastructure.MyBatisReconDiffRepository;
import com.company.retail.recon.infrastructure.MyBatisReconQueryPort;
import com.company.retail.recon.mapper.ReconBatchRunMapper;
import com.company.retail.recon.mapper.ReconDiffMapper;
import com.company.retail.recon.mapper.ReconQueryMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对拍域精确装配（R02 §3.1 范式 1）。
 *
 * <p>持久层统一走 MyBatis 注解 SQL（{@code ReconMyBatisConfig} 显式注册本服务 Mapper 包）；
 * 本类只做「端口 → 实现」的按类注入，不做组件全包扫描。</p>
 */
@Configuration
public class ReconServiceConfig {

    @Bean
    public ReconQueryPort reconQueryPort(ReconQueryMapper mapper) {
        return new MyBatisReconQueryPort(mapper);
    }

    @Bean
    public ReconDiffRepository reconDiffRepository(ReconDiffMapper mapper) {
        return new MyBatisReconDiffRepository(mapper);
    }

    @Bean
    public ReconBatchRunRepository reconBatchRunRepository(ReconBatchRunMapper mapper) {
        return new MyBatisReconBatchRunRepository(mapper);
    }

    @Bean
    public ReconRunAppService reconRunAppService(ReconQueryPort queryPort,
                                                 ReconDiffRepository diffRepository,
                                                 ReconBatchRunRepository batchRunRepository) {
        return new ReconRunAppService(queryPort, diffRepository, batchRunRepository);
    }
}
