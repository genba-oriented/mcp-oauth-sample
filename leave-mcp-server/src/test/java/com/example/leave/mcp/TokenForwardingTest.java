package com.example.leave.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * ツールが、Claude から受けたトークンを Token Exchange で替えてから休暇申請API を呼ぶことを、本物の HTTP で確かめる。
 * Claude のトークンは Keycloak の token エンドポイントにだけ（subject_token として）行き、休暇申請API には行かない。
 *
 * <p>ツールはトークンを SecurityContextHolder から取る（LeaveTools）。これはツールが HTTP リクエストと同じスレッドで
 * 動く（Spring AI がサーブレットの sync MCP Server に immediateExecution を設定する）前提に立っている。
 * Spring AI の版が変わってツールが別スレッドで動くようになったら、このテストが落ちる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenForwardingTest {

    /** テストごとに別の Claude のトークンを使う（交換結果はアプリ全体でキャッシュされるので、テストの順に影響されないように）。 */
    static final String CLAUDE_TOKEN_READ = "claude-token-for-read";
    static final String CLAUDE_TOKEN_WRITE = "claude-token-for-write";
    /** scope に leave-mcp:write を持つトークン（申請ツールの @McpRequiredOauthScopes を満たす）。 */
    static final String CLAUDE_TOKEN_WITH_WRITE_SCOPE = "claude-token-with-write-scope";
    static final String CLAUDE_TOKEN_WITHOUT_WRITE_SCOPE = "claude-token-without-write-scope";
    /** leave-mcp:read も持たないトークン（参照系ツールも 403 になる）。 */
    static final String CLAUDE_TOKEN_WITHOUT_READ_SCOPE = "claude-token-without-read-scope";
    static final String EXCHANGED_TOKEN = "token-issued-for-leave-api";
    static final String TOKEN_PATH = "/realms/sample-corp/protocol/openid-connect/token";

    /** 休暇申請API と Keycloak の token エンドポイントの代わり。受けた Authorization ヘッダーと交換の要求を覚える。 */
    static final HttpServer httpServer;
    static final Map<String, String> receivedAuthorization = new ConcurrentHashMap<>();
    static final List<String> receivedExchangeForms = new CopyOnWriteArrayList<>();

    static {
        try {
            httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        httpServer.createContext(TOKEN_PATH, exchange -> {
            receivedExchangeForms.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"access_token\":\"" + EXCHANGED_TOKEN + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpServer.createContext("/", exchange -> {
            receivedAuthorization.put(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(),
                String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"id\":\"yamada\",\"status\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpServer.start();
    }

    @DynamicPropertySource
    static void leaveApiUrl(DynamicPropertyRegistry registry) {
        registry.add("app.leave-api.base-url", () -> "http://localhost:" + httpServer.getAddress().getPort());
        registry.add("app.outbound.token-uri", () -> "http://localhost:" + httpServer.getAddress().getPort() + TOKEN_PATH);
    }

    @AfterAll
    static void stopLeaveApi() {
        httpServer.stop(0);
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @LocalServerPort
    int port;

    final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        receivedAuthorization.clear();
        receivedExchangeForms.clear();
        given(jwtDecoder.decode(anyString())).willAnswer(inv -> {
            String token = inv.getArgument(0);
            return Jwt.withTokenValue(token)
                .header("alg", "RS256").subject("uuid-of-yamada").claim("preferred_username", "yamada")
                .audience(List.of("leave-mcp-server"))
                // scope クレーム → SCOPE_* の権限（Spring Security の既定の変換）。
                // 最初の認可で付く leave-mcp:read は全員が持つ。leave-mcp:write は step-up した後のトークンだけ。
                .claim("scope", switch (token) {
                    case CLAUDE_TOKEN_WRITE, CLAUDE_TOKEN_WITH_WRITE_SCOPE -> "leave-mcp-server leave-mcp:read leave-mcp:write";
                    case CLAUDE_TOKEN_WITHOUT_READ_SCOPE -> "leave-mcp-server";
                    default -> "leave-mcp-server leave-mcp:read";
                })
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        });
    }

    private HttpResponse<String> mcp(String claudeToken, String body, String sessionId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .header("Authorization", "Bearer " + claudeToken)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String callTool(String claudeToken, String name, String arguments) throws Exception {
        return callToolResponse(claudeToken, name, arguments).body();
    }

    private HttpResponse<String> callToolResponse(String claudeToken, String name, String arguments) throws Exception {
        String sessionId = mcp(claudeToken, """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
             "capabilities":{},"clientInfo":{"name":"test","version":"0"}}}""", null)
            .headers().firstValue("Mcp-Session-Id").orElseThrow();
        mcp(claudeToken, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId);
        return mcp(claudeToken, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
            + "\",\"arguments\":" + arguments + "}}", sessionId);
    }

    @Test
    void 参照系のツールは交換後のトークンで休暇申請APIを呼ぶ() throws Exception {
        String response = callTool(CLAUDE_TOKEN_READ, "get_my_leave_balance", "{}");

        assertThat(response).contains("\"isError\":false").contains("yamada");
        assertThat(receivedAuthorization).containsEntry("GET /me", "Bearer " + EXCHANGED_TOKEN);
        // Claude のトークンは token エンドポイントに subject_token として渡しただけ
        assertThat(receivedExchangeForms).singleElement().asString()
            .contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange")
            .contains("subject_token=" + CLAUDE_TOKEN_READ)
            .contains("client_id=leave-mcp-server")
            .contains("scope=leave-api");
        assertThat(receivedAuthorization.values()).doesNotContain("Bearer " + CLAUDE_TOKEN_READ);
    }

    @Test
    void 更新系のツールも交換後のトークンで休暇申請APIを呼ぶ() throws Exception {
        String response = callTool(CLAUDE_TOKEN_WRITE, "approve_leave_request", "{\"id\":1005,\"comment\":\"OK\"}");

        assertThat(response).contains("\"isError\":false").contains("APPROVED");
        assertThat(receivedAuthorization).containsEntry("POST /approvals/1005/approve", "Bearer " + EXCHANGED_TOKEN);
        assertThat(receivedExchangeForms).singleElement().asString().contains("subject_token=" + CLAUDE_TOKEN_WRITE);
        assertThat(receivedAuthorization.values()).doesNotContain("Bearer " + CLAUDE_TOKEN_WRITE);
    }

    @Test
    void 申請はscopeにleave_mcp_writeがあれば通る() throws Exception {
        String response = callTool(CLAUDE_TOKEN_WITH_WRITE_SCOPE, "submit_leave_request", "{\"startDate\":\"2030-01-07\"}");

        assertThat(response).contains("\"isError\":false");
        assertThat(receivedAuthorization).containsEntry("POST /leave-requests", "Bearer " + EXCHANGED_TOKEN);
    }

    /**
     * scope が足りなければ、ツールは呼ばれず HTTP 403 + insufficient_scope（MCP 仕様「Runtime Insufficient Scope Errors」）。
     * MCP クライアントは scope と resource_metadata を見て、scope を足して再認可する（step-up）。
     */
    @Test
    void scopeが無ければ申請は403_insufficient_scopeになる() throws Exception {
        HttpResponse<String> response = callToolResponse(CLAUDE_TOKEN_WITHOUT_WRITE_SCOPE, "submit_leave_request",
            "{\"startDate\":\"2030-01-07\"}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue(
            "Bearer error=\"insufficient_scope\", error_description=\"This tool requires the scope: leave-mcp:write\", "
                + "scope=\"leave-mcp:write\", "
                + "resource_metadata=\"http://localhost:19003/.well-known/oauth-protected-resource\"");
        // ツールの本体まで行かないので、交換も休暇申請API の呼び出しも起きない
        assertThat(receivedExchangeForms).isEmpty();
        assertThat(receivedAuthorization).isEmpty();
    }

    @Test
    void 承認もscopeにleave_mcp_writeが無ければ403_insufficient_scopeになる() throws Exception {
        HttpResponse<String> response = callToolResponse(CLAUDE_TOKEN_WITHOUT_WRITE_SCOPE, "approve_leave_request",
            "{\"id\":1005}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate")).get().asString()
            .contains("error=\"insufficient_scope\"").contains("scope=\"leave-mcp:write\"");
        assertThat(receivedAuthorization).isEmpty();
    }

    @Test
    void 参照系もscopeにleave_mcp_readが無ければ403_insufficient_scopeになる() throws Exception {
        HttpResponse<String> response = callToolResponse(CLAUDE_TOKEN_WITHOUT_READ_SCOPE, "get_my_leave_balance", "{}");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate")).get().asString()
            .contains("error=\"insufficient_scope\"").contains("scope=\"leave-mcp:read\"");
        assertThat(receivedAuthorization).isEmpty();
    }

    /** scope が無くてもツールの一覧には出る（呼んで 403 を受けないと、クライアントは step-up できない）。 */
    @Test
    void scopeが無くてもtools_listには申請ツールが出る() throws Exception {
        String sessionId = mcp(CLAUDE_TOKEN_WITHOUT_WRITE_SCOPE, """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
             "capabilities":{},"clientInfo":{"name":"test","version":"0"}}}""", null)
            .headers().firstValue("Mcp-Session-Id").orElseThrow();
        mcp(CLAUDE_TOKEN_WITHOUT_WRITE_SCOPE, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId);
        HttpResponse<String> response = mcp(CLAUDE_TOKEN_WITHOUT_WRITE_SCOPE,
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", sessionId);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"name\":\"submit_leave_request\"");
    }
}
