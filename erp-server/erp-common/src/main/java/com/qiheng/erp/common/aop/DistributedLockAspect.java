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

    @Around("@annotation(lock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock lock) throws Throwable {
        String lockKey = resolveKey(joinPoint, lock.key());
        RLock rLock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = rLock.tryLock(lock.waitTime(), lock.leaseTime(), lock.timeUnit());
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