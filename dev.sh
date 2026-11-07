#!/usr/bin/env bash
#
# dev.sh — mcp-oauth-sample を動かす。
#   Keycloak (docker compose) :19000 → 休暇申請API (Spring Boot) :19002 → 休暇MCP Server (Spring AI) :19003
#
# Usage:
#   ./dev.sh up [svc...]        起動（既定は全部）。各サービスが応答するまで待つ
#   ./dev.sh down [svc...]      停止（既定は全部）
#   ./dev.sh restart [svc...]   down → up（realm JSON を書き換えたら ./dev.sh restart keycloak）
#   ./dev.sh status
#   ./dev.sh logs [svc]         ログを tail（既定は leave-api・leave-mcp-server）
#
# svc: keycloak | leave-api | leave-mcp-server。複数指定しても依存順（up は keycloak → leave-api → leave-mcp-server）で処理する。
#
# トンネルモード（Claude Desktop から使うとき）: ./tunnel.sh up で tunnel.env ができると、
#   Keycloak の issuer（KC_HOSTNAME）と MCP Server の resource をトンネルの公開 URL にして起動する。
#   トンネルを張り直すと URL が変わるので、そのたびに ./dev.sh restart（Keycloak も含めて全部）。
# PID とログは mcp-oauth-sample/.logs/。
set -euo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # mcp-oauth-sample/
COMPOSE=(docker compose -f "$APP/keycloak/compose.yaml")
LOG_DIR="$APP/.logs"
mkdir -p "$LOG_DIR"

C=$'\033[0;36m'; G=$'\033[0;32m'; Y=$'\033[0;33m'; R=$'\033[0;31m'; N=$'\033[0m'
info()  { echo -e "${C}[mcp-oauth-sample]${N} $*"; }
ok()    { echo -e "${G}[mcp-oauth-sample]${N} $*"; }
warn()  { echo -e "${Y}[mcp-oauth-sample]${N} $*"; }
err()   { echo -e "${R}[mcp-oauth-sample]${N} $*" >&2; }

SERVICES=(keycloak leave-api leave-mcp-server)
declare -A PORT_OF=([keycloak]=19000 [leave-api]=19002 [leave-mcp-server]=19003)
REALM=sample-corp

# トンネルモード: ./tunnel.sh up が書いた tunnel.env があれば、issuer と MCP の resource を公開 URL にする。
#   application.yml には手を入れない（環境変数が勝つ）。
TUNNEL_ENV="$APP/tunnel.env"
TUNNEL_MODE=0
# MCP Server ごとの公開 resource（PRM の resource と 401 の resource_metadata になる）。
# APP_PRM_RESOURCE はサーバーごとに違うので export せず、起動時にそのサーバーにだけ渡す（mcp_env）。
LEAVE_MCP_RESOURCE=""
if [ -f "$TUNNEL_ENV" ]; then
  # shellcheck source=/dev/null
  . "$TUNNEL_ENV"
  TUNNEL_MODE=1
  export KC_HOSTNAME="$TUNNEL_KEYCLOAK_URL"                                                  # keycloak/compose.tunnel.yaml
  export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="$KC_HOSTNAME/realms/$REALM"   # leave-api, leave-mcp-server
  # Token Exchange の token-uri は localhost のまま（KC_HOSTNAME で iss は公開 URL に固定されるので、トンネルを往復しない）。
  LEAVE_MCP_RESOURCE="$TUNNEL_LEAVE_MCP_URL/mcp"
  COMPOSE+=(-f "$APP/keycloak/compose.tunnel.yaml")
fi

pidfile() { echo "$LOG_DIR/$1.pid"; }
logfile() { echo "$LOG_DIR/$1.log"; }

port_in_use() { # $1=port
  (echo >/dev/tcp/localhost/"$1") 2>/dev/null
}

# 止めた直後はまだポートが応答することがある（Docker Desktop・プロセスの終了待ち）。しばらく待つ。
wait_port_free() { # $1=port
  local tries=0
  while port_in_use "$1"; do
    tries=$((tries+1))
    if [ "$tries" -ge 15 ]; then return 1; fi
    sleep 1
  done
}

# ---------------------------------------------------------------- Keycloak (docker compose)

keycloak_running() {
  "${COMPOSE[@]}" ps --status running --quiet 2>/dev/null | grep -q .
}

keycloak_up() {
  local port="${PORT_OF[keycloak]}"
  if keycloak_running; then
    warn "Keycloak は既に起動中"
  elif ! wait_port_free "$port"; then
    local owner
    owner="$(docker ps --filter "publish=$port" --format '{{.Names}}' 2>/dev/null | paste -sd ' ' -)"
    err "ポート $port が使用中です${owner:+（コンテナ: $owner）}"
    return 1
  else
    info "起動: Keycloak (docker compose, realm $REALM) :$port"
    "${COMPOSE[@]}" up -d
  fi
  local tries=0 url="http://localhost:$port/realms/$REALM/.well-known/openid-configuration"
  info "Keycloak realm $REALM の準備を待機 ..."
  until curl -sf "$url" >/dev/null 2>&1; do
    tries=$((tries+1))
    if [ "$tries" -ge 60 ]; then err "Keycloak が起動しません: ./dev.sh logs keycloak"; return 1; fi
    sleep 2
  done
  ok "Keycloak realm $REALM は準備完了"
  wait_keycloak_tunnel
}

# leave-api / leave-mcp-server は起動時に issuer の discovery を引く（引けないと起動に失敗する）。
# トンネルモードでは issuer が公開 URL なので、トンネル経由で Keycloak に届くまで待つ（DNS の反映待ちなど）。
wait_keycloak_tunnel() {
  [ "$TUNNEL_MODE" = 1 ] || return 0
  local tries=0 url="$KC_HOSTNAME/realms/$REALM/.well-known/openid-configuration" doc issuer
  info "トンネル経由の Keycloak ($KC_HOSTNAME) を待機 ..."
  until doc="$(curl -sf --max-time 10 "$url" 2>/dev/null)" && [ -n "$doc" ]; do
    tries=$((tries+1))
    if [ "$tries" -ge 30 ]; then
      err "トンネル経由で Keycloak に届きません: ./tunnel.sh status / ./dev.sh status / .logs/tunnel-keycloak.log を確認"
      err "トンネルを張り直すなら: ./tunnel.sh down && ./tunnel.sh up && ./dev.sh restart（URL が変わるのでコネクタも再登録）"
      return 1
    fi
    sleep 2
  done
  # Keycloak が名乗る issuer（KC_HOSTNAME）が今のトンネル URL と違う ＝ トンネルを張り直したのに Keycloak を
  # 再起動していない。leave-api / leave-mcp-server は issuer 不一致で起動に失敗するので、ここで止める。
  issuer="$(printf '%s' "$doc" | sed -n 's/.*"issuer" *: *"\([^"]*\)".*/\1/p')"
  if [ -n "$issuer" ] && [ "$issuer" != "$KC_HOSTNAME/realms/$REALM" ]; then
    err "Keycloak の issuer ($issuer) が今のトンネル URL ($KC_HOSTNAME) と一致しません。"
    err "トンネルを張り直したあとは Keycloak も再起動が要ります: ./dev.sh restart（realm は JSON から作り直される）"
    return 1
  fi
  ok "トンネル経由の Keycloak も応答 (issuer: $issuer)"
}

keycloak_down() {
  if ! keycloak_running; then
    warn "Keycloak は起動していません"
    return 0
  fi
  info "Keycloak コンテナを停止します (docker compose down)"
  "${COMPOSE[@]}" down
}

# ---------------------------------------------------------------- leave-api / leave-mcp-server (プロセス)

# env に渡す引数。外から入ってきた APP_PRM_RESOURCE は消し、トンネルモードならそのサーバーの公開 resource だけを渡す。
#   トンネルモードでなければ application.yml の既定（http://localhost:<port>/mcp）のまま。
mcp_env() { # $1=resource（空ならトンネルモードでない）
  echo "-u APP_PRM_RESOURCE${1:+ APP_PRM_RESOURCE=$1}"
}

proc_running() { # $1=svc
  local pf
  pf="$(pidfile "$1")"
  [ -f "$pf" ] && kill -0 "$(cat "$pf")" 2>/dev/null
}

proc_up() { # $1=svc
  local svc="$1" port="${PORT_OF[$1]}" log timeout
  if proc_running "$svc"; then
    warn "$svc は既に起動中 (pid $(cat "$(pidfile "$svc")"))"
    return 0
  fi
  if ! wait_port_free "$port"; then
    err "ポート $port が使用中です"
    return 1
  fi
  wait_keycloak_tunnel || return 1
  log="$(logfile "$svc")"
  : >"$log"
  case "$svc" in
    leave-api)
      info "起動: 休暇申請API (./mvnw spring-boot:run) :$port"
      ( cd "$APP/leave-api" && ./mvnw -q spring-boot:run ) >"$log" 2>&1 &
      timeout=180   # 初回は Maven と依存ライブラリのダウンロードがある
      ;;
    leave-mcp-server)
      info "起動: 休暇 MCP Server (./mvnw spring-boot:run) :$port"
      # shellcheck disable=SC2046
      ( cd "$APP/leave-mcp-server" && env $(mcp_env "$LEAVE_MCP_RESOURCE") ./mvnw -q spring-boot:run ) >"$log" 2>&1 &
      timeout=180
      ;;
  esac
  echo $! >"$(pidfile "$svc")"
  local waited=0
  until port_in_use "$port"; do
    if ! proc_running "$svc"; then err "$svc が終了しました: $log を確認"; return 1; fi
    waited=$((waited+2))
    if [ "$waited" -ge "$timeout" ]; then err "$svc (localhost:$port) の起動がタイムアウト: $log を確認"; return 1; fi
    sleep 2
  done
  ok "$svc が localhost:$port で応答"
}

# .pid が指すのは mvnw / npm を包んだサブシェルで、実体の java / node は子として残る。ポート側からも止める。
kill_port() { # $1=port
  local port="$1" pid pids=()
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      mapfile -t pids < <(netstat -ano 2>/dev/null \
        | awk -v p=":$port" '$0 ~ /LISTENING/ && $2 ~ p"$" { print $NF }' | sort -u) || true
      for pid in ${pids[@]+"${pids[@]}"}; do
        [ -n "$pid" ] && [ "$pid" != 0 ] || continue
        taskkill //PID "$pid" //T //F >/dev/null 2>&1 || true
      done
      ;;
    *)
      if command -v lsof >/dev/null 2>&1; then
        mapfile -t pids < <(lsof -ti "tcp:$port" -sTCP:LISTEN 2>/dev/null | sort -u) || true
      elif command -v fuser >/dev/null 2>&1; then
        mapfile -t pids < <(fuser -n tcp "$port" 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+$') || true
      fi
      for pid in ${pids[@]+"${pids[@]}"}; do
        kill "$pid" 2>/dev/null || true
      done
      ;;
  esac
}

proc_down() { # $1=svc
  local svc="$1" pf
  pf="$(pidfile "$svc")"
  if proc_running "$svc"; then
    info "停止: $svc (pid $(cat "$pf"))"
    kill "$(cat "$pf")" 2>/dev/null || true
  elif ! port_in_use "${PORT_OF[$svc]}"; then
    warn "$svc は起動していません"
  fi
  rm -f "$pf"
  kill_port "${PORT_OF[$svc]}"
}

# ---------------------------------------------------------------- commands

svc_up()   { if [ "$1" = keycloak ]; then keycloak_up; else proc_up "$1"; fi; }
svc_down() { if [ "$1" = keycloak ]; then keycloak_down; else proc_down "$1"; fi; }

validate() { # $@=svcs
  local s
  for s in "$@"; do
    [[ -n "${PORT_OF[$s]:-}" ]] || { err "不明なサービス: $s（keycloak | leave-api | leave-mcp-server）"; exit 1; }
  done
}

# 引数のサービスを依存順に並べる（引数なしなら全部）。
ordered() { # $1=asc|desc, $2...=svcs
  local dir="$1" s list=()
  shift
  for s in "${SERVICES[@]}"; do
    if [ $# -eq 0 ] || [[ " $* " == *" $s "* ]]; then list+=("$s"); fi
  done
  if [ "$dir" = desc ]; then printf '%s\n' "${list[@]}" | tac; else printf '%s\n' "${list[@]}"; fi
}

cmd_up() {
  local s
  validate "$@"
  for s in $(ordered asc "$@"); do svc_up "$s"; done
  if [ $# -eq 0 ]; then
    ok "全サービス起動"
    info "ユーザー: yamada / suzuki（社員）, sato（上長）。パスワードはユーザー名と同じ"
    if [ "$TUNNEL_MODE" = 1 ]; then
      info "トンネルモード: Keycloak $KC_HOSTNAME"
      info "休暇 MCP Server: $LEAVE_MCP_RESOURCE"
      info "Claude のカスタムコネクタに登録（OAuth client は claude-desktop / claude-desktop-secret）"
    else
      info "休暇 MCP Server: http://localhost:${PORT_OF[leave-mcp-server]}/mcp"
      info "（MCP Inspector はこのまま使える。Claude Desktop から使うなら ./tunnel.sh up → ./dev.sh restart）"
    fi
    info "Keycloak 管理コンソール: http://localhost:${PORT_OF[keycloak]}/admin/master/console/#/$REALM （admin / admin）"
    info "ログ: ./dev.sh logs [svc] | 停止: ./dev.sh down"
  fi
}

cmd_down() {
  local s
  validate "$@"
  for s in $(ordered desc "$@"); do svc_down "$s"; done
  ok "停止しました"
}

cmd_status() {
  local s state
  for s in "${SERVICES[@]}"; do
    if [ "$s" = keycloak ]; then keycloak_running && state=running || state=stopped
    elif proc_running "$s"; then state="running (pid $(cat "$(pidfile "$s")"))"
    elif port_in_use "${PORT_OF[$s]}"; then state="port in use (この dev.sh 以外)"
    else state=stopped
    fi
    printf '%-17s :%-6s %s\n' "$s" "${PORT_OF[$s]}" "$state"
  done
}

cmd_logs() {
  case "${1:-}" in
    keycloak) exec "${COMPOSE[@]}" logs -f ;;
    leave-api|leave-mcp-server) exec tail -f "$(logfile "$1")" ;;
    "") exec tail -f "$(logfile leave-api)" "$(logfile leave-mcp-server)" ;;
    *) err "不明なサービス: $1"; exit 1 ;;
  esac
}

usage() {
  cat <<USAGE
Usage: $0 {up|down|restart} [svc...] | status | logs [svc]
  up [svc...]       起動 (既定は全部)
  down [svc...]     停止 (既定は全部)
  restart [svc...]  down → up
  status            起動状態の表示
  logs [svc]        ログを tail (既定は leave-api・leave-mcp-server)
  svc: keycloak | leave-api | leave-mcp-server
USAGE
}

cmd="${1:-help}"
[ $# -gt 0 ] && shift
case "$cmd" in
  up|start) cmd_up "$@" ;;
  down|stop) cmd_down "$@" ;;
  restart) cmd_down "$@"; cmd_up "$@" ;;
  status) cmd_status ;;
  logs) cmd_logs "${1:-}" ;;
  help|--help|-h) usage ;;
  *) err "不明なコマンド: $cmd"; usage; exit 1 ;;
esac
