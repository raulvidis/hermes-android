# Deploying the relay behind HTTPS WebSocket (wss)

Production-friendly pattern for internet phones:

```text
Phone APK  --wss-->  reverse proxy :443/:8443 (TLS)  --ws-->  relay 127.0.0.1:8766
```

## Why

- Binding the relay on a public interface **without TLS** sends pairing tokens and device traffic in cleartext.
- Non-standard ports are often filtered on mobile networks.
- Reverse proxy on a normal HTTPS port + Let’s Encrypt (or your CA) is the usual fix.

## Environment

```bash
# Relay listens only on loopback
export ANDROID_RELAY_HOST=127.0.0.1
export ANDROID_RELAY_PORT=8766

# What the phone should type into "Server" (no secrets)
export ANDROID_PUBLIC_URL=https://bridge.example.com:8443

# Local tool base URL (agent side)
export ANDROID_BRIDGE_URL=http://127.0.0.1:8766
```

## nginx sketch

```nginx
server {
    listen 8443 ssl http2;
    server_name bridge.example.com;

    ssl_certificate     /etc/letsencrypt/live/bridge.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/bridge.example.com/privkey.pem;

    location /ws {
        proxy_pass http://127.0.0.1:8766;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

Exact path (`/ws` vs `/`) must match what the APK dials; inspect `RelayClient` if unsure.

## Phone UI

Server field must include scheme **and** port if non-443:

```text
https://bridge.example.com:8443
```

Missing port causes some clients to fall back to a public raw port and reconnect forever.

## Pairing

1. Start Hermes with the plugin enabled; call setup with the 6-character code from the app.
2. Codes are **case-sensitive**.
3. Failed auth is rate-limited (see env knobs). Wrong code spam → temporary 429.

## Firewall

- Allow the public TLS port only.
- Do **not** expose `8766` publicly when the proxy works.

## Cloudflare / regional blocks

If a CDN or tunnel product is blocked in your users’ region, terminate TLS on your own VPS instead.
