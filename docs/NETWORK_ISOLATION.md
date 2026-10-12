# TubeGo ingress isolation

TubeGo API must never join `sjia_tunnel_origin`, `sjia_edge`, or SJIA database networks. SJIA Caddy reserves `172.30.248.2`; dynamically allocated TubeGo endpoints previously occupied that address while Caddy was stopped.

Provision the dedicated external network before deploying TubeGo or the SJIA tunnel connector:

```sh
docker network create --internal tubego_tunnel_origin
```

`compose.mobile.yaml` attaches only the API to this ingress network, with the DNS alias `tubego-api`. The tunnel connector joins it through the `tubego_origin` declaration in SJIA `deploy/compose.edge.yml`. Caddy remains on SJIA networks. TubeGo workers and the media egress service remain on the TubeGo default network. The public route continues to use `http://tubego-api:8000`.

The Cloudflare tunnel process is still shared. Address pools and API container access are separated; this is not complete tunnel/process isolation. Do not attach the API to SJIA networks to restore public connectivity.

## Incident correction, 2026-10-12

When remediation began, Caddy was already healthy and the TubeGo API was stopped. The API was disconnected from the SJIA origin network, connected to the dedicated internal network, and started using its existing image and data bind. The tunnel connector was attached to the new network without restarting SJIA. Both TubeGo deployment copies and the SJIA connector Compose definition were corrected.

Verified: SJIA public page HTTP 200 after its normal redirect; TubeGo public and loopback `/api/v1/health` report `status=ok` and `database=ok`; SJIA origin network contains only Caddy and the tunnel connector.

After merging the correction into the official repository, fast-forward the operator maintenance staging checkout to the corrected main revision and restart its supervisor so it approves the new SHA. The supervisor refuses deployment mutations from a changed checkout. Do not reset staging to the previous SJIA network declaration.
