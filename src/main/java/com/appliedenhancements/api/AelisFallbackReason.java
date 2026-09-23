package com.appliedenhancements.api;

/** Structured category for a planner fallback; the original detail remains available. */
public enum AelisFallbackReason {
    NONE,
    OVERFLOW,
    TIME_LIMIT,
    NODE_LIMIT,
    RESOURCE_LIMIT,
    INVALID_REQUEST,
    NATIVE_BOUNDARY,
    ORDERED_CHOICE,
    COMPATIBILITY,
    INTERNAL_ERROR,
    OTHER;

    public static AelisFallbackReason fromDetail(String detail) {
        if (detail == null || detail.isBlank()) return NONE;
        var value = detail.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("internal")) return INTERNAL_ERROR;
        if (value.equals("invalid_request_amount") || value.equals("calculation_root_changed")) return INVALID_REQUEST;
        if (value.contains("overflow")) return OVERFLOW;
        if (value.contains("budget") || value.contains("time_limit")) return TIME_LIMIT;
        if (value.contains("node_limit") || value.contains("max_nodes")) return NODE_LIMIT;
        if (value.contains("native_boundary")) return NATIVE_BOUNDARY;
        if (value.contains("ordered")) return ORDERED_CHOICE;
        if (value.contains("depth_limit") || value.contains("search_limit")) return RESOURCE_LIMIT;
        if (value.contains("container") || value.contains("candidate") || value.contains("recipe")
                || value.contains("pattern") || value.contains("template") || value.contains("unsupported")) {
            return COMPATIBILITY;
        }
        return OTHER;
    }
}
