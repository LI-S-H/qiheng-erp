package com.qiheng.erp.system.manager;

import java.util.List;


/**
 * Session 管理器接口，负责在线用户的 Session 生命周期管理
 * <p>
 * 当用户的角色、权限、状态等发生变化时，Session 中缓存的权限快照会过期，
 * 需要通过本管理器刷新或踢下线，保证权限实时生效。
 * </p>
 * <p>
 * 接口定义在 erp-system，实现在 erp-security，避免模块循环依赖。
 * </p>
 */
public interface SessionManager {

    /**
     * 刷新单个在线用户的 Session（权限快照）
     * <p>
     * 内部自动处理三种情况：
     * <ol>
     *   <li>用户不在线 → 跳过，无需刷新</li>
     *   <li>用户在线但已从数据库删除 → 踢下线</li>
     *   <li>用户在线且存在 → 重新查库，更新 Session 中的角色和权限码</li>
     * </ol>
     * 调用方无需区分场景，只需告知"该用户的权限快照可能过期"即可。
     * </p>
     *
     * @param userId 用户ID
     */
    void refreshUserSession(Long userId);

    /**
     * 批量刷新在线用户的 Session（权限快照）
     * <p>
     * 适用场景：停用/删除角色后，需要刷新所有受影响用户的权限快照。
     * 内部逐个调用 {@link #refreshUserSession(Long)}，每个用户独立判断处理方式。
     * </p>
     *
     * @param userIds 用户ID列表
     */
    void refreshUserSession(List<Long> userIds);

    /**
     * 强制单个用户下线
     * <p>
     * 适用场景：停用用户、删除用户等需要立即终止会话的情况。
     * </p>
     *
     * @param userId 用户ID
     */
    void kickOffline(Long userId);

    /**
     * 批量强制用户下线
     * <p>
     * 适用场景：批量停用/删除用户后，需要立即终止这些用户的会话。
     * </p>
     *
     * @param userIds 用户ID列表
     */
    void kickOffline(List<Long> userIds);

}