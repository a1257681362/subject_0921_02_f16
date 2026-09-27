#!/bin/bash
# apt-15 全链路日志边界验证脚本（管家可演示）
#
# 验证三条铁律：
#   1) 链路号线程内唯一：一个请求的日志只带本请求的链路号，绝不混进别人的单号；
#   2) 响应头与日志同源：响应头 X-Trace-Id == 响应体 traceId == 运维库中该请求日志的 traceId；
#   3) 边界不靠自觉：线程残留上下文在入口即被拦下（由自动化测试 TraceBoundaryInvariantTest 证明）。
#
# 用法：
#   1. 启动应用：mvn spring-boot:run（或 java -jar target/evops-base-*.jar）
#   2. 另开终端执行：bash docs/apt-15/verify-trace-boundary.sh
#   可选环境变量：BASE_URL（默认 http://127.0.0.1:8080）
#
# 脚本幂等：每次运行用时间戳生成新合同号/链路号，可重复执行。

set -u
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
SUF=$(date +%s)
PASS=0
FAIL=0

green() { printf '\033[32m%s\033[0m\n' "$1"; }
red()   { printf '\033[31m%s\033[0m\n' "$1"; }
ok()    { PASS=$((PASS+1)); green "  [PASS] $1"; }
ng()    { FAIL=$((FAIL+1)); red   "  [FAIL] $1"; }

# json_get '<json>' 'python 表达式（变量 d 为解析后的对象）'
json_get() { python3 -c "import sys,json;d=json.loads(sys.argv[1]);print($2)" "$1" 2>/dev/null; }

echo "== 0. 健康检查 ($BASE_URL)"
curl -sf "$BASE_URL/api/health" >/dev/null 2>&1 \
  && ok "应用可达" \
  || { red "  应用不可达，请先启动服务"; exit 1; }

echo "== 1. 登录（管家 steward01 / 主管 supervisor01）"
STEWARD=$(curl -sf -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"steward01","password":"steward123"}')
SUPERVISOR=$(curl -sf -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"supervisor01","password":"supervisor123"}')
STOKEN=$(json_get "$STEWARD" "d['data']['token']")
SPTOKEN=$(json_get "$SUPERVISOR" "d['data']['token']")
[ -n "$STOKEN" ] && [ -n "$SPTOKEN" ] && ok "拿到令牌" || { ng "登录失败"; exit 1; }

echo "== 2. 铁律二：响应头与日志（响应体）同源"
TMPH=$(mktemp)
BODY=$(curl -sf -D "$TMPH" "$BASE_URL/api/health" -H "X-Auth-Token: $STOKEN")
H_TRACE=$(grep -i '^X-Trace-Id:' "$TMPH" | tr -d '\r' | awk '{print $2}')
B_TRACE=$(json_get "$BODY" "d.get('traceId','')")
rm -f "$TMPH"
if [ -n "$H_TRACE" ] && [ "$H_TRACE" = "$B_TRACE" ]; then
  ok "响应头 X-Trace-Id == 响应体 traceId == $H_TRACE"
else
  ng "响应头($H_TRACE) 与响应体($B_TRACE) 不一致"
fi

echo "== 3. 准备房源台账 PROP-D15-$SUF"
curl -sf -X POST "$BASE_URL/api/apartment/property/entries" \
  -H "X-Auth-Token: $SPTOKEN" -H 'Content-Type: application/json' \
  -d "{\"propertyCode\":\"PROP-D15-$SUF\",\"community\":\"演示小区\",\"building\":\"1栋\",\"roomNo\":\"101\",\"area\":45.5,\"requestNo\":\"REQ-L-D15-$SUF\",\"operator\":\"demo\"}" >/dev/null \
  && ok "台账登记成功" || ng "台账登记失败（若为重复登记可忽略继续）"

lease_json() {
  cat <<EOF
{"contractNo":"$1","propertyCode":"PROP-D15-$SUF","tenantName":"演示租客","tenantPhone":"13800000000","leaseStart":"2026-01-01","leaseEnd":"2026-12-31","monthlyRent":3000,"deposit":1000,"requestNo":"REQ-$1","operator":"demo"}
EOF
}

CA="HT-D15-A-$SUF"; CB="HT-D15-B-$SUF"; CC="HT-D15-C-$SUF"
TA="d15-trace-a-$SUF"; TB="d15-trace-b-$SUF"; TC="d15-trace-c-$SUF"

echo "== 4. 两笔建档：合同 A（链路 $TA）与合同 B（链路 $TB）"
curl -sf -X POST "$BASE_URL/api/apartment/leases" -H "X-Auth-Token: $STOKEN" \
  -H "X-Trace-Id: $TA" -H 'Content-Type: application/json' -d "$(lease_json "$CA")" >/dev/null && ok "合同 A 建档" || ng "合同 A 建档失败"
curl -sf -X POST "$BASE_URL/api/apartment/leases" -H "X-Auth-Token: $STOKEN" \
  -H "X-Trace-Id: $TB" -H 'Content-Type: application/json' -d "$(lease_json "$CB")" >/dev/null && ok "合同 B 建档" || ng "合同 B 建档失败"

echo "   等待异步入库..."; sleep 3

check_contract_aggregation() {
  # $1=合同号 $2=期望链路号 $3=标签
  local resp rows bad
  resp=$(curl -sf "$BASE_URL/api/apartment/ops-log/entries?contractNo=$1" -H "X-Auth-Token: $STOKEN")
  rows=$(json_get "$resp" "d['data']['count']")
  bad=$(json_get "$resp" "sum(1 for r in d['data']['rows'] if r['contractNo']!='$1' or r['traceId']!='$2')")
  if [ "${rows:-0}" -ge 1 ] && [ "${bad:-1}" -eq 0 ]; then
    ok "$3：按合同号 $1 聚合到 $rows 行，全部归属本请求（链路号 $2）"
  else
    ng "$3：合同号 $1 聚合结果混入外来内容（rows=$rows, 异常=$bad）"
    echo "$resp" | python3 -m json.tool 2>/dev/null | head -30
  fi
}

echo "== 5. 铁律一：按合同号聚合，绝不混进别人的单号"
check_contract_aggregation "$CA" "$TA" "合同 A"
check_contract_aggregation "$CB" "$TB" "合同 B"

echo "== 6. 后台任务续传：签约归档任务（合同 C，链路 $TC）"
SUBMIT=$(curl -sf -X POST "$BASE_URL/api/apartment/sign-archive/tasks" -H "X-Auth-Token: $STOKEN" \
  -H "X-Trace-Id: $TC" -H 'Content-Type: application/json' -d "$(lease_json "$CC")")
TASK_NO=$(json_get "$SUBMIT" "d['data']['taskNo']")
[ -n "$TASK_NO" ] && ok "任务已提交 $TASK_NO" || { ng "任务提交失败"; TASK_NO=""; }

if [ -n "$TASK_NO" ]; then
  echo "   等待后台 20 步执行（模拟平台自动签署）..."
  ST=""
  for i in $(seq 1 60); do
    DETAIL=$(curl -sf "$BASE_URL/api/apartment/sign-archive/tasks/$TASK_NO" -H "X-Auth-Token: $STOKEN")
    ST=$(json_get "$DETAIL" "d['data']['status']")
    [ "$ST" = "SUCCESS" ] && break
    [ "$ST" = "FAILED" ] && break
    sleep 2
  done
  if [ "$ST" = "SUCCESS" ]; then
    ok "任务执行成功 100%"
    sleep 3
    RESP=$(curl -sf "$BASE_URL/api/apartment/ops-log/entries?contractNo=$CC" -H "X-Auth-Token: $STOKEN")
    ROWS=$(json_get "$RESP" "d['data']['count']")
    BAD=$(json_get "$RESP" "sum(1 for r in d['data']['rows'] if r['contractNo']!='$CC' or r['traceId']!='$TC')")
    BG=$(json_get "$RESP" "sum(1 for r in d['data']['rows'] if 'task.engine' in (r['logger'] or '') or 'esign' in (r['logger'] or ''))")
    if [ "${ROWS:-0}" -ge 2 ] && [ "${BAD:-1}" -eq 0 ] && [ "${BG:-0}" -ge 1 ]; then
      ok "合同 C 共 $ROWS 行日志（含后台步骤 $BG 行），全部同一链路号 $TC"
    else
      ng "合同 C 日志聚合异常（rows=$ROWS, 外来=$BAD, 后台行=$BG）"
    fi
  else
    ng "任务未成功（状态 $ST），跳过日志核验"
  fi
fi

echo "== 7. 非法入站链路号（疑似伪造/注入）不复用"
# 入站规约：仅字母/数字/中划线、8~64 位；下划线+超长即非法，服务端必须重新生成
INJECTED="INVALID_TRACE_ID_WITH_UNDERSCORES_AND_FAR_TOO_LONG_xxxxxxxxxxxxxxxxxxxx"
TMPH=$(mktemp)
curl -sf -D "$TMPH" "$BASE_URL/api/health" -H "X-Auth-Token: $STOKEN" \
  -H "X-Trace-Id: $INJECTED" >/dev/null 2>&1
H2=$(grep -i '^X-Trace-Id:' "$TMPH" | tr -d '\r' | awk '{print $2}')
rm -f "$TMPH"
if [ -z "$H2" ]; then
  ng "响应头缺失 X-Trace-Id"
elif [ "$H2" = "$INJECTED" ]; then
  ng "非法入站值被沿用: $H2"
elif printf '%s' "$H2" | grep -qE '^[A-Za-z0-9-]{8,64}$'; then
  ok "非法入站值被拒，服务端重新生成: $H2"
else
  ng "重新生成的链路号格式异常: $H2"
fi

echo
echo "== 8. 铁律三（边界不靠自觉）：自动化守卫"
echo "   线程残留上下文在入口即被拦下，由回归测试证明："
echo "   mvn test -Dtest=TraceBoundaryInvariantTest,TraceContextIsolationReproTest"
echo
echo "======================================"
if [ "$FAIL" -eq 0 ]; then
  green "全部 $PASS 项检查通过"
else
  red "通过 $PASS 项，失败 $FAIL 项"
fi
exit "$FAIL"
