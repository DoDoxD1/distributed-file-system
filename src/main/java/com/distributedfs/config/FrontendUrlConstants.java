package com.distributedfs.config;

import java.util.List;

public final class FrontendUrlConstants {

    public static final String LOCALHOST_FRONTEND_ORIGIN_PATTERN = "http://localhost:*";
    public static final String DUCKDNS_FRONTEND_ORIGIN = "http://dfs-ui.duckdns.org";
    public static final List<String> DEFAULT_CORS_ALLOWED_ORIGIN_PATTERNS = List.of(
        LOCALHOST_FRONTEND_ORIGIN_PATTERN,
        DUCKDNS_FRONTEND_ORIGIN
    );

    private FrontendUrlConstants() {
    }
}
