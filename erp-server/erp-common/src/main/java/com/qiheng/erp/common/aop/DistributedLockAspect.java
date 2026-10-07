package com.qiheng.erp.common.aop;

import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
@Order(0)
public class DistributedLockAspect {

    private final RedissonClient redissonClient;
    private final SpelExpressionParser spelParser = new SpelExpressionParser();

    public DistributedLockAspect(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 在业务方法外层获取分布式锁；负租期启用续约，固定租期保持原有行为。
     *
     * @param joinPoint 被拦截的业务方法
     * @param lock 锁键、等待时间及租期配置
     * @return 业务方法的原始返回值
     * @throws Throwable 获取锁失败或业务方法执行异常，原异常继续向调用方传播
     */
    @Around("@annotation(lock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock lock) throws Throwable {
        String lockKey = resolveKey(joinPoint, lock.key());
        RLock rLock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            // 不传固定租期才启用看门狗；其他入口仍保留注解指定的租期，不扩大本次修复范围。
            acquired = lock.leaseTime() < 0
                    ? rLock.tryLock(lock.waitTime(), lock.timeUnit())
                    : rLock.tryLock(lock.waitTime(), lock.leaseTime(), lock.timeUnit());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "操作被中断");
        }
        if (!acquired) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "该数据正在被其他用户操作，请稍后再试");
        }
        try {
            return joinPoint.proceed();
        } finally {
            if (rLock.isHeldByCurrentThread()) {
                rLock.unlock();
            }
        }
    }

    /**
     * 解析锁键表达式
     * @param joinPoint 连接点
     * @param keyExpression 锁键表达式
     * @return 解析后的锁键
     */
    private String resolveKey(ProceedingJoinPoint joinPoint, String keyExpression) {
        //获取方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        //获取方法
        Method method = signature.getMethod();
        //获取方法参数
        Object[] args = joinPoint.getArgs();
        //创建表达式上下文
        EvaluationContext context = new MethodBasedEvaluationContext(
                method.getDeclaringClass(), method, args, new DefaultParameterNameDiscoverer());
        //解析表达式
        Expression expression = spelParser.parseExpression(keyExpression);
        //解析表达式值
        //返回解析后的锁键
        return expression.getValue(context, String.class);
    }
}
