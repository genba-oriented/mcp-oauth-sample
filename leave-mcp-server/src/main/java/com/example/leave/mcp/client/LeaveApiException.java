package com.example.leave.mcp.client;

/**
 * 休暇申請API が断った（4xx/5xx）。Spring AI はツールの例外をメッセージ付きのエラー結果（isError=true）にして
 * MCP クライアントへ返すので、メッセージは Claude がそのままユーザーに伝えられる文にしておく。
 */
public class LeaveApiException extends RuntimeException {

    public LeaveApiException(String message) {
        super(message);
    }
}
