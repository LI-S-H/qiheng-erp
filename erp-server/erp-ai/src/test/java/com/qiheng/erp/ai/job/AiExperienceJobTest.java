package com.qiheng.erp.ai.job;

import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.experience.ExperienceExtractionService;
import com.qiheng.erp.ai.experience.ExperienceMergeService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import static org.mockito.Mockito.*;

class AiExperienceJobTest {
    @Test
    void extractsBeforeMergingAndReleasesLock() {
        var extraction = mock(ExperienceExtractionService.class);
        var merge = mock(ExperienceMergeService.class);
        var redis = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(redis.getLock("erp:ai:experience:job")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        new AiExperienceJob(extraction, merge, properties(), redis).run();
        var order = inOrder(extraction, merge, lock);
        order.verify(extraction).extractPending();
        order.verify(merge).mergeAll();
        order.verify(lock).unlock();
    }

    @Test
    void workerFailureDoesNotStartMergeAndStillReleasesLock() {
        var extraction = mock(ExperienceExtractionService.class);
        var merge = mock(ExperienceMergeService.class);
        var redis = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(redis.getLock("erp:ai:experience:job")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        doThrow(new IllegalStateException("工作线程失败")).when(extraction).extractPending();
        new AiExperienceJob(extraction, merge, properties(), redis).run();
        verify(merge, never()).mergeAll();
        verify(lock).unlock();
    }

    private AiExperienceProperties properties() {
        return new AiExperienceProperties(true, "0 10 3 * * ?", 100, 7, 6,
                600_000, 4000, 5, 400, 100, 400, 10, 60, "flash");
    }
}
