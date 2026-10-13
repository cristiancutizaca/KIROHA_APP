#!/usr/bin/env python3
"""Kiroha Sync reference server: stdlib-only HTTP + SQLite implementation."""
from __future__ import annotations

import argparse
import hashlib
import json
import secrets
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


def now() -> int:
    return int(time.time() * 1000)


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class Store:
    def __init__(self, path: str, pairing_ttl_seconds: int = 300):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        self.pairing_ttl_ms = max(pairing_ttl_seconds, 0) * 1000
        with self.lock, self.db:
            self.db.executescript(
                """
                CREATE TABLE IF NOT EXISTS spaces(
                    space_id TEXT PRIMARY KEY, name TEXT NOT NULL,
                    created_at INTEGER NOT NULL, revision INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS pairings(
                    pairing_hash TEXT PRIMARY KEY, space_id TEXT NOT NULL,
                    expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS devices(
                    device_id TEXT NOT NULL, space_id TEXT NOT NULL,
                    name TEXT NOT NULL, platform TEXT NOT NULL,
                    app_version TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
                    created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL,
                    revoked INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(space_id, device_id)
                );
                CREATE TABLE IF NOT EXISTS events(
                    space_id TEXT NOT NULL, event_id TEXT NOT NULL,
                    revision INTEGER NOT NULL, entity TEXT NOT NULL,
                    entity_id TEXT NOT NULL, operation TEXT NOT NULL,
                    timestamp INTEGER NOT NULL, device_id TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    PRIMARY KEY(space_id, event_id), UNIQUE(space_id, revision)
                );
                CREATE TABLE IF NOT EXISTS cursors(
                    space_id TEXT NOT NULL, device_id TEXT NOT NULL,
                    revision INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL,
                    PRIMARY KEY(space_id, device_id)
                );
                CREATE INDEX IF NOT EXISTS events_revision ON events(space_id, revision);
                CREATE INDEX IF NOT EXISTS devices_space ON devices(space_id, revoked);
                """
            )

    def _new_pairing(self, space_id: str):
        raw = secrets.token_urlsafe(9)
        with self.db:
            self.db.execute(
                "INSERT INTO pairings VALUES(?,?,?,0)",
                (token_hash(raw), space_id, now() + self.pairing_ttl_ms),
            )
        return raw

    def _register_device(self, space_id: str, device: dict):
        raw = "dev_" + secrets.token_urlsafe(24)
        stamp = now()
        self.db.execute(
            "INSERT OR REPLACE INTO devices VALUES(?,?,?,?,?,?,?,?,0)",
            (device["deviceId"], space_id, device.get("deviceName", device["deviceId"]),
             device.get("platform", "ANDROID"), device.get("appVersion", "unknown"),
             token_hash(raw), stamp, stamp),
        )
        self.db.execute(
            "INSERT OR IGNORE INTO cursors VALUES(?,?,0,?)",
            (space_id, device["deviceId"], stamp),
        )
        return raw

    def create_space(self, name: str, owner: dict | None = None):
        space_id = "space_" + secrets.token_urlsafe(12)
        with self.lock, self.db:
            self.db.execute("INSERT INTO spaces VALUES(?,?,?,0)", (space_id, name, now()))
            owner_token = self._register_device(space_id, owner) if owner and owner.get("deviceId") else None
            pairing = self._new_pairing(space_id)
        return space_id, pairing, owner_token

    def issue_pairing(self, space_id: str, device_token: str):
        self.auth(space_id, device_token)
        with self.lock:
            if not self.db.execute("SELECT 1 FROM spaces WHERE space_id=?", (space_id,)).fetchone():
                raise ValueError("unknown space")
            return self._new_pairing(space_id)

    def join(self, space_id: str, pairing: str, device: dict):
        with self.lock, self.db:
            row = self.db.execute(
                "SELECT pairing_hash, expires_at, used FROM pairings WHERE space_id=? AND pairing_hash=?",
                (space_id, token_hash(pairing)),
            ).fetchone()
            if not row:
                raise ValueError("invalid pairing code")
            if row[2]:
                raise ValueError("pairing code already used")
            if row[1] < now():
                raise ValueError("pairing code expired")
            self.db.execute("UPDATE pairings SET used=1 WHERE pairing_hash=?", (row[0],))
            return self._register_device(space_id, device)

    def auth(self, space_id: str, raw: str | None):
        if not raw:
            raise PermissionError("missing bearer token")
        digest = token_hash(raw)
        with self.lock:
            row = self.db.execute(
                "SELECT * FROM devices WHERE space_id=? AND token_hash=? AND revoked=0",
                (space_id, digest),
            ).fetchone()
            if row:
                return row
            same_space = self.db.execute(
                "SELECT 1 FROM devices WHERE space_id=? AND token_hash=?",
                (space_id, digest),
            ).fetchone()
            if same_space:
                raise PermissionError("invalid device token")
            belongs_elsewhere = self.db.execute("SELECT 1 FROM devices WHERE token_hash=?", (digest,)).fetchone()
            if belongs_elsewhere:
                raise PermissionError("token is not authorized for this space")
            raise PermissionError("invalid device token")

    def revoke(self, space_id: str, requester_id: str, target_id: str):
        with self.lock, self.db:
            target = self.db.execute(
                "SELECT device_id FROM devices WHERE space_id=? AND device_id=?", (space_id, target_id)
            ).fetchone()
            if not target:
                raise ValueError("unknown device in this space")
            self.db.execute(
                "UPDATE devices SET revoked=1 WHERE space_id=? AND device_id=?", (space_id, target_id)
            )

    def append(self, space_id, device_id, event):
        required = ("eventId", "entity", "entityId", "operation", "timestamp", "payload")
        if any(key not in event for key in required):
            raise ValueError("event requires eventId, entity, entityId, operation, timestamp and payload")
        with self.lock, self.db:
            existing = self.db.execute("SELECT * FROM events WHERE space_id=? AND event_id=?", (space_id, event["eventId"])).fetchone()
            if existing:
                return self.event_row(existing)
            rev = self.db.execute("SELECT revision FROM spaces WHERE space_id=?", (space_id,)).fetchone()
            if not rev:
                raise ValueError("unknown space")
            revision = rev[0] + 1
            self.db.execute("UPDATE spaces SET revision=? WHERE space_id=?", (revision, space_id))
            self.db.execute(
                "INSERT INTO events VALUES(?,?,?,?,?,?,?,?,?)",
                (space_id, event["eventId"], revision, event["entity"], event["entityId"], event["operation"],
                 event["timestamp"], device_id, json.dumps(event["payload"], separators=(",", ":"))),
            )
            return self.event_row(self.db.execute("SELECT * FROM events WHERE space_id=? AND event_id=?", (space_id, event["eventId"])).fetchone())

    @staticmethod
    def event_row(row):
        result = dict(row)
        result["payload"] = json.loads(result["payload"])
        result["eventId"] = result.pop("event_id")
        result["originDeviceId"] = result.pop("device_id")
        result["entityId"] = result.pop("entity_id")
        result["revision"] = int(result["revision"])
        return result

    def pull(self, space_id, after, limit):
        with self.lock:
            rows = self.db.execute(
                "SELECT * FROM events WHERE space_id=? AND revision>? ORDER BY revision LIMIT ?",
                (space_id, after, min(max(limit, 1), 1000)),
            ).fetchall()
            return [self.event_row(row) for row in rows]

    def ack(self, space_id, device_id, revision):
        with self.lock, self.db:
            self.db.execute(
                "INSERT INTO cursors VALUES(?,?,?,?) ON CONFLICT(space_id,device_id) DO UPDATE SET revision=max(revision,excluded.revision),updated_at=excluded.updated_at",
                (space_id, device_id, revision, now()),
            )

    def devices(self, space_id):
        with self.lock:
            rows = self.db.execute(
                "SELECT device_id,name,platform,app_version,created_at,last_seen,revoked FROM devices WHERE space_id=? ORDER BY last_seen DESC",
                (space_id,),
            ).fetchall()
            return [{"deviceId": r[0], "deviceName": r[1], "platform": r[2], "appVersion": r[3], "createdAt": r[4], "lastSeen": r[5], "revoked": bool(r[6])} for r in rows]


class Handler(BaseHTTPRequestHandler):
    store: Store
    server_version = "KirohaSync/0.1"
    pairing_attempts: dict[str, tuple[int, int]] = {}
    pairing_attempts_lock = threading.Lock()

    def log_message(self, *_):
        return

    def json_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def send_json(self, status, value):
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def bearer(self):
        value = self.headers.get("Authorization", "")
        return value[7:] if value.startswith("Bearer ") else None

    def allow_pairing_attempt(self):
        key = self.client_address[0]
        current = int(time.time())
        with self.pairing_attempts_lock:
            window, count = self.pairing_attempts.get(key, (current, 0))
            if current - window >= 60:
                window, count = current, 0
            count += 1
            self.pairing_attempts[key] = (window, count)
            return count <= 10

    @staticmethod
    def permission_status(exc: PermissionError):
        return 403 if "not authorized for this space" in str(exc) else 401

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/")
        try:
            body = self.json_body()
            if path == "/sync-spaces":
                space, pairing, owner_token = self.store.create_space(body.get("name", "Kiroha Sync Space"), body)
                result = {"spaceId": space, "pairingCode": pairing}
                if owner_token:
                    result["deviceId"] = body["deviceId"]
                    result["deviceToken"] = owner_token
                self.send_json(201, result)
                return
            parts = path.split("/")
            if len(parts) == 4 and parts[1] == "sync-spaces" and parts[3] == "join":
                if not self.allow_pairing_attempt():
                    self.send_json(429, {"error": "pairing rate limit exceeded"})
                    return
                token = self.store.join(parts[2], body.get("pairingCode", ""), body)
                self.send_json(201, {"spaceId": parts[2], "deviceId": body["deviceId"], "deviceToken": token})
                return
            if len(parts) == 4 and parts[1] == "sync-spaces" and parts[3] == "pairing":
                token = self.store.issue_pairing(parts[2], self.bearer())
                self.send_json(201, {"spaceId": parts[2], "pairingCode": token})
                return
            if path == "/sync/events":
                space = body["spaceId"]
                device = self.store.auth(space, self.bearer())
                self.send_json(200, self.store.append(space, device["device_id"], dict(body["event"])))
                return
            if path == "/sync/ack":
                space = body["spaceId"]
                device = self.store.auth(space, self.bearer())
                self.store.ack(space, device["device_id"], int(body["revision"]))
                self.send_json(204, {})
                return
            if path == "/devices/heartbeat":
                space = body["spaceId"]
                device = self.store.auth(space, self.bearer())
                with self.store.lock, self.store.db:
                    self.store.db.execute("UPDATE devices SET last_seen=? WHERE space_id=? AND device_id=?", (now(), space, device["device_id"]))
                self.send_json(200, {"ok": True, "lastSeen": now()})
                return
            if path == "/devices/revoke":
                space = body["spaceId"]
                requester = self.store.auth(space, self.bearer())
                self.store.revoke(space, requester["device_id"], body["deviceId"])
                self.send_json(200, {"revoked": body["deviceId"]})
                return
            self.send_json(404, {"error": "not found"})
        except PermissionError as exc:
            self.send_json(self.permission_status(exc), {"error": str(exc)})
        except (KeyError, ValueError, json.JSONDecodeError) as exc:
            self.send_json(400, {"error": str(exc)})
        except Exception as exc:
            self.send_json(500, {"error": str(exc)})

    def do_GET(self):
        parsed = urlparse(self.path)
        try:
            query = parse_qs(parsed.query)
            if parsed.path == "/sync/events":
                space = query["spaceId"][0]
                self.store.auth(space, self.bearer())
                events = self.store.pull(space, int(query.get("afterRevision", [0])[0]), int(query.get("limit", [500])[0]))
                self.send_json(200, {"events": events})
                return
            if parsed.path == "/devices":
                space = query["spaceId"][0]
                self.store.auth(space, self.bearer())
                self.send_json(200, {"devices": self.store.devices(space)})
                return
            self.send_json(404, {"error": "not found"})
        except (PermissionError, KeyError, ValueError) as exc:
            status = self.permission_status(exc) if isinstance(exc, PermissionError) else 400
            self.send_json(status, {"error": str(exc)})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--db", default="kiroha-sync.sqlite3")
    parser.add_argument("--pairing-ttl-seconds", type=int, default=300)
    args = parser.parse_args()
    Handler.store = Store(args.db, args.pairing_ttl_seconds)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Kiroha Sync Server listening on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
