package com.qiheng.erp.ai.returnorder;

import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.domain.vo.ReturnableSourceDetailVo;
import com.qiheng.erp.returnorder.service.IReturnOrderService;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import com.qiheng.erp.ai.sql.scope.PermissionCodes;

import java.util.List;

/**
 * 可退来源查询入口：校验用户权限与退货方向，复用退货模块的可退数量口径。
 *
 * @author Li
 * @since 2026-11-24
 */
public final class ReturnableItemQueryService {

    private final IReturnOrderService returnOrders;

    private final LoginUserService users;

    /**
     * 绑定退货服务与用户查询。
     *
     * @param returnOrders 退货只读服务
     * @param users 登录用户查询
     */
    public ReturnableItemQueryService(IReturnOrderService returnOrders, LoginUserService users) {
        this.returnOrders = returnOrders;
        this.users = users;
    }

    /**
     * 按来源单号查询来源单表头与可退明细。
     *
     * @param userId 当前用户
     * @param returnType 退货方向
     * @param sourceOrderNo 来源单号
     * @return 来源单与可退明细
     */
    public ReturnableSourceDetailVo load(long userId, String returnType, String sourceOrderNo) {
        ReturnType type = requireType(userId, returnType);
        return returnOrders.getReturnableSourceByNo(type, sourceOrderNo);
    }

    /**
     * 修改已有退货单时按退货单 ID 查询可退明细，并排除自身占用。
     *
     * @param userId 当前用户
     * @param returnOrderId 当前退货单 ID
     * @return 来源单与可退明细
     */
    public ReturnableSourceDetailVo loadForEdit(long userId, String returnOrderId) {
        long id;
        try {
            id = Long.parseLong(returnOrderId == null ? "" : returnOrderId.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("退货单 ID 不合法：" + returnOrderId);
        }
        var detail = returnOrders.getDetail(id);
        ReturnType type = requireType(userId, detail.getReturnType());
        return returnOrders.getReturnableSource(type, detail.getSourceOrderId(), id);
    }

    /** 校验退货查询权限并解析退货方向。 */
    private ReturnType requireType(long userId, String returnType) {
        LoginUser user = users.findByUserId(userId);
        List<String> permissions = user == null ? List.of() : PermissionCodes.of(user);
        if (user == null || (!permissions.contains("*") && !permissions.contains("return:query"))) {
            throw new IllegalArgumentException("当前用户无退货查询权限");
        }
        if (returnType == null || returnType.isBlank()) {
            throw new IllegalArgumentException("退货类型不能为空，只能是 SALES_RETURN 或 PURCHASE_RETURN");
        }
        try {
            return ReturnType.valueOf(returnType.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("退货类型不合法：" + returnType);
        }
    }
}