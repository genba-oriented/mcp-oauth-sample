#!/usr/bin/env bash
#
# tunnel.sh — mcp-oauth-sample のローカルの Keycloak (:19000) と休暇 MCP Server (:19003) を
#   Cloudflare Quick Tunnel で公開する。
#   Claude のカスタムコネクタは Anthropic のクラウドから繋ぎに来るので、localhost では届かない。
#   トークンエンドポイントも Anthropic のサーバーが直接叩くので、Keycloak も公開が要る。
#
# Usage:
#   ./tunnel.sh up       トンネルを2本張り、URL を tunnel.env に書く
#   ./tunnel.sh down     トンネルを閉じ、tunnel.env を消す
#   ./tunnel.sh status   URL と起動状態の表示
#
# 流れ:
#   ./tunnel.sh up
#   ./dev.sh restart     # dev.sh は tunnel.env があればトンネルモードで起動する
#   → 表示された https://xxx.trycloudflare.com/mcp を Claude のコネクタに登録
#      （OAuth client は「Use your own OAuth client」→ claude-desktop / claude-desktop-secret）
#   休暇 MCP Server → Keycloak の Token Exchange は localhost で行うので、トンネルは要らない。
#   MCP Inspector もトンネルモード中は公開 URL の /mcp に繋ぐ（PRM の resource が公開 URL になるため）。
#
# Quick Tunnel の制約（https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/）:
#   - アカウント不要。そのかわり URL は毎回変わる（up し直したら dev.sh restart とコネクタの再登録が要る）。
#   - SSE は非対応（ストリームは閉じるまでバッファされる）。MCP の POST 応答は1イベントで閉じるので動くが、
#     GET の常時接続ストリーム（サーバー発の通知）は届かない。このサーバーは通知を使っていない。
#   - 同時 200 リクエストまで。デモには十分。
set -euo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # mcp-oauth-sample/
LOG_DIR="$APP/.logs"
TUNNEL_ENV="$APP/tunnel.env"
mkdir -p "$LOG_DIR"

C=$'\033[0;36m'; G=$'\033[0;32m'; Y=$'\033[0;33m'; R=$'\033[0;31m'; N=$'\033[0m'
info()  { echo -e "${C}[tunnel]${N} $*"; }
ok()    { echo -e "${G}[tunnel]${N} $*"; }
warn()  { echo -e "${Y}[tunnel]${N} $*"; }
err()   { echo -e "${R}[tunnel]${N} $*" >&2; }

TUNNELS=(keycloak leave-mcp-server)
declare -A PORT_OF=([keycloak]=19000 [leave-mcp-server]=19003)
# tunnel.env の変数名。dev.sh が読む。
declare -A VAR_OF=([keycloak]=TUNNEL_KEYCLOAK_URL [leave-mcp-server]=TUNNEL_LEAVE_MCP_URL)

pidfile() { echo "$LOG_DIR/tunnel-$1.pid"; }
logfile() { echo "$LOG_DIR/tunnel-$1.log"; }

find_cloudflared() {
  if command -v cloudflared >/dev/null 2>&1; then command -v cloudflared
  elif [ -x "$HOME/.local/bin/cloudflared" ]; then echo "$HOME/.local/bin/cloudflared"
  else
    err "cloudflared が見つかりません。例: curl -fsSL -o ~/.local/bin/cloudflared https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 && chmod +x ~/.local/bin/cloudflared"
    exit 1
  fi
}

is_running() { # $1=name
  local pf pid
  pf="$(pidfile "$1")"
  [ -f "$pf" ] || return 1
  pid="$(cat "$pf")"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

url_of() { # $1=name — cloudflared がログに出す https://xxx.trycloudflare.com
  grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' "$(logfile "$1")" 2>/dev/null | head -1
}

start_one() { # $1=name
  local name="$1" bin log tries=0 url
  bin="$(find_cloudflared)"
  log="$(logfile "$name")"
  : >"$log"
  info "起動: $name (localhost:${PORT_OF[$name]})"
  "$bin" tunnel --no-autoupdate --url "http://localhost:${PORT_OF[$name]}" >"$log" 2>&1 &
  echo $! >"$(pidfile "$name")"
  until url="$(url_of "$name")" && [ -n "$url" ]; do
    if ! is_running "$name"; then err "cloudflared が終了しました ($log を確認)"; return 1; fi
    tries=$((tries+1))
    if [ "$tries" -ge 30 ]; then err "URL が出てきません ($log を確認)"; return 1; fi
    sleep 1
  done
  ok "$name → $url"
}

stop_one() { # $1=name
  local name="$1" pf pid
  pf="$(pidfile "$name")"
  if [ -f "$pf" ]; then
    pid="$(cat "$pf")"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      info "停止: $name (pid $pid)"
      kill "$pid" 2>/dev/null || true
    fi
    rm -f "$pf"
  fi
}

cmd_up() {
  local name running=0
  for name in "${TUNNELS[@]}"; do is_running "$name" && running=1; done
  if [ "$running" = 1 ]; then
    warn "既にトンネルが動いています。張り直すなら ./tunnel.sh down してから up（URL が変わります）"
    cmd_status
    return 0
  fi
  for name in "${TUNNELS[@]}"; do
    start_one "$name" || { cmd_down; return 1; }
  done
  {
    echo "# ./tunnel.sh up が生成。dev.sh はこのファイルがあればトンネルモードで起動する。"
    for name in "${TUNNELS[@]}"; do echo "${VAR_OF[$name]}=$(url_of "$name")"; done
  } >"$TUNNEL_ENV"
  ok "書き出し: $TUNNEL_ENV"
  info "DNS が行き渡るまで数十秒かかることがあります"
  info "次: ./dev.sh restart（起動済みなら）または ./dev.sh up"
  connector_urls
}

# Claude のカスタムコネクタに登録する URL（MCP Server ごと）。
connector_urls() {
  info "Claude のコネクタ URL（休暇）: $(url_of leave-mcp-server)/mcp"
}

cmd_down() {
  local name
  for name in "${TUNNELS[@]}"; do stop_one "$name"; done
  if [ -f "$TUNNEL_ENV" ]; then
    rm -f "$TUNNEL_ENV"
    ok "$TUNNEL_ENV を削除しました"
    info "ローカル（localhost）設定に戻すには ./dev.sh restart"
  fi
}

cmd_status() {
  local name
  printf "%-17s %-8s %s\n" TUNNEL STATUS URL
  for name in "${TUNNELS[@]}"; do
    if is_running "$name"; then printf "%-17s %-8s %s\n" "$name" running "$(url_of "$name")"
    else printf "%-17s %-8s %s\n" "$name" stopped -
    fi
  done
  [ -f "$TUNNEL_ENV" ] && connector_urls
  return 0
}

case "${1:-help}" in
  up|start) cmd_up ;;
  down|stop) cmd_down ;;
  status) cmd_status ;;
  help|--help|-h) sed -n '2,25p' "$0" ;;
  *) err "不明なコマンド: $1"; sed -n '8,11p' "$0"; exit 1 ;;
esac
