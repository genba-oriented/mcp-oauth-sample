package com.example.leave.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** RFC 8693 Token Exchange の要求の形と、交換結果のキャッシュ。Keycloak は MockRestServiceServer で代わりにする。 */
class LeaveApiTokenExchangerTest {

    static final String TOKEN_URI = "http://keycloak.test/realms/sample-corp/protocol/openid-connect/token";
    static final String CLAUDE_TOKEN = "token-issued-for-leave-mcp-server";

    /** テストから進められる時計。 */
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-09T10:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    MockRestServiceServer mockRestServiceServer;
    MutableClock mutableClock;
    LeaveApiTokenExchanger leaveApiTokenExchanger;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockRestServiceServer = MockRestServiceServer.bindTo(builder).build();
        mutableClock = new MutableClock();
        leaveApiTokenExchanger = new LeaveApiTokenExchanger(builder, TOKEN_URI, "leave-mcp-server",
            "leave-mcp-server-secret", "leave-api", mutableClock);
    }

    private void expectExchange(String issuedToken) {
        mockRestServiceServer.expect(requestTo(TOKEN_URI))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().formData(MultiValueMap.fromMultiValue(Map.of(
                "grant_type", List.of("urn:ietf:params:oauth:grant-type:token-exchange"),
                "subject_token", List.of(CLAUDE_TOKEN),
                "subject_token_type", List.of("urn:ietf:params:oauth:token-type:access_token"),
                "client_id", List.of("leave-mcp-server"),
                "client_secret", List.of("leave-mcp-server-secret"),
                "scope", List.of("leave-api")))))
            .andRespond(withSuccess("{\"access_token\":\"" + issuedToken + "\",\"expires_in\":300,"
                + "\"token_type\":\"Bearer\",\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}",
                MediaType.APPLICATION_JSON));
    }

    @Test
    void RFC8693の形で交換を要求し交換後のトークンを返す() {
        expectExchange("token-for-leave-api");

        assertThat(leaveApiTokenExchanger.exchange(CLAUDE_TOKEN)).isEqualTo("token-for-leave-api");
        mockRestServiceServer.verify();
    }

    @Test
    void 期限まではキャッシュを使いKeycloakに行かない() {
        expectExchange("token-for-leave-api");

        leaveApiTokenExchanger.exchange(CLAUDE_TOKEN);
        mutableClock.now = mutableClock.now.plus(Duration.ofSeconds(280)); // 300 - 15 秒より手前

        assertThat(leaveApiTokenExchanger.exchange(CLAUDE_TOKEN)).isEqualTo("token-for-leave-api");
        mockRestServiceServer.verify(); // 交換は1回だけ
    }

    @Test
    void 期限の少し手前を過ぎたら交換し直す() {
        expectExchange("first");
        expectExchange("second");

        assertThat(leaveApiTokenExchanger.exchange(CLAUDE_TOKEN)).isEqualTo("first");
        mutableClock.now = mutableClock.now.plus(Duration.ofSeconds(290)); // 300 - 15 秒を過ぎた

        assertThat(leaveApiTokenExchanger.exchange(CLAUDE_TOKEN)).isEqualTo("second");
        mockRestServiceServer.verify();
    }

    @Test
    void Keycloakが断ったらClaudeに伝わる文でエラーにする() {
        mockRestServiceServer.expect(requestTo(TOKEN_URI))
            // Keycloak 26.8 が subject token の aud に leave-mcp-server が無いときに返すもの
            .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"access_denied\",\"error_description\":\"Client is not within the token audience\"}"));

        assertThatThrownBy(() -> leaveApiTokenExchanger.exchange(CLAUDE_TOKEN))
            .isInstanceOf(LeaveApiException.class)
            .hasMessageContaining("交換に失敗しました（HTTP 403）")
            .hasMessageContaining("Client is not within the token audience");
    }
}
