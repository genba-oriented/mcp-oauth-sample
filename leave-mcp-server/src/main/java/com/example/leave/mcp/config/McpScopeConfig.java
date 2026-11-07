package com.example.leave.mcp.config;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.spring.AnnotationProviderUtil;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration.ServerMcpAnnotatedBeans;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ツールごとの scope の確認（MCP 仕様 Authorization「Runtime Insufficient Scope Errors」）。
 *
 * <p>ツールは全部 {@code POST /mcp} の JSON-RPC（{@code tools/call} の {@code params.name}）で呼ばれるので、
 * URL では分けられない。そこで Spring AI の MCP のルーターにフィルターを挟み、ツールが呼ばれる前に
 * {@link McpRequiredOauthScopes} の scope がトークンにあるかを見る。足りなければ Spring Security の
 * {@code insufficient_scope} エラーを投げ、{@link SecurityConfig} のエントリーポイントが
 * 403 + {@code WWW-Authenticate: Bearer error="insufficient_scope", scope="…", resource_metadata="…"} にする。
 *
 * <p>{@code @PreAuthorize}（method security）では 403 にならない: ツールの中で拒否されるので、Spring AI が例外を
 * HTTP 200 のツール結果（isError: true）にしてしまい、MCP クライアントは step-up を始められない。
 * 方式は https://github.com/spring-projects/spring-ai/issues/6740#issuecomment-5217399970 による。
 */
@Configuration
class McpScopeConfig {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * Spring AI のルーター（同名の Bean）を置き換える。自動構成側は
     * {@code @ConditionalOnMissingBean(name = "webMvcStreamableServerRouterFunction")} なので、こちらが使われる。
     */
    @Bean(name = "webMvcStreamableServerRouterFunction")
    RouterFunction<ServerResponse> webMvcStreamableServerRouterFunction(WebMvcStreamableServerTransportProvider transport,
                                                                        ServerMcpAnnotatedBeans annotatedBeans) {
        // ツール名 → 要る scope。@McpTool の Bean はスキャナーが Bean を作りながら集めるので、最初のリクエストで作る。
        Supplier<Map<String, List<String>>> toolScopes = SingletonSupplier.of(() -> toolScopes(annotatedBeans));
        return transport.getRouterFunction().filter((request, next) -> {
            if (request.method() != HttpMethod.POST) {
                return next.handle(request);
            }
            String body = request.body(String.class);
            String tool = calledTool(body);
            if (tool != null) {
                assertScopes(toolScopes.get().getOrDefault(tool, List.of()));
            }
            // body は読んでしまったので、同じ中身で作り直して渡す
            return next.handle(ServerRequest.from(request).body(body).build());
        });
    }

    private static Map<String, List<String>> toolScopes(ServerMcpAnnotatedBeans annotatedBeans) {
        return annotatedBeans.getBeansByAnnotation(McpTool.class).stream()
            .flatMap(bean -> Arrays.stream(AnnotationProviderUtil.beanMethods(bean)))
            .filter(method -> method.isAnnotationPresent(McpTool.class) && method.isAnnotationPresent(McpRequiredOauthScopes.class))
            .collect(Collectors.toUnmodifiableMap(
                (Method method) -> method.getAnnotation(McpTool.class).name(),
                (Method method) -> List.of(method.getAnnotation(McpRequiredOauthScopes.class).value())));
    }

    /** {@code tools/call} ならツール名。それ以外（initialize・tools/list・通知・壊れた JSON）は null で素通し。 */
    private static String calledTool(String body) {
        try {
            JsonNode message = JSON.readTree(body);
            return "tools/call".equals(message.path("method").asString(null))
                ? message.path("params").path("name").asString(null) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void assertScopes(List<String> requiredScopes) {
        Set<String> granted = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toUnmodifiableSet());
        if (!requiredScopes.stream().allMatch(scope -> granted.contains("SCOPE_" + scope))) {
            // 必要な scope は1回のチャレンジで全部返す（MCP 仕様の SHOULD）。description は ASCII だけ（RFC 6750 §3）。
            throw new OAuth2AuthenticationException(BearerTokenErrors.insufficientScope(
                "This tool requires the scope: " + String.join(" ", requiredScopes), String.join(" ", requiredScopes)));
        }
    }
}
