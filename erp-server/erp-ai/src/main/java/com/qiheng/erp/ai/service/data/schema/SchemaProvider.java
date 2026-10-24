package com.qiheng.erp.ai.service.data.schema;

import java.util.List;

/**
 * Schema 提供者：把「当前用户能看到的数据表」组装成给模型看的 Schema 文本。
 *
 * <p>实现只认权限码，不碰身份——身份解析是应用服务的事，这里只做数据可见性到文本的转换。
 * 这样实现可以脱离会话单独测试。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
public interface SchemaProvider {

    /**
     * 按权限码组装可见 Schema 文本。
     *
     * @param permissionCodes 当前登录用户的权限码；超管是 {@code ["*"]}，其余是多角色并集
     * @return Schema 文本；该用户没有任何可查模块时返回空串
     */
    String provide(List<String> permissionCodes);
}
