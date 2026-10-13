# Sync server

```bash
cd kiroha-sync-server
python3 server.py --host 0.0.0.0 --port 8787 --db kiroha-sync.sqlite3
```

Run the isolated E2E suite directly:

```bash
python3 kiroha-sync-server/test_e2e.py
```

For LAN testing, expose port `8787` only on a trusted network or put the server behind an HTTPS reverse proxy. The reference server itself is intentionally small and does not implement Internet-scale TLS infrastructure.
