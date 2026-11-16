package com.qiheng.erp.ai.job;

import com.qiheng.erp.ai.mapper.AiAnalysisResultMapper;
import com.qiheng.erp.ai.mapper.AiDatasetMapper;
import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

/** 回收超过保留时间且不存在元信息的受管文件。 */
@Component
public class AiOrphanFileCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(AiOrphanFileCleanupJob.class);
    private final AiDatasetMapper datasets;
    private final AiAnalysisResultMapper results;
    private final SystemExceptionMqPublisher publisher;
    private final Path datasetDirectory;
    private final Path resultDirectory;
    private final long datasetDays;
    private final long resultDays;

    public AiOrphanFileCleanupJob(AiDatasetMapper datasets, AiAnalysisResultMapper results,
            ObjectProvider<SystemExceptionMqPublisher> publisher,
            @Value("${erp.ai.dataset.directory:./data/ai-datasets}") String datasetDirectory,
            @Value("${erp.ai.python.result-directory:./data/ai-python-results}") String resultDirectory,
            @Value("${erp.ai.dataset.ttl-days:0}") long datasetDays,
            @Value("${erp.ai.python.result-ttl-days:0}") long resultDays) {
        this.datasets = datasets;
        this.results = results;
        this.publisher = publisher.getIfAvailable();
        this.datasetDirectory = Path.of(datasetDirectory).toAbsolutePath().normalize();
        this.resultDirectory = Path.of(resultDirectory).toAbsolutePath().normalize();
        this.datasetDays = datasetDays;
        this.resultDays = resultDays;
    }

    /** 在元信息过期清理后扫描；关闭保留期限时不主动删除文件。 */
    @Scheduled(cron = "0 45 3 * * ?", scheduler = "aiOrphanCleanupScheduler")
    public synchronized void cleanup() {
        scan(datasetDirectory, datasetDays, true);
        scan(resultDirectory, resultDays, false);
    }

    /** 流式遍历全部目录，每批最多缓存 200 个过期候选文件。 */
    private void scan(Path root, long days, boolean dataset) {
        if (days <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        try {
            BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || !root.toRealPath().equals(root)) {
                throw new IOException("清理目录不是普通目录或包含链接");
            }
        } catch (NoSuchFileException ignored) {
            // 新安装尚未创建目录时无需清理。
            return;
        } catch (IOException | RuntimeException error) {
            report(root, root, "检查目录", error);
            return;
        }
        try (Stream<Path> paths = Files.walk(root, dataset ? 1 : 4)) {
            var iterator = paths.iterator();
            List<Path> batch = new ArrayList<>(200);
            while (iterator.hasNext()) {
                Path file = iterator.next();
                try {
                    if (candidate(root, file, cutoff, dataset)) {
                        batch.add(file);
                    }
                } catch (IOException | RuntimeException error) {
                    report(root, file, "检查文件", error);
                }
                if (batch.size() == 200) {
                    inspectBatch(root, batch, cutoff, dataset);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                inspectBatch(root, batch, cutoff, dataset);
            }
        } catch (IOException | RuntimeException error) {
            report(root, root, "扫描目录", error);
        }
    }

    /** 只检查规定目录层级和命名，拒绝符号链接。 */
    private boolean candidate(Path root, Path file, Instant cutoff, boolean dataset) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path relative = root.relativize(file);
        String name = file.getFileName().toString();
        boolean temporary = dataset ? name.matches("dataset-[0-9]+\\.tmp") : name.matches("analysis-[0-9]+\\.tmp");
        boolean recognized = dataset
                ? relative.getNameCount() == 1 && (name.matches("ds_[0-9a-fA-F]{32}\\.jsonl") || temporary)
                : relative.getNameCount() == 4 && relative.getName(0).toString().matches("[0-9]+")
                && relative.getName(1).toString().matches("[0-9]+")
                && relative.getName(2).toString().matches("[A-Za-z0-9_-]{1,64}")
                && (name.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") || temporary);
        if (!recognized) {
            return false;
        }
        for (Path parent = file.getParent(); parent != null && parent.startsWith(root); parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) {
                return false;
            }
        }
        return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
    }

    /** 两次批量反查：数据库失败时保留整批，后续任务重试。 */
    private void inspectBatch(Path root, List<Path> batch, Instant cutoff, boolean dataset) {
        List<String> ids = batch.stream().map(path -> fileId(path, dataset)).filter(id -> id != null).toList();
        try {
            Set<String> present = existingIds(ids, dataset);
            List<String> missing = ids.stream().filter(id -> !present.contains(id)).toList();
            Set<String> confirmed = existingIds(missing, dataset);
            for (Path file : batch) {
                String id = fileId(file, dataset);
                if (id != null && (present.contains(id) || confirmed.contains(id))) {
                    continue;
                }
                try {
                    if (candidate(root, file, cutoff, dataset)) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException | RuntimeException error) {
                    report(root, file, "删除文件", error);
                }
            }
        } catch (RuntimeException error) {
            for (Path file : batch) {
                report(root, file, "批量查询元数据", error);
            }
        }
    }

    private String fileId(Path file, boolean dataset) {
        String name = file.getFileName().toString();
        if (name.endsWith(".tmp")) {
            return null;
        }
        return dataset ? name.substring(0, name.length() - 6) : name;
    }

    private Set<String> existingIds(List<String> ids, boolean dataset) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<String> existing = new HashSet<>();
        if (dataset) {
            datasets.selectBatchIds(ids).forEach(record -> existing.add(record.getDatasetId()));
        } else {
            results.selectBatchIds(ids).forEach(record -> existing.add(record.getResultId()));
        }
        return existing;
    }

    /** MQ 发送失败不阻断其他文件的清理。 */
    private void report(Path root, Path file, String stage, Exception error) {
        String relative = root.equals(file) ? "." : root.relativize(file).toString();
        log.warn("AI 孤儿文件清理失败 path={} stage={}", relative, stage, error);
        if (publisher == null) {
            return;
        }
        try {
            var message = new SystemExceptionRecordMessage();
            message.setEventId(Long.toString(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE));
            message.setExceptionType("AI_ORPHAN_CLEANUP_FAILED");
            message.setSourceModule(SystemExceptionConstants.MODULE_AI);
            message.setSourceNo("AI_FILE_CLEANUP");
            message.setSeverity(SystemExceptionConstants.SEVERITY_MEDIUM);
            message.setErrorCode("AI_ORPHAN_CLEANUP_FAILED");
            message.setErrorMessage("AI 孤儿文件清理失败");
            message.setDetailSummary("文件=" + relative + " 阶段=" + stage + " 原因=" + error.getClass().getSimpleName());
            message.setResolveHint("检查文件权限及数据库状态后重试清理。");
            message.setOccurredAt(System.currentTimeMillis());
            publisher.publish(message);
        } catch (RuntimeException publishError) {
            log.warn("AI 孤儿文件清理异常上报失败 path={}", relative, publishError);
        }
    }
}
