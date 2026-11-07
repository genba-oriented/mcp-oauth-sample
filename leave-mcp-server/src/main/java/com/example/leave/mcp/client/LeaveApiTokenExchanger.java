package com.example.leave.mcp.client;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * RFC 8693 Token Exchange: Claude のトークン（aud=leave-mcp-server）→ 休暇申請API 用のトークン（aud=leave-api）。
 *
 * <p>Keycloak の Standard Token Exchange（impersonation）。交換後のトークンは sub（ユーザー）がそのままで、
 * azp が leave-mcp-server になる。act クレームは付かない（委任ではなく、MCP Server がユーザーとして振る舞う）。
 *
 * <p>Keycloak 側の条件（keycloak/import/sample-corp-realm.json の client {@code leave-mcp-server}）:
 * <ul>
 *   <li>要求する client は confidential で、Standard token exchange が有効</li>
 *   <li>subject token の aud に、要求する client の client_id（leave-mcp-server）が入っている</li>
 *   <li>交換後のトークンの中身は要求した client の scope で決まる。aud=leave-api は optional scope {@code leave-api}
 *       （audience mapper）を {@code scope=leave-api} で要求して付ける。subject token の scope は引き継がない</li>
 * </ul>
 *
 * <p>交換結果は subject token ごとに、期限の少し前までキャッシュする（ツールを呼ぶたびに Keycloak へ行かない）。
 * そのため、Claude のトークンが失効しても、キャッシュ中の交換後トークンは期限まで使える。
 */
@Component
public class LeaveApiTokenExchanger {

    static final String GRANT_TYPE_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String TOKEN_TYPE_ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";

    /** 期限ちょうどまで使うと、休暇申請API に届いたときに切れていることがある。少し手前で捨てる。 */
    private static final long EXPIRY_MARGIN_SECONDS = 15;

    private final RestClient restClient;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final Clock clock;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    /** Spring が使うコンストラクタ（もう1つは時計を差し替えるテスト用）。 */
    @Autowired
    LeaveApiTokenExchanger(RestClient.Builder restClientBuilder,
                           @Value("${app.outbound.token-uri}") String tokenUri,
                           @Value("${app.outbound.client-id}") String clientId,
                           @Value("${app.outbound.client-secret}") String clientSecret,
                           @Value("${app.outbound.scope}") String scope) {
        this(restClientBuilder, tokenUri, clientId, clientSecret, scope, Clock.systemUTC());
    }

    LeaveApiTokenExchanger(RestClient.Builder restClientBuilder, String tokenUri, String clientId,
                           String clientSecret, String scope, Clock clock) {
        this.restClient = restClientBuilder
            .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                throw new LeaveApiException("休暇申請API 用のトークンへの交換に失敗しました（HTTP "
                    + response.getStatusCode().value() + "）。コネクタに再ログインしてください。" + body);
            })
            .build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.clock = clock;
    }

    /** Claude のトークンを、休暇申請API 用のトークンに替える。 */
    public String exchange(String subjectToken) {
        Instant now = clock.instant();
        CachedToken cached = cache.get(subjectToken);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.accessToken();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE_TOKEN_EXCHANGE);
        form.add("subject_token", subjectToken);
        form.add("subject_token_type", TOKEN_TYPE_ACCESS_TOKEN);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        // aud=leave-api は optional scope「leave-api」の audience mapper が付ける。
        form.add("scope", scope);

        JsonNode response = restClient.post().uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(JsonNode.class);

        String accessToken = response.get("access_token").asString();
        long expiresIn = response.get("expires_in").asLong();
        // 期限切れのものは掃除してから入れる（デモ用の単純なキャッシュ。件数の上限は無い）。
        cache.values().removeIf(token -> !now.isBefore(token.expiresAt()));
        cache.put(subjectToken, new CachedToken(accessToken, now.plusSeconds(Math.max(0, expiresIn - EXPIRY_MARGIN_SECONDS))));
        return accessToken;
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
    }
}
