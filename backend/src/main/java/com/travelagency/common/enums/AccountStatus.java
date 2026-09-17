package com.travelagency.common.enums;

/**
 * 账号状态，取值对齐契约 AccountStatus 枚举 [ACTIVE, DISABLED]。
 * 数据库 sys_user.status 用 1/0 表示，转换只在视图层做。
 */
public final class AccountStatus {
    public static final String ACTIVE = "ACTIVE";
    public static final String DISABLED = "DISABLED";

    private AccountStatus() {
    }

    public static String of(Integer status) {
        return status != null && status == 1 ? ACTIVE : DISABLED;
    }
}
