package com.example.leave.mcp.config;

import java.net.URI;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * MCP Server as an OAuth 2.0 Resource Server（MCP Authorization 仕様）。
 *
 * <ul>
 *   <li>{@code /mcp} は、署名・issuer・<b>aud=leave-mcp-server</b> を満たす JWT が必須。
 *       検証は application.yml の {@code spring.security.oauth2.resourceserver.jwt.*}（issuer-uri / audiences）。</li>
 *   <li>トークンが無ければ 401 + {@code WWW-Authenticate: Bearer resource_metadata="…", scope="…"}。
 *       MCP クライアント（Claude）はここから PRM → Keycloak の AS メタデータ → 認可、と進む。</li>
 *   <li>PRM（RFC 9728）は Spring Security が返す。{@code /.well-known/oauth-protected-resource} と
 *       {@code /.well-known/oauth-protected-resource/mcp} のどちらでも同じ JSON。401 / 403 はルートの方を指す。</li>
 * </ul>
 *
 * <p>ここで見るのは「この MCP Server 宛てのトークンか」だけ。ツールごとの scope の確認は
 * {@link McpScopeConfig}（MCP のルーターの手前）で行い、足りなければ 403 insufficient_scope を返す
 * （何ができるかの業務的な判定は、休暇申請API がユーザー本人の権限で決める）。
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            @Value("${app.prm.resource}") String resource,
                                            @Value("${app.prm.scope}") String scope,
                                            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri)
            throws Exception {
        http
            // Bearer トークンだけで認証する API なので CSRF 対策は不要（Cookie を使わない）。
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/.well-known/**", "/error").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                // JSONの中身を設定
                .protectedResourceMetadata(prm -> prm.protectedResourceMetadataCustomizer(metadata -> metadata
                    .resource(resource)
                    .authorizationServer(issuerUri)
                    // scopes_supported は 401 の scope と同じもの（既定は空）。設定値はスペース区切りなので、1つずつの要素にする。
                    .scopes(scopes -> scopes.addAll(List.of(scope.split(" ")))))) // scopes_supported
                .authenticationEntryPoint(prmAwareEntryPoint(resource, scope))
                .jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * 401 で PRM の場所と、要求してほしい scope を伝える（RFC 9728 §5.1, RFC 6750 §3）。
     * PRM の URL は resource のオリジン + {@code /.well-known/oauth-protected-resource}（ルート）。
     * Spring Security は PRM をルートでも返すので、resource のパス（/mcp）は付けない。
     *
     * <p>Spring Security の既定の 401 は {@code Bearer resource_metadata="http://localhost/.well-known/oauth-protected-resource"}
     * のように、URL をリクエストから組み立て（トンネル越しだと公開 URL にならない）、scope を付けない。
     * scope は Keycloak に aud=leave-mcp-server を付けてもらうために要るので（optional scope で audience を運ぶ）、
     * ここで設定値（app.prm.resource / app.prm.scope）から作る。
     *
     * <p>ツールに要る scope が足りないとき（{@link McpScopeConfig} が insufficient_scope を投げる）は 401 ではなく
     * 403 にし、足りない scope と PRM の場所を付ける（MCP 仕様「Runtime Insufficient Scope Errors」）。
     * MCP クライアントはこれを見て、今の scope に足して再認可する（step-up）。
     */
    static AuthenticationEntryPoint prmAwareEntryPoint(String resource, String scope) {
        URI uri = URI.create(resource);
        String metadataUrl = uri.getScheme() + "://" + uri.getAuthority()
            + "/.well-known/oauth-protected-resource";
        return (request, response, authException) -> {
            if (authException instanceof OAuth2AuthenticationException e
                    && e.getError() instanceof BearerTokenError error
                    && BearerTokenErrorCodes.INSUFFICIENT_SCOPE.equals(error.getErrorCode())) {
                response.setHeader("WWW-Authenticate",
                    "Bearer error=\"insufficient_scope\", error_description=\"" + error.getDescription()
                        + "\", scope=\"" + error.getScope() + "\", resource_metadata=\"" + metadataUrl + "\"");
                response.sendError(error.getHttpStatus().value(), "Forbidden");
                return;
            }
            response.setHeader("WWW-Authenticate",
                // scopeを指定しないと、step-upするときのscopeに含まれなくなってしまう
                "Bearer resource_metadata=\"" + metadataUrl + "\", scope=\"" + scope + "\"");
            response.sendError(401, "Unauthorized");
        };
    }
}
