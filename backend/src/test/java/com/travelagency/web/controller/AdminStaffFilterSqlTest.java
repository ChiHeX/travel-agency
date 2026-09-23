package com.travelagency.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelagency.common.exception.BusinessException;
import com.travelagency.domain.entity.Staff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /admin/staff} 筛选条件的 <b>SQL 形状</b>（纯单测，不启动 Spring、不连库）。
 *
 * <p><b>为什么需要这一层</b>：评审指出的两个问题（status 只约束了账号 id 那一支 ⇒ 关键字分支绕过状态；
 * 每次筛选都先把命中账号 id 全查回 Java 再拼 {@code IN (…)} ⇒ 参数与代价随账号总数增长）
 * 都是<b>形状</b>问题 —— 真实请求的响应体看不出来，行为型集成测试也守不住它。
 * 只有直接断言生成的 SQL，才能让「日后有人改回先查 id 再 IN」立刻变红。</p>
 *
 * <p>断言的是 {@code getSqlSegment()}（占位符形态），不是拼好的字面 SQL：
 * 参数一律走 {@code {0}} 占位符 + {@code getParamNameValuePairs()} 绑定，不做字符串拼接。</p>
 */
class AdminStaffFilterSqlTest {

    @Test
    @DisplayName("只按 status 筛选：库内 EXISTS + 恰好一个绑定参数，不再物化账号 id")
    void statusFilterIsPushedDownAsExists() {
        QueryWrapper<Staff> query = AdminController.staffFilter(null, "DISABLED");
        String sql = sqlOf(query);

        assertTrue(sql.contains("EXISTS"), "status 应下推为库内 EXISTS：" + sql);
        assertTrue(sql.contains("sys_user"), "EXISTS 应作用在关联的 sys_user 上：" + sql);
        assertFalse(sql.contains("LIKE"), "只按 status 筛选时不该出现关键字条件：" + sql);
        assertEquals(1, query.getParamNameValuePairs().size(),
                "只应绑定 status 一个参数（旧实现会再塞进一批账号 id）：" + sql);
        assertTrue(query.getParamNameValuePairs().containsValue(0),
                "DISABLED 应绑成 0：" + sql);
    }

    @Test
    @DisplayName("keyword + status：两个独立条件 AND 取交集，关键字整体成组、无法绕过状态")
    void keywordCannotBypassTheStatusScope() {
        QueryWrapper<Staff> query = AdminController.staffFilter("测试部", "ACTIVE");
        String sql = sqlOf(query);

        assertTrue(sql.contains("EXISTS"), sql);
        assertTrue(sql.contains("LIKE"), sql);
        assertTrue(sql.contains(") AND ("),
                "status 与关键字必须是两个独立条件（AND 连接），而不是同一个 OR 组：" + sql);
        assertFalse(sql.contains("user_id IN"),
                "不得再把命中的账号 id 物化回 Java 后拼 IN：" + sql);
        assertTrue(query.getParamNameValuePairs().containsValue("%测试部%"),
                "关键字应作为绑定参数（不做字符串拼接）：" + sql);
        assertTrue(query.getParamNameValuePairs().containsValue(1),
                "ACTIVE 应绑成 1：" + sql);
    }

    @Test
    @DisplayName("两个参数都没给（或缺省为空）时不追加任何条件")
    void noFilterMeansNoCondition() {
        assertTrue(sqlOf(AdminController.staffFilter(null, null)).trim().isEmpty());
        assertTrue(sqlOf(AdminController.staffFilter("   ", "   ")).trim().isEmpty());
        assertTrue(sqlOf(AdminController.staffFilter(null, "")).trim().isEmpty());
    }

    @Test
    @DisplayName("契约枚举之外的 status 直接 422，不得静默当成停用")
    void statusOutsideTheContractEnumIsRejected() {
        for (String invalid : new String[]{"DISABLE", "active", "1", "ALL"}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> AdminController.staffFilter(null, invalid), "应拒绝 " + invalid);
            assertEquals(422, ex.getStatus(), invalid);
            assertEquals("VALIDATION_ERROR", ex.getCode(), invalid);
        }
    }

    private static String sqlOf(QueryWrapper<Staff> query) {
        String segment = query.getSqlSegment();
        return segment == null ? "" : segment;
    }
}
