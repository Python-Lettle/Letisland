package cn.lettle.letisland.log;

/**
 * 日志类型枚举
 */
public enum LogType {
    LOGIN("登录"),
    LOGOUT("登出"),
    CODEX_FISH("鱼类图鉴解锁"),
    CODEX_TITLE("称号解锁"),
    SENSITIVE("敏感操作");

    private final String displayName;

    LogType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
