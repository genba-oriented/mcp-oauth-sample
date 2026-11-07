package com.example.leave.mcp.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code @McpTool} のメソッドに付けて、そのツールに要る OAuth の scope を書く。
 * トークンの scope にすべて含まれていなければ、ツールは呼ばれず 403 insufficient_scope になる（{@link McpScopeConfig}）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpRequiredOauthScopes {

    String[] value();
}
