#!/usr/bin/env python3
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request


def request(base, method, path, body=None, token=None, expected=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=3) as response:
            raw = response.read()
            result = json.loads(raw) if raw else {}
            if expected is not None:
                assert response.status == expected, (response.status, result)
            return response.status, result
    except urllib.error.HTTPError as error:
        raw = error.read()
        result = json.loads(raw) if raw else {}
        if expected is not None:
            assert error.code == expected, (error.code, result)
        return error.code, result


def start(port, ttl=300):
    base = f"http://127.0.0.1:{port}"
    server_dir = os.path.dirname(os.path.abspath(__file__))
    process = subprocess.Popen(
        [sys.executable, "server.py", "--host", "127.0.0.1", "--port", str(port), "--db", ":memory:", "--pairing-ttl-seconds", str(ttl)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        cwd=server_dir,
    )
    deadline = time.time() + 5
    while time.time() < deadline:
        try:
            request(base, "POST", "/sync-spaces", {"name": "probe"}, expected=201)
            return process, base
        except urllib.error.URLError:
            time.sleep(0.05)
    process.terminate()
    raise AssertionError("server did not start")


def stop(process):
    process.terminate()
    process.wait(timeout=3)


def event(event_id, entity="DOWNLOAD"):
    return {"eventId": event_id, "entity": entity, "entityId": "history-x", "operation": "UPSERT", "timestamp": 10, "payload": {"state": "AVAILABLE_REMOTE"}}


def main():
    base_port = 19000 + (os.getpid() % 900)
    process, base = start(base_port)
    try:
        # Two independent users/spaces. Each creator receives its own device token.
        _, a = request(base, "POST", "/sync-spaces", {"name": "User A", "deviceId": "a1", "deviceName": "A1"}, expected=201)
        _, b = request(base, "POST", "/sync-spaces", {"name": "User B", "deviceId": "b1", "deviceName": "B1"}, expected=201)
        space_a, token_a1, pairing_a = a["spaceId"], a["deviceToken"], a["pairingCode"]
        space_b, token_b1, pairing_b = b["spaceId"], b["deviceToken"], b["pairingCode"]

        # Explicit single-use pairing registers A2 and B2.
        _, a2 = request(base, "POST", f"/sync-spaces/{space_a}/join", {"pairingCode": pairing_a, "deviceId": "a2"}, expected=201)
        token_a2 = a2["deviceToken"]
        request(base, "POST", f"/sync-spaces/{space_a}/join", {"pairingCode": pairing_a, "deviceId": "a3"}, expected=400)
        _, b2 = request(base, "POST", f"/sync-spaces/{space_b}/join", {"pairingCode": pairing_b, "deviceId": "b2"}, expected=201)

        # Creator issues a new pairing for a further authorized member.
        _, new_pairing = request(base, "POST", f"/sync-spaces/{space_a}/pairing", token=token_a1, expected=201)
        request(base, "POST", f"/sync-spaces/{space_a}/join", {"pairingCode": new_pairing["pairingCode"], "deviceId": "a3"}, expected=201)

        # Real HTTP event isolation: A sees A, B never sees A; inverse also holds.
        request(base, "POST", "/sync/events", {"spaceId": space_a, "event": event("a-event")}, token=token_a1, expected=200)
        _, a_events = request(base, "GET", f"/sync/events?spaceId={space_a}&afterRevision=0", token=token_a2, expected=200)
        assert [item["eventId"] for item in a_events["events"]] == ["a-event"]
        request(base, "GET", f"/sync/events?spaceId={space_a}", token=token_b1, expected=403)
        request(base, "POST", "/sync/events", {"spaceId": space_b, "event": event("b-event", "PROGRESS")}, token=token_b1, expected=200)
        request(base, "GET", f"/sync/events?spaceId={space_b}&afterRevision=0", token=token_a1, expected=403)

        # Idempotency remains scoped and does not allocate a second revision.
        _, duplicate = request(base, "POST", "/sync/events", {"spaceId": space_a, "event": event("a-event")}, token=token_a1, expected=200)
        assert duplicate["revision"] == 1

        # Revocation invalidates only the selected device.
        request(base, "POST", "/devices/revoke", {"spaceId": space_a, "deviceId": "a2"}, token=token_a1, expected=200)
        request(base, "GET", f"/sync/events?spaceId={space_a}&afterRevision=0", token=token_a2, expected=401)
        request(base, "POST", "/devices/heartbeat", {"spaceId": space_a}, token=token_a2, expected=401)
        request(base, "GET", f"/sync/events?spaceId={space_a}&afterRevision=0", token=token_a1, expected=200)

        # Expired pairing is rejected by a real server process.
        expired_process, expired_base = start(base_port + 1, ttl=0)
        try:
            _, expired = request(expired_base, "POST", "/sync-spaces", {"name": "Expired"}, expected=201)
            request(expired_base, "POST", f"/sync-spaces/{expired['spaceId']}/join", {"pairingCode": expired["pairingCode"], "deviceId": "expired"}, expected=400)
        finally:
            stop(expired_process)

        print("SECURITY E2E PASS: isolated spaces, cross-space 403, single-use/expired pairing, revocation, idempotency")
    finally:
        stop(process)


if __name__ == "__main__":
    main()
