"""로컬 모의 벤더 서버 — 커넥터가 부르는 벤더 API 의 요청·응답을 근거 문서(docs/vendor-connector-evidence.md)의 공식 예시대로 흉내 낸다.
실제 벤더를 부르지 않는다. 상태는 메모리(기동·/__reset 마다 처음으로). 계정은 fresh 조직 E(tools/dev-seed)의 것이다 — E2E 가 시드 A·B·C 의
연결·원장·청구를 바꾸지 않게 한다. 사용법·데이터는 같은 디렉터리의 README.md.

자격증명: `fake-vendor-credential-` 으로 시작하는 값만 받는다(Claude 는 `x-api-key`, Cursor 는 Basic 사용자 이름). 그 밖은 401 이다 —
실제 키가 받아들여지는 일이 없고, 잘못된 자격증명을 시험할 수 있다. Claude 는 `anthropic-version` 이 없으면 400.

Claude Enterprise Admin API (근거 §1.2)
  GET    /v1/organizations/users                    최근 추가 순, limit(기본 20·최대 1000)·after_id 페이지 → data·has_more·first_id·last_id
  GET    /v1/organizations/invites                  최근 순, pending·accepted·expired 를 모두(상태 거르기 없음), 같은 페이지
  DELETE /v1/organizations/users/{user_id}          {"type": "user_deleted", "id": …}. 관리자 역할은 400, 모르는 ID 는 404
  GET    /v1/organizations/analytics/cost_report    starting_at 필수(없으면 400). 두 쪽 — page 없으면 첫 쪽(has_more·next_page), next_page 값이면 끝 쪽
Cursor Enterprise Admin API (근거 §3)
  GET    /teams/members                             teamMembers(id·name·email·role·isRemoved). 제거한 구성원은 isRemoved: true
  POST   /teams/remove-member                       본문 userId 또는 email 하나 → success·userId·hasBillingCycleUsage. 둘 다·없음·모름·이미 제거·마지막 owner 는 success: false
  POST   /teams/spend                               page·pageSize → teamMemberSpend(제거한 구성원 포함, spendCents 합 3500 = $35.00)·subscriptionCycleStart(이번 달 1일 0시 UTC, epoch 밀리초)·totalMembers·totalPages
관리(E2E 확인용)
  GET    /__calls                                   받은 요청(메서드·경로·질의·본문·인증 헤더)
  POST   /__fault                                   {"path": 접두사, "status": 401|403|429|5xx, "times": n, "retryAfter": 초?} — 그 경로의 다음 n 번 요청에 그 상태. {"clear": true} 는 모두 끔
  POST   /__reset                                   상태·기록·오류 주입을 처음으로
그 밖의 경로는 404 다. 루프백 주소(127.0.0.1)에만 묶는다."""
import base64, datetime, json, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

FAKE_PREFIX = "fake-vendor-credential-"
# 근거 §1.2: 역할 값은 user·managed·owner·membership_admin·primary_owner. 관리 역할은 이 엔드포인트로 제거하지 못한다(400).
CLAUDE_ADMIN_ROLES = {"owner", "membership_admin", "primary_owner"}
CLAUDE_USERS = [  # (id, email, name, role, added_at)
    ("user_seed_e_owner", "owner@seed-e.example.test", "E Owner", "primary_owner", "2026-06-12T09:14:03Z"),
    ("user_seed_e_member1", "member1@seed-e.example.test", "E Member 1", "user", "2026-06-13T09:14:03Z"),
    ("user_partner_contractor", "contractor@partner.example.test", "Contractor", "user", "2026-06-14T09:14:03Z"),
]
CLAUDE_INVITES = [  # (id, email, role, status, invited_at, expires_at, accepted_at)
    ("invite_partner_newhire", "newhire@partner.example.test", "user", "pending", "2026-07-06T16:20:11Z", "2026-07-27T16:20:11Z", None),
    ("invite_seed_e_member1", "member1@seed-e.example.test", "user", "accepted", "2026-06-10T09:00:00Z", "2026-07-01T09:00:00Z", "2026-06-13T09:14:03Z"),
]
# 비용 보고서의 두 쪽 — 금액은 "fractional cents" 10진 문자열(근거: "41280.000000" = $412.80). 합 43000 센트 = $430.00.
CLAUDE_COST_PAGES = ["41280.000000", "1720.000000"]
CURSOR_MEMBERS = [  # (id, name, email, role, spendCents)
    ("user_seed_e_owner", "E Owner", "owner@seed-e.example.test", "owner", 0),
    ("user_seed_e_member1", "E Member 1", "member1@seed-e.example.test", "member", 2450.125487),
    ("user_partner_contractor", "Contractor", "contractor@partner.example.test", "member", 1049.874513),
]
lock = threading.Lock()

def initial():
    return {
        "claude_users": [{"type": "user", "id": i, "email": e, "name": n, "role": r, "added_at": a} for i, e, n, r, a in CLAUDE_USERS],
        "claude_invites": [{"type": "invite", "id": i, "email": e, "role": r, "invited_at": at, "expires_at": ex, "accepted_at": ac, "status": s, "rbac_group_ids": []}
                           for i, e, r, s, at, ex, ac in CLAUDE_INVITES],
        "cursor_members": [{"id": i, "name": n, "email": e, "role": r, "isRemoved": False, "spendCents": c} for i, n, e, r, c in CURSOR_MEMBERS],
        "calls": [], "faults": [],
    }

state = initial()

def page_by_id(items, query):
    """근거 §1.2: "pass limit (default 20, max 1000) plus at most one of before_id or after_id, and page using the first_id and last_id fields"."""
    limit = int((query.get("limit") or ["20"])[0])
    if not 1 <= limit <= 1000:
        return None
    after = (query.get("after_id") or [None])[0]
    start = 0
    if after is not None:
        index = next((n for n, item in enumerate(items) if item["id"] == after), None)
        if index is None:
            return None
        start = index + 1
    chunk = items[start:start + limit]
    return {"data": chunk, "has_more": start + limit < len(items),
            "first_id": chunk[0]["id"] if chunk else None, "last_id": chunk[-1]["id"] if chunk else None}

def cycle_start_ms():
    now = datetime.datetime.now(datetime.timezone.utc)
    return int(datetime.datetime(now.year, now.month, 1, tzinfo=datetime.timezone.utc).timestamp() * 1000)

def credential(headers, path):
    """요청의 자격증명. Claude 는 x-api-key, Cursor 는 Basic 의 사용자 이름(비밀번호는 비운다)."""
    if path.startswith("/v1/"):
        return headers.get("x-api-key")
    auth = headers.get("Authorization") or ""
    if not auth.startswith("Basic "):
        return None
    try:
        user, _, _ = base64.b64decode(auth[6:]).decode().partition(":")
        return user
    except Exception:
        return None

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def send(self, status, body, headers=None):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data)))
        for key, value in (headers or {}).items(): self.send_header(key, value)
        self.end_headers(); self.wfile.write(data)
    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"null") if length else None
    def handle_any(self, method):
        global state
        url = urlsplit(self.path)
        path, query = url.path, parse_qs(url.query)
        body = self.body() if method in ("POST", "DELETE", "PUT") else None
        if path == "/__calls" and method == "GET":
            with lock: return self.send(200, {"calls": state["calls"]})
        if path == "/__reset" and method == "POST":
            with lock: state = initial()
            return self.send(200, {})
        if path == "/__fault" and method == "POST":
            request = body if isinstance(body, dict) else {}
            with lock:
                if request.get("clear"):
                    state["faults"] = []
                    return self.send(200, {})
                status, times = request.get("status"), request.get("times", 1)
                if not isinstance(request.get("path"), str) or status not in (401, 403, 429) and not (isinstance(status, int) and 500 <= status <= 599) or not isinstance(times, int) or times < 1:
                    return self.send(400, {"error": "path·status(401·403·429·5xx)·times(1 이상)"})
                state["faults"].append({"path": request["path"], "status": status, "times": times, "retryAfter": request.get("retryAfter")})
            return self.send(200, {})
        with lock:
            state["calls"].append({"method": method, "path": path, "query": {k: v[0] for k, v in query.items()}, "body": body,
                                   "authorization": self.headers.get("Authorization"), "xApiKey": self.headers.get("x-api-key"),
                                   "anthropicVersion": self.headers.get("anthropic-version")})
            fault = next((f for f in state["faults"] if path.startswith(f["path"]) and f["times"] > 0), None)
            if fault:
                fault["times"] -= 1
                extra = {"Retry-After": str(fault["retryAfter"])} if fault.get("retryAfter") is not None else {}
                return self.send(fault["status"], {"error": "injected"}, extra)
        vendor = path.startswith("/v1/organizations/") or path.startswith("/teams/")
        if not vendor:
            return self.send(404, {"error": "not_found"})
        key = credential(self.headers, path)
        if not key or not key.startswith(FAKE_PREFIX):
            return self.send(401, {"error": "invalid credential"})
        if path.startswith("/v1/") and not self.headers.get("anthropic-version"):
            return self.send(400, {"error": "anthropic-version header is required"})
        with lock:
            if path == "/v1/organizations/users" and method == "GET":
                rows = sorted(state["claude_users"], key=lambda u: u["added_at"], reverse=True)
                result = page_by_id(rows, query)
                return self.send(400, {"error": "invalid page"}) if result is None else self.send(200, result)
            if path == "/v1/organizations/invites" and method == "GET":
                rows = sorted(state["claude_invites"], key=lambda i: i["invited_at"], reverse=True)
                result = page_by_id(rows, query)
                return self.send(400, {"error": "invalid page"}) if result is None else self.send(200, result)
            if path.startswith("/v1/organizations/users/") and method == "DELETE":
                user_id = path.rsplit("/", 1)[1]
                found = next((u for u in state["claude_users"] if u["id"] == user_id), None)
                if found is None:
                    return self.send(404, {"error": "not_found"})
                if found["role"] in CLAUDE_ADMIN_ROLES:
                    return self.send(400, {"error": "administrative role"})
                state["claude_users"].remove(found)
                return self.send(200, {"type": "user_deleted", "id": user_id})
            if path == "/v1/organizations/analytics/cost_report" and method == "GET":
                starting = (query.get("starting_at") or [None])[0]
                if not starting:
                    return self.send(400, {"error": "starting_at is required"})
                ending = (query.get("ending_at") or [starting])[0]
                last = (query.get("page") or [None])[0] == "cost_page_2"
                amount = CLAUDE_COST_PAGES[1 if last else 0]
                return self.send(200, {
                    "data": [{"starting_at": starting, "ending_at": ending, "results": [{
                        "amount": amount, "cost_type": "code_execution", "currency": "USD", "list_amount": amount,
                        "model": "claude-opus-5", "product": "chat", "requests": 0}]}],
                    "data_refreshed_at": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
                    "has_more": not last, "next_page": None if last else "cost_page_2", "organization_id": "org_mock_seed_e"})
            if path == "/teams/members" and method == "GET":
                return self.send(200, {"teamMembers": [{k: m[k] for k in ("id", "name", "email", "role", "isRemoved")} for m in state["cursor_members"]]})
            if path == "/teams/remove-member" and method == "POST":
                request = body if isinstance(body, dict) else {}
                keys = [k for k in ("userId", "email") if request.get(k)]
                if len(keys) != 1:
                    return self.send(200, {"success": False})
                field = keys[0]
                found = next((m for m in state["cursor_members"] if (m["id"] == request[field] if field == "userId" else m["email"].lower() == str(request[field]).lower())), None)
                owners = [m for m in state["cursor_members"] if m["role"] == "owner" and not m["isRemoved"]]
                if found is None or found["isRemoved"] or (found["role"] == "owner" and len(owners) <= 1):
                    return self.send(200, {"success": False})
                found["isRemoved"] = True
                return self.send(200, {"success": True, "userId": found["id"], "hasBillingCycleUsage": found["spendCents"] > 0})
            if path == "/teams/spend" and method == "POST":
                request = body if isinstance(body, dict) else {}
                page, size = request.get("page", 1), request.get("pageSize", 100)
                if not isinstance(page, int) or not isinstance(size, int) or page < 1 or size < 1:
                    return self.send(400, {"error": "page·pageSize"})
                # 이번 주기의 지출이다 — 주기 중에 제거한 구성원의 지출도 남는다.
                rows = state["cursor_members"]
                pages = max(1, -(-len(rows) // size))
                chunk = rows[(page - 1) * size: page * size]
                return self.send(200, {
                    "teamMemberSpend": [{"userId": m["id"], "spendCents": m["spendCents"], "overallSpendCents": m["spendCents"], "fastPremiumRequests": 1250,
                                         "name": m["name"], "email": m["email"], "role": m["role"], "hardLimitOverrideDollars": 100,
                                         "monthlyLimitDollars": 200, "effectivePerUserLimitDollars": 100} for m in chunk],
                    "subscriptionCycleStart": cycle_start_ms(), "totalMembers": len(rows), "totalPages": pages})
        return self.send(404, {"error": "not_found"})
    def do_GET(self): self.handle_any("GET")
    def do_POST(self): self.handle_any("POST")
    def do_DELETE(self): self.handle_any("DELETE")
    def do_PUT(self): self.handle_any("PUT")

if __name__ == "__main__":
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        sys.exit("사용법: python3 tools/mock-vendor/mock_vendor.py <포트>")
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
