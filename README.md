# mcp-oauth-sample

MCP Server を OAuth 2.0 で保護するサンプルです。題材は休暇申請システムで、Claude などの MCP クライアントから、
ログインしたユーザー本人として休暇の参照・申請・承認を行います。

## サーバー構成

```
  Claude / MCP Inspector（MCP Client）
      │         │
      │         │ ① ログイン・同意（ブラウザ）、トークン取得（aud=leave-mcp-server）
      │         ▼
      │     ┌────────────────────────────┐
      │     │ Keycloak            :19000 │  認可サーバー（realm: sample-corp）
      │     └────────────────────────────┘
      │         ▲
      │         │ ③ Token Exchange（aud=leave-mcp-server → aud=leave-api）
      │ ② MCP   │
      │ POST /mcp
      ▼         │
  ┌────────────────────────────┐
  │ leave-mcp-server    :19003 │  MCP Server（Resource Server）
  └────────────────────────────┘
      │
      │ ④ REST API（aud=leave-api のトークン）
      ▼
  ┌────────────────────────────┐
  │ leave-api           :19002 │  休暇申請API（Resource Server）
  └────────────────────────────┘
```

| サーバー | ポート | URL |
|---|---|---|
| Keycloak | 19000 | http://localhost:19000 |
| leave-api | 19002 | http://localhost:19002 |
| leave-mcp-server | 19003 | http://localhost:19003/mcp |

## フォルダとファイル

| 名前 | 内容 |
|---|---|
| `keycloak/` | 認可サーバー Keycloak の設定。`compose.yaml` で起動し、`import/sample-corp-realm.json`（client・scope・ユーザーの定義）を起動時に読み込む。`compose.tunnel.yaml` はトンネルで公開するときの上書き設定 |
| `leave-api/` | 休暇申請API（Spring Boot）。休暇の残日数・申請・承認の業務ルールを持つ。データはメモリ上で、起動のたびに初期データに戻る |
| `leave-mcp-server/` | MCP Server（Spring AI）。休暇申請API の機能を MCP のツールとして公開する。受け取ったトークンの検証、ツールごとの scope の確認（足りなければ 403 insufficient_scope）、Token Exchange を行う |
| `dev.sh` | 上の3つをまとめて起動・停止するスクリプト |
| `tunnel.sh` | Keycloak と leave-mcp-server を Cloudflare Quick Tunnel でインターネットに公開するスクリプト。Claude のカスタムコネクタは Anthropic のクラウドから接続するので、localhost には届かない。そのときに使う |

### scope

| scope | 用途 |
|---|---|
| `leave-mcp-server` | aud=leave-mcp-server のトークンを発行してもらう |
| `leave-mcp:read` | 参照系のツール。最初の認可で要求される |
| `leave-mcp:write` | 更新系のツール（申請・取消・承認・却下）。使うときに 403 insufficient_scope で要求され、クライアントが再認可する（step-up） |

## 動かし方

Docker と Java 21 以上が必要です（Maven は Maven Wrapper を同梱）。`tunnel.sh` を使う場合は `cloudflared` も必要です。

```bash
./dev.sh up        # 全部起動（./dev.sh up keycloak のように個別でも可）
./dev.sh status    # 起動状態
./dev.sh logs      # ログ
./dev.sh down      # 全部停止
```

MCP Inspector からは http://localhost:19003/mcp に接続します（OAuth client: `mcp-inspector` / `mcp-inspector-secret`）。

Claude のカスタムコネクタから使う場合は、トンネルで公開してから起動し直します。

```bash
./tunnel.sh up     # 公開 URL を tunnel.env に書く
./dev.sh restart   # tunnel.env があれば公開 URL で起動する
```

表示された `https://xxx.trycloudflare.com/mcp` をコネクタに登録します（OAuth client: `claude-desktop` / `claude-desktop-secret`）。
Quick Tunnel の URL は毎回変わるので、張り直したら `./dev.sh restart` とコネクタの再登録が必要です。

### ログインユーザー

| ユーザー | パスワード | 役割 |
|---|---|---|
| yamada | yamada | 社員 |
| suzuki | suzuki | 社員 |
| sato | sato | 上長（yamada・suzuki の承認者） |

Keycloak の管理コンソール: http://localhost:19000/admin/master/console/#/sample-corp （`admin` / `admin`）

## 注意

これはデモ用のサンプルです。管理者パスワード・ユーザーのパスワード・client secret はすべて公開されている固定値です。
`tunnel.sh` は Keycloak の管理コンソールも含めてインターネットに公開するので、トンネルは使い終わったら `./tunnel.sh down` で閉じてください。
本番環境で使う場合は、これらの値を必ず変更してください。

## ライセンス

[MIT](LICENSE)
