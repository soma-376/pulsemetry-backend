"""로컬 모의 벤더 서버 — GitHub Copilot 좌석 API의 요청·응답(docs/vendor-connector-evidence.md §4)을 흉내 낸다.
실제 벤더를 부르지 않는다. 상태는 메모리(기동마다 초기화): 조직 seed-org 의 좌석 seed-dev-7·seed-dev-11·seed-bot(시드 A 의 Copilot 좌석과 같다).
  GET    /orgs/{org}/copilot/billing/seats            좌석 목록(한 페이지)
  DELETE /orgs/{org}/copilot/billing/selected_users   취소 → pending_cancellation_date(주기 말)
  POST   /orgs/{org}/copilot/billing/selected_users   재배정 → 취소 예정 해제
  GET    /__calls                                     받은 요청(메서드·경로·본문·Authorization) — E2E 확인용
  POST   /__reset                                     상태·기록 초기화
그 밖의 경로는 404(벤더 거절)다 — 다른 커넥터의 기준 주소도 여기를 가리켜 실제 벤더로 나가지 않게 한다.
루프백 주소(127.0.0.1)에만 묶는다. 사용법은 같은 디렉터리의 README.md."""
import json, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOGINS = ["seed-dev-7", "seed-dev-11", "seed-bot"]
lock = threading.Lock()
state = {"seats": {login: None for login in LOGINS}, "calls": []}

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"null") if length else None
    def handle_any(self, method):
        path = self.path.split("?")[0]
        body = self.body() if method in ("POST", "DELETE", "PUT") else None
        if path == "/__calls" and method == "GET":
            with lock: return self.send(200, {"calls": state["calls"]})
        if path == "/__reset" and method == "POST":
            with lock:
                state["seats"] = {login: None for login in LOGINS}; state["calls"] = []
            return self.send(200, {})
        with lock: state["calls"].append({"method": method, "path": path, "body": body, "authorization": self.headers.get("Authorization")})
        parts = path.strip("/").split("/")
        if len(parts) == 5 and parts[0] == "orgs" and parts[2:4] == ["copilot", "billing"] and parts[1] == "seed-org":
            if parts[4] == "seats" and method == "GET":
                with lock:
                    seats = [{"assignee": {"login": login, "id": i + 1, "type": "User"}, "pending_cancellation_date": cancel, "last_activity_at": None,
                              "created_at": "2026-08-01T00:00:00Z", "plan_type": "business"} for i, (login, cancel) in enumerate(state["seats"].items())]
                return self.send(200, {"total_seats": len(seats), "seats": seats})
            if parts[4] == "selected_users" and method in ("DELETE", "POST"):
                users = (body or {}).get("selected_usernames") or []
                with lock:
                    known = [user for user in users if user in state["seats"]]
                    for user in known: state["seats"][user] = "2026-10-31" if method == "DELETE" else None
                return self.send(200 if method == "DELETE" else 201, {"seats_cancelled" if method == "DELETE" else "seats_created": len(known)})
        return self.send(404, {"message": "Not Found"})
    def do_GET(self): self.handle_any("GET")
    def do_POST(self): self.handle_any("POST")
    def do_DELETE(self): self.handle_any("DELETE")
    def do_PUT(self): self.handle_any("PUT")

if __name__ == "__main__":
    if len(sys.argv) != 2 or not sys.argv[1].isdigit():
        sys.exit("사용법: python3 tools/mock-vendor/mock_vendor.py <포트>")
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
