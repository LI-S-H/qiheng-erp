package com.qiheng.erp.ai.service.knowledge;

import java.util.List;

/**
 * 业务知识提供者：把「当前用户应该知道的业务口径」组装成给模型看的文本。
 *
 * <p>与 {@code SchemaProvider} 是同一套权限推导思路：</p>
 * <ul>
 *   <li>Schema 回答「能查哪些表、哪些字段」——由数据登记表决定；</li>
 *   <li>业务知识回答「这些数怎么算才对」——由业务规则文档决定。</li>
 * </ul>
 *
 * <p>实现只认权限码，不碰身份——身份解析是应用服务的事。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public interface KnowledgeProvider {

    /**
     * 按权限码组装业务规则文本。
     *
     * @param permissionCodes 当前登录用户的权限码；超管是 {@code ["*"]}，其余是多角色并集
     * @return 业务规则文本；该用户没有任何可查模块时返回空串
     */
    String provide(List<String> permissionCodes);
}
