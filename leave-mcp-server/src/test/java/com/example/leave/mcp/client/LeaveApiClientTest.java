package com.example.leave.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 休暇申請API の呼び方。Claude から受けたトークンは転送せず、交換後のトークン（aud=leave-api）を付ける。
 * 交換そのものは LeaveApiTokenExchangerTest で確かめるので、ここでは交換結果が決まった値になるスタブを使う。
 */
class LeaveApiClientTest {

    static final String BASE = "http://leave-api.test";
    static final String CLAUDE_TOKEN = "token-issued-for-leave-mcp-server";
    static final String EXCHANGED_TOKEN = "exchanged-for-" + CLAUDE_TOKEN;

    MockRestServiceServer mockRestServiceServer;
    LeaveApiClient leaveApiClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockRestServiceServer = MockRestServiceServer.bindTo(builder).build();
        LeaveApiTokenExchanger stubExchanger = new LeaveApiTokenExchanger(RestClient.builder(),
                "http://keycloak.test/token", "leave-mcp-server", "secret", "leave-api") {
            @Override
            public String exchange(String subjectToken) {
                return "exchanged-for-" + subjectToken;
            }
        };
        leaveApiClient = new LeaveApiClient(builder, BASE, stubExchanger, JsonMapper.builder().build());
    }

    @Test
    void 受けたトークンではなく交換後のトークンをAuthorizationに載せて呼ぶ() {
        mockRestServiceServer.expect(requestTo(BASE + "/me"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + EXCHANGED_TOKEN))
            .andRespond(withSuccess("{\"id\":\"yamada\",\"roles\":[\"employee\"]}", MediaType.APPLICATION_JSON));

        JsonNode me = leaveApiClient.me(CLAUDE_TOKEN);

        mockRestServiceServer.verify();
        assertThat(me.get("id").asString()).isEqualTo("yamada");
    }

    @Test
    void 承認はコメント付きでPOSTする() {
        mockRestServiceServer.expect(requestTo(BASE + "/approvals/1005/approve"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + EXCHANGED_TOKEN))
            .andExpect(content().json("{\"comment\":\"OK\"}"))
            .andRespond(withSuccess("{\"id\":1005,\"status\":\"APPROVED\"}", MediaType.APPLICATION_JSON));

        assertThat(leaveApiClient.approve(CLAUDE_TOKEN, 1005, "OK").get("status").asString()).isEqualTo("APPROVED");
        mockRestServiceServer.verify();
    }

    @Test
    void 休暇申請APIのエラーはdetailをメッセージにする() {
        mockRestServiceServer.expect(requestTo(BASE + "/leave-requests"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"status\":400,\"detail\":\"有給の残日数が足りません（残 1.0 日 / 申請 3.0 日）\"}"));

        assertThatThrownBy(() -> leaveApiClient.submit(CLAUDE_TOKEN,
                new LeaveApiClient.SubmitRequest("PAID", null, null, "FULL", null)))
            .isInstanceOf(LeaveApiException.class)
            .hasMessage("有給の残日数が足りません（残 1.0 日 / 申請 3.0 日）");
    }

    @Test
    void 上長でなければ403をそのまま伝える() {
        mockRestServiceServer.expect(requestTo(BASE + "/approvals"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> leaveApiClient.pendingApprovals(CLAUDE_TOKEN))
            .isInstanceOf(LeaveApiException.class)
            .hasMessageContaining("拒否");
    }
}
