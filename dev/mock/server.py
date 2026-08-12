"""
轻量级 E2E Mock Server
基于 Python 标准库（无需第三方依赖）
用于本地 E2E 测试，替代 Docker Prism

启动：python dev/mock/server.py
默认端口：4010
"""
import json
import re
import sys
import uuid
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


# 内存数据
USERS = {
    "SA0001": {
        "user_id": 1,
        "employee_no": "SA0001",
        "real_name": "系统管理员",
        "dept_id": 4,
        "dept_name": "安全部",
        "roles": ["super_admin", "security_admin"],
        "permissions": ["*"],
        "last_login_time": datetime.now(timezone.utc).isoformat(),
    }
}
SESSIONS = {}  # refresh_token -> user_id
VIDEOS = [
    {
        "id": 1,
        "title": "员工安全培训 2026 Q3",
        "description": "全员安全意识培训",
        "file_hash": "a" * 64,
        "duration_sec": 1830,
        "size_bytes": 524288000,
        "mime_type": "video/mp4",
        "classification_lv": "L2",
        "uploader_id": 1,
        "upload_time": "2026-08-10T10:00:00.000Z",
        "hls_status": "ready",
        "fingerprint_status": "ready",
    },
    {
        "id": 2,
        "title": "核心架构设计评审",
        "description": "L4 绝密",
        "file_hash": "b" * 64,
        "duration_sec": 3600,
        "size_bytes": 1073741824,
        "mime_type": "video/mp4",
        "classification_lv": "L4",
        "uploader_id": 1,
        "upload_time": "2026-08-11T14:30:00.000Z",
        "hls_status": "ready",
        "fingerprint_status": "ready",
    },
]
DOCUMENTS = []
APPROVALS = []
LOGS = []


def now_iso():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def pagination_response(items, page, page_size):
    total = len(items)
    start = (page - 1) * page_size
    end = start + page_size
    return {
        "items": items[start:end],
        "pagination": {
            "page": page,
            "page_size": page_size,
            "total": total,
            "total_pages": (total + page_size - 1) // page_size,
        },
    }


class MockHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # 简化日志
        print(f"[{datetime.now().strftime('%H:%M:%S')}] {self.command} {self.path} {args[1]}")

    def _send_json(self, status, body):
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("X-Trace-Id", uuid.uuid4().hex)
        self.end_headers()
        self.wfile.write(payload)

    def _send_error(self, status, title, detail, code=""):
        body = {
            "type": "about:blank",
            "title": title,
            "status": status,
            "detail": detail,
            "code": code,
            "trace_id": uuid.uuid4().hex,
            "timestamp": now_iso(),
        }
        self._send_json(status, body)

    def _read_body(self):
        length = int(self.headers.get("Content-Length", 0))
        if length > 0:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        return {}

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/health/live":
            return self._send_json(200, {"status": "alive", "uptime_sec": 100, "pid": 1})
        if path == "/health/ready":
            return self._send_json(200, {"status": "ok", "version": "3.1.0"})
        if path == "/health/components":
            return self._send_json(200, {
                "overall": "ok",
                "components": {
                    "mysql": {"status": "ok", "latency_ms": 1},
                    "redis": {"status": "ok", "latency_ms": 1},
                }
            })
        if path == "/auth/me":
            return self._handle_auth_me()
        if path == "/videos":
            return self._handle_videos_list()
        if m := re.match(r"^/videos/(\d+)$", path):
            return self._handle_video_get(int(m.group(1)))
        if path == "/documents":
            return self._handle_documents_list()
        if m := re.match(r"^/documents/(\d+)$", path):
            return self._handle_document_get(int(m.group(1)))
        if path == "/distribution/approvals":
            return self._handle_approvals_list()
        if path == "/users":
            return self._handle_users_list()
        if path == "/audit/logs":
            return self._handle_audit_list()
        if path == "/roles":
            return self._send_json(200, [{"id": 1, "code": "super_admin", "name": "超级管理员"}])
        if path == "/permissions":
            return self._send_json(200, [{"id": 1, "code": "video:read", "name": "查看视频"}])
        if path == "/notifications":
            return self._send_json(200, {"items": [], "pagination": {"page": 1, "page_size": 20, "total": 0, "total_pages": 0}, "unread_count": 0})
        if path == "/webhooks":
            return self._send_json(200, [])
        if path == "/tags":
            return self._send_json(200, [])
        return self._send_error(404, "Not Found", f"路径不存在: {path}", "RES_404")

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/auth/login":
            return self._handle_login()
        if path == "/auth/refresh":
            return self._handle_refresh()
        if path == "/auth/logout":
            return self._send_json(204, None)
        if path == "/videos":
            return self._handle_video_upload()
        if m := re.match(r"^/playback/(\d+)/token$", path):
            return self._handle_playback_token(int(m.group(1)))
        if m := re.match(r"^/playback/(\d+)/key$", path):
            return self._handle_playback_key(int(m.group(1)))
        if m := re.match(r"^/playback/(\d+)/log$", path):
            return self._send_json(204, None)
        if path == "/documents":
            return self._handle_document_upload()
        if path == "/distribution/approvals":
            return self._handle_approval_create()
        if m := re.match(r"^/distribution/approvals/(\d+)/decide$", path):
            return self._handle_approval_decide(int(m.group(1)))
        if m := re.match(r"^/distribution/approvals/(\d+)/revoke$", path):
            return self._send_json(204, None)
        if path == "/watermarks/extract":
            return self._handle_watermark_extract()
        if path == "/users":
            return self._handle_user_create()
        if m := re.match(r"^/users/(\d+)/revoke-keys$", path):
            return self._send_json(204, None)
        if path == "/audit/logs/export":
            return self._send_json(202, {"task_id": uuid.uuid4().hex, "download_url": f"/exports/{uuid.uuid4().hex}"})
        if path == "/admin/backups":
            return self._send_json(202, {"backup_id": uuid.uuid4().hex, "task_id": uuid.uuid4().hex})
        if path in ("/videos/batch-delete", "/documents/batch-delete"):
            return self._send_json(202, {"task_id": uuid.uuid4().hex, "affected_count": 0})
        return self._send_error(404, "Not Found", f"路径不存在: {path}", "RES_404")

    def do_PUT(self):
        path = urlparse(self.path).path
        if m := re.match(r"^/users/(\d+)$", path):
            return self._handle_user_update(int(m.group(1)))
        if path == "/notifications/preferences":
            return self._send_json(200, {"ok": True})
        if path == "/notifications/read-all":
            return self._send_json(200, {"updated_count": 0})
        return self._send_error(404, "Not Found", f"路径不存在: {path}", "RES_404")

    def do_DELETE(self):
        path = urlparse(self.path).path
        if m := re.match(r"^/videos/(\d+)$", path):
            return self._send_json(204, None)
        if m := re.match(r"^/documents/(\d+)$", path):
            return self._send_json(204, None)
        if m := re.match(r"^/users/(\d+)$", path):
            return self._send_json(204, None)
        if m := re.match(r"^/watermarks/cache/(\d+)$", path):
            return self._send_json(204, None)
        return self._send_error(404, "Not Found", f"路径不存在: {path}", "RES_404")

    def _handle_login(self):
        body = self._read_body()
        employee_no = body.get("employee_no", "")
        password = body.get("password", "")

        if not employee_no or not password:
            return self._send_error(400, "Bad Request", "工号和密码不能为空", "VAL_400")
        if employee_no not in USERS:
            return self._send_error(401, "Unauthorized", "工号或密码错误", "AUTH_401")
        if password != "admin123":
            return self._send_error(401, "Unauthorized", "工号或密码错误", "AUTH_401")

        user = USERS[employee_no]
        access_token = f"access_{uuid.uuid4().hex}"
        refresh_token = f"refresh_{uuid.uuid4().hex}"
        SESSIONS[refresh_token] = user["user_id"]
        user["last_login_time"] = now_iso()

        return self._send_json(200, {
            "access_token": access_token,
            "refresh_token": refresh_token,
            "token_type": "Bearer",
            "expires_in": 600,
        })

    def _handle_refresh(self):
        body = self._read_body()
        rt = body.get("refresh_token", "")
        if rt not in SESSIONS:
            return self._send_error(401, "Unauthorized", "refresh_token 无效", "AUTH_401")
        return self._send_json(200, {
            "access_token": f"access_{uuid.uuid4().hex}",
            "token_type": "Bearer",
            "expires_in": 600,
        })

    def _handle_auth_me(self):
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return self._send_error(401, "Unauthorized", "未携带 token", "AUTH_401")
        user = USERS["SA0001"]
        return self._send_json(200, user)

    def _handle_videos_list(self):
        page = int(self.params.get("page", 1)) if hasattr(self, "params") else 1
        # 简化解析
        from urllib.parse import parse_qs
        q = parse_qs(urlparse(self.path).query)
        page = int(q.get("page", [1])[0])
        page_size = int(q.get("page_size", [20])[0])
        return self._send_json(200, pagination_response(VIDEOS, page, page_size))

    def _handle_video_get(self, video_id):
        v = next((v for v in VIDEOS if v["id"] == video_id), None)
        if not v:
            return self._send_error(404, "Not Found", f"视频不存在: {video_id}", "RES_404")
        return self._send_json(200, v)

    def _handle_video_upload(self):
        return self._send_json(202, {
            "video_id": 999,
            "file_hash": uuid.uuid4().hex,
            "hls_status": "pending",
            "fingerprint_status": "pending",
            "estimated_processing_sec": 60,
        })

    def _handle_playback_token(self, video_id):
        return self._send_json(200, {
            "session_id": uuid.uuid4().hex,
            "m3u8_url": f"/api/v1/video/{video_id}/playlist.m3u8?token=xxx",
            "token": uuid.uuid4().hex,
            "expires_at": (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat(),
            "key_url": f"/api/v1/playback/{video_id}/key",
            "watermark_template": "{employee_no}|{timestamp}",
        })

    def _handle_playback_key(self, video_id):
        body = b"\x00" * 16
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", "16")
        self.end_headers()
        self.wfile.write(body)

    def _handle_documents_list(self):
        from urllib.parse import parse_qs
        q = parse_qs(urlparse(self.path).query)
        page = int(q.get("page", [1])[0])
        page_size = int(q.get("page_size", [20])[0])
        return self._send_json(200, pagination_response(DOCUMENTS, page, page_size))

    def _handle_document_get(self, doc_id):
        return self._send_error(404, "Not Found", f"文档不存在: {doc_id}", "RES_404")

    def _handle_document_upload(self):
        return self._send_json(202, {
            "doc_id": 999,
            "file_hash": uuid.uuid4().hex,
            "watermark_status": "pending",
            "preview_status": "pending",
        })

    def _handle_approvals_list(self):
        from urllib.parse import parse_qs
        q = parse_qs(urlparse(self.path).query)
        page = int(q.get("page", [1])[0])
        page_size = int(q.get("page_size", [20])[0])
        return self._send_json(200, pagination_response(APPROVALS, page, page_size))

    def _handle_approval_create(self):
        body = self._read_body()
        approval = {
            "id": len(APPROVALS) + 1,
            "doc_id": body.get("doc_id"),
            "applicant_id": 1,
            "external_recipient": body.get("external_recipient", {}),
            "reason": body.get("reason", ""),
            "valid_hours": body.get("valid_hours", 24),
            "max_open_count": body.get("max_open_count", 5),
            "allow_forward": body.get("allow_forward", False),
            "allow_print": body.get("allow_print", False),
            "status": "pending",
            "current_open_count": 0,
            "created_at": now_iso(),
        }
        APPROVALS.append(approval)
        return self._send_json(201, approval)

    def _handle_approval_decide(self, approval_id):
        return self._send_json(200, {"status": "approved"})

    def _handle_watermark_extract(self):
        return self._send_json(200, {
            "type": "image",
            "extracted": {
                "employee_no": "",
                "extract_time": now_iso(),
                "fingerprint": "",
                "confidence": 0.0,
            },
            "matched_users": [],
        })

    def _handle_users_list(self):
        from urllib.parse import parse_qs
        q = parse_qs(urlparse(self.path).query)
        page = int(q.get("page", [1])[0])
        page_size = int(q.get("page_size", [20])[0])
        users = [{k: v for k, v in u.items() if k != "password_hash"} for u in USERS.values()]
        return self._send_json(200, pagination_response(users, page, page_size))

    def _handle_user_create(self):
        body = self._read_body()
        new_id = max(USERS[u]["user_id"] for u in USERS) + 1
        user = {
            "user_id": new_id,
            "username": body.get("username", ""),
            "employee_no": body.get("employee_no", ""),
            "real_name": body.get("real_name", ""),
            "email": body.get("email", ""),
            "dept_id": body.get("dept_id", 1),
            "status": "active",
            "roles": [],
            "permissions": [],
        }
        return self._send_json(201, user)

    def _handle_user_update(self, user_id):
        return self._send_json(200, USERS["SA0001"])

    def _handle_audit_list(self):
        from urllib.parse import parse_qs
        q = parse_qs(urlparse(self.path).query)
        page = int(q.get("page", [1])[0])
        page_size = int(q.get("page_size", [20])[0])
        return self._send_json(200, pagination_response(LOGS, page, page_size))


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 4010
    server = ThreadingHTTPServer(("0.0.0.0", port), MockHandler)
    print(f"Mock Server listening on http://0.0.0.0:{port}")
    print(f"OpenAPI spec: doc/openapi.yaml (51 endpoints, mock implements ~30)")
    print(f"Default credentials: SA0001 / admin123")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.shutdown()
        print("\nMock Server stopped")


if __name__ == "__main__":
    main()