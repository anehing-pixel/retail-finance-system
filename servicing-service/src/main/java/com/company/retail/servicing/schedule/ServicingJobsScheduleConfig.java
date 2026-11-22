package com.company.retail.servicing.schedule;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled 开关总闸（W9-C）：{@code retail.jobs.schedule-enabled=true} 才启用调度基础设施。
 * 默认 false——不启用时不产生任何调度 bean，手工触发路径不受影响。
 */
@Configuration
@ConditionalOnProperty(name = "retail.jobs.schedule-enabled", havingValue = "true")
@EnableScheduling
public class ServicingJobsScheduleConfig {
}
