package com.example.leave.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.leave.api.entity.DayPart;
import com.example.leave.api.repository.LeaveRepository;
import com.example.leave.api.service.LeaveService;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 休暇申請API をセキュリティフィルタ込みで叩く。Keycloak 抜きで動く。
 *
 * <p>{@link JwtDecoder} だけモックし、「Bearer {username}」で来たらそのユーザーのクレームを持つ
 * Jwt を返す。主体の決定とロール（社員マスタから）は本物の {@code EmployeeAuthenticationConverter} が走る。
 * トークンにはロールを載せない（Keycloak もロールを出さない）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LeaveApiTest {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LeaveRepository leaveRepository;

    @BeforeEach
    void setUp() {
        // コンテキストは使い回し、データだけ初期状態に戻す（申請・取消・承認のテストが互いに影響しないように）。
        leaveRepository.reset();
        given(jwtDecoder.decode(anyString())).willAnswer(invocation -> token(invocation.getArgument(0)));
    }

    /**
     * トークン文字列 = username。ロールは載せない（誰か、だけ）。
     * 「yamada-claims-manager」は、トークンに manager ロールを載せてきた yamada（無視されることを確かめる用）。
     */
    private static Jwt token(String value) {
        Jwt.Builder builder = Jwt.withTokenValue(value)
            .header("alg", "RS256")
            .audience(List.of("leave-api"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300));
        if (value.equals("yamada-claims-manager")) {
            return builder.subject("uuid-of-yamada").claim("preferred_username", "yamada")
                .claim("realm_access", Map.of("roles", List.of("employee", "manager")))
                .build();
        }
        return builder.subject("uuid-of-" + value).claim("preferred_username", value).build();
    }

    private static String bearer(String username) {
        return "Bearer " + username;
    }

    /** 2週間以上先の月曜。初期データ（来週の申請）と重ならない。 */
    private static LocalDate freeMonday() {
        return LocalDate.now().plusDays(14).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    @Test
    void トークン無しは401() throws Exception {
        mockMvc.perform(get("/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void 社員は自分のプロフィールと残日数を取得できる() throws Exception {
        mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer("yamada")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("yamada"))
            .andExpect(jsonPath("$.name").value("山田 太郎"))
            .andExpect(jsonPath("$.managerName").value("佐藤 一郎"))
            .andExpect(jsonPath("$.roles", Matchers.contains("employee")))
            // 付与 20 − 承認済 2.5 − 申請中 1.0
            .andExpect(jsonPath("$.balance.granted").value(20.0))
            .andExpect(jsonPath("$.balance.used").value(2.5))
            .andExpect(jsonPath("$.balance.pending").value(1.0))
            .andExpect(jsonPath("$.balance.remaining").value(16.5));
    }

    @Test
    void 部下がいれば社員マスタからmanagerロールが付く() throws Exception {
        mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer("sato")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", Matchers.contains("employee", "manager")));
    }

    @Test
    void 社員マスタにいなければ403() throws Exception {
        mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer("outsider")))
            .andExpect(status().isForbidden());
    }

    @Test
    void 社員は承認待ち一覧を見られない() throws Exception {
        mockMvc.perform(get("/approvals").header(HttpHeaders.AUTHORIZATION, bearer("yamada")))
            .andExpect(status().isForbidden());
    }

    @Test
    void トークンに載ったロールは見ない() throws Exception {
        // realm_access.roles に manager があっても、社員マスタで部下がいなければ上長ではない
        mockMvc.perform(get("/approvals").header(HttpHeaders.AUTHORIZATION, bearer("yamada-claims-manager")))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer("yamada-claims-manager")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roles", Matchers.contains("employee")));
    }

    @Test
    void 自分の申請だけが一覧に出る() throws Exception {
        mockMvc.perform(get("/leave-requests").header(HttpHeaders.AUTHORIZATION, bearer("suzuki")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[*].employeeId", Matchers.everyItem(Matchers.is("suzuki"))));
    }

    @Test
    void 他人の申請は404で存在も明かさない() throws Exception {
        mockMvc.perform(get("/leave-requests/1001").header(HttpHeaders.AUTHORIZATION, bearer("suzuki")))
            .andExpect(status().isNotFound());
    }

    @Test
    void 申請すると申請中になり承認者は上長() throws Exception {
        mockMvc.perform(post("/leave-requests").header(HttpHeaders.AUTHORIZATION, bearer("yamada"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"PAID","startDate":"%s","dayPart":"FULL","reason":"私用のため"}
                    """.formatted(freeMonday())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.days").value(1.0))
            .andExpect(jsonPath("$.approverName").value("佐藤 一郎"))
            .andExpect(jsonPath("$.remainingDays").value(15.5));
    }

    @Test
    void 残日数を超える申請は400で理由を返す() throws Exception {
        LocalDate start = freeMonday();
        mockMvc.perform(post("/leave-requests").header(HttpHeaders.AUTHORIZATION, bearer("suzuki"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"PAID","startDate":"%s","endDate":"%s","dayPart":"FULL"}
                    """.formatted(start, start.plusWeeks(2).plusDays(4))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail", Matchers.startsWith("有給の残日数が足りません")));
    }

    @Test
    void 過去日の申請は400() throws Exception {
        mockMvc.perform(post("/leave-requests").header(HttpHeaders.AUTHORIZATION, bearer("yamada"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"PAID","startDate":"%s"}
                    """.formatted(LocalDate.now().minusDays(1))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("休暇日は本日以降を指定してください"));
    }

    @Test
    void 上長は部下の申請中を一覧し承認できる() throws Exception {
        mockMvc.perform(get("/approvals").header(HttpHeaders.AUTHORIZATION, bearer("sato")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].employeeId", Matchers.containsInAnyOrder("yamada", "suzuki")));

        // 1005 = 山田の来週月曜の申請（初期データ）
        mockMvc.perform(post("/approvals/1005/approve").header(HttpHeaders.AUTHORIZATION, bearer("sato"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"OK\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.approverComment").value("OK"));

        mockMvc.perform(post("/approvals/1005/approve").header(HttpHeaders.AUTHORIZATION, bearer("sato")))
            .andExpect(status().isConflict());
    }

    @Test
    void 本人は申請中の申請を取り消せる() throws Exception {
        mockMvc.perform(post("/leave-requests/1005/cancel").header(HttpHeaders.AUTHORIZATION, bearer("yamada")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELED"));
        mockMvc.perform(post("/leave-requests/1001/cancel").header(HttpHeaders.AUTHORIZATION, bearer("yamada")))
            .andExpect(status().isConflict());
    }

    @Test
    void 半休は0点5日() {
        LocalDate monday = freeMonday();
        org.assertj.core.api.Assertions.assertThat(
            LeaveService.countDays(monday, monday, DayPart.AM)).isEqualTo(0.5);
        org.assertj.core.api.Assertions.assertThat(
            LeaveService.countDays(monday, monday.plusDays(6), DayPart.FULL)).isEqualTo(5);
    }
}
