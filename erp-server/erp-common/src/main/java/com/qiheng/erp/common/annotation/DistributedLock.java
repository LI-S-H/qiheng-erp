package com.qiheng.erp.common.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    String key();

    long waitTime() default 0;

    /** 固定租期；负数表示由 Redisson 看门狗续约，切面退出时释放，不按固定租期提前失效。 */
    long leaseTime() default 30;

    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
