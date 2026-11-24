package com.company.retail.underwriting.schedule;

import com.company.retail.common.outbound.OutboundProperties;
import com.company.retail.underwriting.integration.DmsResultSender;
import com.company.retail.underwriting.mapper.OutboundTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DMS 结果通知的异步重试调度器（P0-7；R07 §4.1）。
 *
 * <p>独立单线程执行器（不用 {@code @Scheduled}）：underwriting 的 outbox 调度器是单线程，
 * 出站 HTTP 慢调用若排在其后会拖慢秒级刚性事件（如同意撤回）。本调度器只影响自身。</p>
 *
 * <p>退避 1m/5m/30m/2h/6h，5 次失败后 DEAD；未知任务类型/坏 payload 立即 DEAD。</p>
 */
public final class DmsOutboundTaskScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DmsOutboundTaskScheduler.class);
    private static final String TASK_TYPE = "DMS_RESULT_NOTIFY";

    private final OutboundTaskMapper mapper;
    private final DmsResultSender sender;
    private final OutboundProperties.Tasks properties;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rf-outbound-task");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean started;

    public DmsOutboundTaskScheduler(OutboundTaskMapper mapper, DmsResultSender sender,
                                    OutboundProperties.Tasks properties) {
        this.mapper = mapper;
        this.sender = sender;
        this.properties = properties;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        long interval = properties.getScanInterval().toMillis();
        log.info("出站任务调度器已启动 interval={}ms batchSize={} maxRetries={}",
                interval, properties.getBatchSize(), properties.getMaxRetries());
        executor.scheduleWithFixedDelay(this::pollSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    void pollSafely() {
        try {
            poll();
        } catch (RuntimeException e) {
            log.warn("outbound task 扫描失败，下一轮重试", e);
        }
    }

    /** 同步执行一轮（测试直接调用）。 */
    public void poll() {
        mapper.reapStale(properties.getLease().toSeconds(), properties.getMaxRetries(),
                properties.getBatchSize());
        List<Map<String, Object>> due = mapper.claimDue(properties.getBatchSize());
        for (Map<String, Object> row : due) {
            handle(OutboundTask.from(row));
        }
    }

    private void handle(OutboundTask task) {
        if (!TASK_TYPE.equals(task.taskType())) {
            log.warn("未知出站任务类型，标记 DEAD taskId={} type={}", task.taskId(), task.taskType());
            mapper.markDead(task.taskId(), task.version());
            return;
        }
        DmsPayload payload;
        try {
            payload = DmsPayload.parse(task.payloadRef());
        } catch (RuntimeException e) {
            log.warn("出站任务 payload 非法，标记 DEAD taskId={} payloadRef={}",
                    task.taskId(), task.payloadRef());
            mapper.markDead(task.taskId(), task.version());
            return;
        }
        if (payload.decision().isEmpty()) {
            log.warn("历史任务缺少 decision，标记 DEAD taskId={}", task.taskId());
            mapper.markDead(task.taskId(), task.version());
            return;
        }
        try {
            String dmsRef = sender.sendResult(payload.caseId(), payload.applicationId(),
                    payload.decision(), payload.reasonCode(), task.traceId());
            if (dmsRef != null) {
                mapper.markDone(task.taskId(), task.version());
                return;
            }
        } catch (RuntimeException e) {
            log.warn("出站任务发送异常 taskId={} err={}", task.taskId(), e.getClass().getSimpleName());
        }
        fail(task);
    }

    private void fail(OutboundTask task) {
        int next = task.retryCount() + 1;
        if (next >= properties.getMaxRetries()) {
            mapper.markDead(task.taskId(), task.version());
            log.error("出站任务达到重试上限，标记 DEAD taskId={} retry={}", task.taskId(), next);
            return;
        }
        long delaySeconds = properties.delayFor(next).toSeconds();
        mapper.markRetry(task.taskId(), task.version(), next, delaySeconds);
        log.warn("出站任务失败，安排重试 taskId={} retry={} delaySeconds={}",
                task.taskId(), next, delaySeconds);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
