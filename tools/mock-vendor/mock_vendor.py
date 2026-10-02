"""로컬 모의 벤더 서버 — Cursor Enterprise Admin API 의 좌석 요청·응답(docs/vendor-connector-evidence.md §3)을 흉내 낸다.
실제 벤더를 부르지 않는다. 상태는 메모리(기동마다 초기화): 시드 C 의 Cursor 팀 구성원 넷(owner·member2·member3@seed-c.example.test,
구성원이 없는 외부 계정 contractor@partner.example.test).
  GET    /teams/members        구성원 목록 — 문서 예시의 모양(`teamMembers` 의 id·name·email·role·isRemoved). 제거한 구성원은 isRemoved: true
  POST   /teams/remove-member  본문 userId 또는 email 중 하나 → {"success": true, "userId": …, "hasBillingCycleUsage": false}.
                               둘 다·둘 다 없음·모르는 구성원·이미 제거됨·마지막 owner 는 {"success": false}(벤더 거절)
  GET    /__calls              받은 요청(메서드·경로·본문·Authorization) — E2E 확인용
  POST   /__reset              상태·기록 초기화
그 밖의 경로는 404 다 — 다른 커넥터의 기준 주소도 여기를 가리켜 실제 벤더로 나가지 않게 한다.
루프백 주소(127.0.0.1)에만 묶는다. 사용법은 같은 디렉터리의 README.md."""
import json, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MEMBERS = [
    ("user_seed_c_owner", "C Owner", "owner@seed-c.example.test", "owner"),
    ("user_seed_c_member2", "C Member 2", "member2@seed-c.example.test", "member"),
    ("user_seed_c_member3", "C Member 3", "member3@seed-c.example.test", "member"),
    ("user_seed_c_contractor", "Contractor", "contractor@partner.example.test", "member"),
]
lock = threading.Lock()

def initial():
    return {"members": [{"id": i, "name": n, "email": e, "role": r, "isRemoved": False} for i, n, e, r in MEMBERS], "calls": []}

state = initial()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"null") if length else None
    def handle_any(self, method):
        global state
        path = self.path.split("?")[0]
        body = self.body() if method in ("POST", "DELETE", "PUT") else None
        if path == "/__calls" and method == "GET":
            with lock: return self.send(200, {"calls": state["calls"]})
        if path == "/__reset" and method == "POST":
            with lock: state = initial()
            return self.send(200, {})
        with lock: state["calls"].append({"method": method, "path": path, "body": body, "authorization": self.headers.get("Authorization")})
        if path == "/teams/members" and method == "GET":
            with lock: return self.send(200, {"teamMembers": [dict(m) for m in state["members"]]})
        if path == "/teams/remove-member" and method == "POST":
            request = body if isinstance(body, dict) else {}
            keys = [k for k in ("userId", "email") if request.get(k)]
            with lock:
                if len(keys) != 1:
                    return self.send(200, {"success": False})
                key = keys[0]
                found = next((m for m in state["members"] if (m["id"] == request[key] if key == "userId" else m["email"].lower() == str(request[key]).lower())), None)
                owners = [m for m in state["members"] if m["role"] == "owner" and not m["isRemoved"]]
                if found is None or found["isRemoved"] or (found["role"] == "owner" and len(owners) <= 1):
                    return self.send(200, {"success": False})
                found["isRemoved"] = True
                return self.send(200, {"success": True, "userId": found["id"], "hasBillingCycleUsage": False})
        return self.send(404, {"error": "not_found"})
    def do_GET(self): self.handle_any("GET")
    def do_POST(self): self.handle_any("POST")
    def do_DELETE(self): self.handle_any("DELETE")
    def do_PUT(self): self.handle_any("PUT")

if __name__ == "__main__":
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        sys.exit("사용법: python3 tools/mock-vendor/mock_vendor.py <포트>")
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
