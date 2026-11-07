package com.example.leave.mcp.tool;

import java.time.LocalDate;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.example.leave.mcp.client.LeaveApiClient;
import com.example.leave.mcp.client.LeaveApiClient.SubmitRequest;
import com.example.leave.mcp.config.McpRequiredOauthScopes;

import tools.jackson.databind.JsonNode;

/**
 * 休暇申請の MCP ツール。休暇申請API の1エンドポイントに1ツールが対応する。
 *
 * <p>ツールに要る scope は {@link McpRequiredOauthScopes} で書く（参照系は {@code leave-mcp:read}、
 * 更新系の申請・取消・承認・却下は {@code leave-mcp:write}）。
 * 確認はツールが呼ばれる前に McpScopeConfig が行い、足りなければ 403 insufficient_scope になる
 * （MCP クライアントはそれを見て、scope を足して再認可する）。それ以外は Claude から受けたトークンで休暇申請API を
 * 呼ぶだけで、上長か・残日数は足りるか・本人の申請か、は休暇申請API がユーザー本人の権限で判定する。
 * 画面（SPA）から操作したときと同じルールで、同じエラーが返る。
 *
 * <p>{@code @McpTool} のメソッドは、Spring AI の annotation scanner（{@code spring.ai.mcp.server.annotation-scanner}、
 * 既定で有効）が見つけて MCP ツールとして登録する。登録用の Bean は要らない。
 * {@code annotations} はクライアントへのヒント（MCP の tool annotations）。指定しないと
 * readOnlyHint=false・destructiveHint=true（既存のものを変える・消す）になるので、参照系は readOnlyHint、
 * 申請（追加するだけ）は destructiveHint=false にしている。ヒントなのでアクセス制御にはならない。
 *
 * <p>呼び出し元のトークンは {@link SecurityContextHolder} から取る（{@link #callerToken()}）。
 * Spring AI は WebMVC（サーブレット）の sync MCP Server に immediateExecution を設定するので、ツールは
 * HTTP リクエストと同じスレッドで動き、Spring Security が検証した JWT がそのまま見える。
 * ツールの中で別スレッド（{@code @Async} など）に渡したり、server type を ASYNC にしたりすると見えなくなる。
 */
@Component
public class LeaveTools {

    private final LeaveApiClient leaveApiClient;

    LeaveTools(LeaveApiClient leaveApiClient) {
        this.leaveApiClient = leaveApiClient;
    }

    @McpTool(name = "get_my_leave_balance",
             description = "ログインしているユーザー本人のプロフィール（氏名・部署・上長・ロール）と有給休暇の残日数を取得する。",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    @McpRequiredOauthScopes("leave-mcp:read")
    JsonNode getMyLeaveBalance() {
        return leaveApiClient.me(callerToken());
    }

    @McpTool(name = "list_my_leave_requests",
             description = "ユーザー本人の休暇申請の一覧を新しい順に取得する。状態は PENDING(申請中), APPROVED(承認済), REJECTED(却下), CANCELED(取消)。",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    @McpRequiredOauthScopes("leave-mcp:read")
    JsonNode listMyLeaveRequests() {
        return leaveApiClient.myRequests(callerToken());
    }

    @McpTool(name = "submit_leave_request",
             description = """
              ユーザー本人の休暇を申請する。申請は上長の承認待ち(PENDING)になる。
              残日数不足・過去日・同じ日の重複は休暇申請API がエラーで返す。""",
             annotations = @McpTool.McpAnnotations(destructiveHint = false))
    @McpRequiredOauthScopes("leave-mcp:write")
    JsonNode submitLeaveRequest(
            @McpToolParam(description = "休暇の開始日（yyyy-MM-dd）") LocalDate startDate,
            @McpToolParam(description = "休暇の終了日（yyyy-MM-dd）。1日だけなら省略するか開始日と同じ", required = false) LocalDate endDate,
            @McpToolParam(description = "種別。PAID(有給, 既定), SPECIAL(特別休暇), ABSENCE(欠勤)", required = false) String type,
            @McpToolParam(description = "区分。FULL(全日, 既定), AM(午前半休), PM(午後半休)。半休は1日だけ", required = false) String dayPart,
            @McpToolParam(description = "理由（任意）", required = false) String reason) {
        SubmitRequest request = new SubmitRequest(type != null ? type : "PAID", startDate, endDate,
            dayPart != null ? dayPart : "FULL", reason);
        return leaveApiClient.submit(callerToken(), request);
    }

    @McpTool(name = "cancel_leave_request",
             description = "ユーザー本人の申請中(PENDING)の休暇申請を取り消す。")
    @McpRequiredOauthScopes("leave-mcp:write")
    JsonNode cancelLeaveRequest(@McpToolParam(description = "申請番号") long id) {
        return leaveApiClient.cancel(callerToken(), id);
    }

    @McpTool(name = "list_pending_approvals",
             description = "上長として、自分が承認者になっている承認待ちの休暇申請を一覧する。上長以外は休暇申請API が拒否する。",
             annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    @McpRequiredOauthScopes("leave-mcp:read")
    JsonNode listPendingApprovals() {
        return leaveApiClient.pendingApprovals(callerToken());
    }

    @McpTool(name = "approve_leave_request",
             description = "上長として、部下の承認待ちの休暇申請を承認する。上長以外・自分が承認者でない申請は休暇申請API が拒否する。")
    @McpRequiredOauthScopes("leave-mcp:write")
    JsonNode approveLeaveRequest(
            @McpToolParam(description = "申請番号") long id,
            @McpToolParam(description = "承認コメント（任意）", required = false) String comment) {
        return leaveApiClient.approve(callerToken(), id, comment);
    }

    @McpTool(name = "reject_leave_request",
             description = "上長として、部下の承認待ちの休暇申請を却下する。上長以外・自分が承認者でない申請は休暇申請API が拒否する。")
    @McpRequiredOauthScopes("leave-mcp:write")
    JsonNode rejectLeaveRequest(
            @McpToolParam(description = "申請番号") long id,
            @McpToolParam(description = "却下の理由（任意）", required = false) String comment) {
        return leaveApiClient.reject(callerToken(), id, comment);
    }

    /** Claude から受けたアクセストークン（aud=leave-mcp-server。検証済み）。そのまま休暇申請API に転送する。 */
    private static String callerToken() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        throw new IllegalStateException("呼び出し元のトークンがありません（ツールがリクエストのスレッドの外で動いている）");
    }
}
