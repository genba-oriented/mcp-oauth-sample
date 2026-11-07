package com.example.leave.mcp.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 休暇申請API を<b>ユーザーとして</b>呼ぶ。
 *
 * <p>Claude から受けたアクセストークン（aud=leave-mcp-server）は転送しない（MCP 仕様の token passthrough 禁止）。
 * {@link LeaveApiTokenExchanger} で休暇申請API 用のトークン（aud=leave-api）に替えてから付ける。
 * 各メソッドの {@code token} は Claude から受けたトークン（交換前）。
 *
 * <p>応答は JSON をそのまま返す（ツールの結果として Claude が読む）。何ができるか（上長か、残日数は足りるか）は
 * 休暇申請API が判定し、エラーは Problem Details の detail を Claude に伝わる文にして返す。
 */
@Component
public class LeaveApiClient {

    private final RestClient restClient;
    private final LeaveApiTokenExchanger leaveApiTokenExchanger;
    private final JsonMapper jsonMapper;

    LeaveApiClient(RestClient.Builder httpBuilder, @Value("${app.leave-api.base-url}") String baseUrl,
                   LeaveApiTokenExchanger leaveApiTokenExchanger, JsonMapper jsonMapper) {
        this.restClient = httpBuilder.baseUrl(baseUrl)
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                throw new LeaveApiException(message(response));
            })
            .build();
        this.leaveApiTokenExchanger = leaveApiTokenExchanger;
        this.jsonMapper = jsonMapper;
    }

    public JsonNode me(String token) {
        return get("/me", token);
    }

    public JsonNode myRequests(String token) {
        return get("/leave-requests", token);
    }

    public JsonNode submit(String token, SubmitRequest request) {
        return post("/leave-requests", token, request);
    }

    public JsonNode cancel(String token, long id) {
        return post("/leave-requests/" + id + "/cancel", token, null);
    }

    public JsonNode pendingApprovals(String token) {
        return get("/approvals", token);
    }

    public JsonNode approve(String token, long id, String comment) {
        return post("/approvals/" + id + "/approve", token, new Decision(comment));
    }

    public JsonNode reject(String token, long id, String comment) {
        return post("/approvals/" + id + "/reject", token, new Decision(comment));
    }

    /** POST /leave-requests の本文。休暇申請API の LeaveRequestInput と対応する。 */
    public record SubmitRequest(String type, LocalDate startDate, LocalDate endDate, String dayPart, String reason) {
    }

    /** POST /approvals/{id}/approve・reject の本文。 */
    record Decision(String comment) {
    }

    private JsonNode get(String path, String token) {
        return restClient.get().uri(path)
            .header(HttpHeaders.AUTHORIZATION, bearer(token))
            .retrieve().body(JsonNode.class);
    }

    private JsonNode post(String path, String token, Object body) {
        RestClient.RequestBodySpec spec = restClient.post().uri(path)
            .header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().body(JsonNode.class);
    }

    /** Claude のトークンを交換して、休暇申請API 用のトークンを Authorization に載せる。 */
    private String bearer(String callerToken) {
        return "Bearer " + leaveApiTokenExchanger.exchange(callerToken);
    }

    private String message(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String detail = detail(response);
        return switch (status) {
            case 401 -> "休暇申請API で認証に失敗しました（HTTP 401）。コネクタに再ログインしてください。";
            case 403 -> detail != null ? detail : "休暇申請API がこの操作を拒否しました（権限がありません）。";
            default -> detail != null ? detail : "休暇申請API でエラーが発生しました (HTTP " + status + ")";
        };
    }

    private String detail(ClientHttpResponse response) {
        try {
            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode detail = jsonMapper.readTree(body).get("detail");
            return detail != null && detail.isString() && !detail.asString().isBlank() ? detail.asString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
