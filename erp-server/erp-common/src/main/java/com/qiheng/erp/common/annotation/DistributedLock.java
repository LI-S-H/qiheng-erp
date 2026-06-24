package com.qiheng.erp.common.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    String key();

    long waitTime() default 0;

    long leaseTime() default 30;

    TimeUnit timeUnit() default TimeUnit.SECONDS;
}