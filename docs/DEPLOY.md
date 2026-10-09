# Deployment

```
browser ──HTTPS──> GitHub Pages            https://bakhyzh.github.io/esep-web/   (esep-web, static)
   │
   └────HTTPS──> VPS :443  Caddy (Let's Encrypt) ──> app:8081 ──> postgres, redis, kafka
                                                   (internal Docker network, no published ports)
```

- **Frontend** (esep-web): push to `main` → GitHub Actions builds with `VITE_API_URL` → GitHub Pages.
- **Backend** (esep-api): push to `main` → `./mvnw verify` → image `ghcr.io/bakhyzh/esep-api:<sha>` →
  SSH to the VPS → `docker compose pull && up -d --wait` → HTTPS health check.

Placeholders used below: `203.0.113.10` = server IP, `api.example.com` = API domain,
`bakhyzh.github.io` = Pages origin. Replace them with your values. **Never commit real values.**

Contents:
1. [Manual setup: server, domain, firewall](#1-manual-setup-server-domain-firewall)
2. [GitHub Secrets and Variables](#2-github-secrets-and-variables)
3. [First launch and checks](#3-first-launch-and-checks)
4. [Logs, operations and rollback](#4-logs-operations-and-rollback)
5. [Known limitations and next steps](#5-known-limitations-and-next-steps)

---

## 1. Manual setup: server, domain, firewall

### 1.1 Buy / prepare

- [ ] **VPS**: 2 GB RAM, 1–2 vCPU, 20+ GB SSD, **Ubuntu 24.04 LTS**, public IPv4.
      The stack uses about 1.6 GB under its memory limits (app 640 MB, kafka 512 MB, postgres 320 MB,
      caddy 128 MB, redis 64 MB); a swap file (below) covers peaks.
- [ ] **Domain or subdomain** for the API, e.g. `api.example.com` (any registrar, or a free subdomain
      of a domain you already own).

### 1.2 DNS

- [ ] Create an **A record**: `api.example.com → 203.0.113.10` (TTL 300 is fine).
- [ ] Do **not** create an AAAA record unless the server really serves IPv6: Let's Encrypt prefers IPv6
      and the certificate request fails if that address does not reach Caddy.
- [ ] Check before the first deploy: `dig +short api.example.com` prints the server IP.

### 1.3 Base system (as root, through the provider's console or the initial SSH access)

```bash
apt update && apt -y upgrade
timedatectl set-timezone UTC

# swap: 2 GB, so a memory peak slows the server down instead of killing a container
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
sysctl -w vm.swappiness=10 && echo 'vm.swappiness=10' > /etc/sysctl.d/99-swappiness.conf

# automatic security updates
apt -y install unattended-upgrades && dpkg-reconfigure -f noninteractive unattended-upgrades
```

### 1.4 Docker

Official repository (https://docs.docker.com/engine/install/ubuntu/):

```bash
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
apt update && apt -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# container logs: rotate instead of filling the disk
cat > /etc/docker/daemon.json <<'EOF'
{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "3" } }
EOF
systemctl restart docker
docker compose version
```

### 1.5 User `deploy` and a deploy-only SSH key

- [ ] **On your own computer** create a key used only by GitHub Actions (no passphrase, it is stored as a secret):

  ```bash
  ssh-keygen -t ed25519 -C "github-actions-esep-deploy" -f ~/.ssh/esep_deploy -N ""
  ```

- [ ] **On the server** (as root):

  ```bash
  adduser --disabled-password --gecos "" deploy
  usermod -aG docker deploy          # note: the docker group is root-equivalent; this user is only for deploys
  install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
  # paste the content of ~/.ssh/esep_deploy.pub (the PUBLIC key) into:
  nano /home/deploy/.ssh/authorized_keys
  chown deploy:deploy /home/deploy/.ssh/authorized_keys && chmod 600 /home/deploy/.ssh/authorized_keys
  ```

  Optional hardening: prefix the key line in `authorized_keys` with
  `no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty ` (the workflow does not need a terminal).

- [ ] Your own admin access: a **separate** personal key for a sudo user (do not reuse the deploy key).
- [ ] Turn off password and root logins in `/etc/ssh/sshd_config` (keep your current session open while testing!):

  ```
  PasswordAuthentication no
  PermitRootLogin no
  ```

  then `systemctl restart ssh` and check from a new terminal: `ssh -i ~/.ssh/esep_deploy deploy@203.0.113.10 docker ps`.

### 1.6 Firewall: only 22, 80, 443

```bash
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp        # or your custom SSH port (then also set the SSH_PORT secret)
ufw allow 80/tcp        # Let's Encrypt HTTP challenge + redirect to HTTPS
ufw allow 443/tcp
ufw allow 443/udp       # HTTP/3 (optional)
ufw enable && ufw status verbose
```

Also allow only these ports in the provider's cloud firewall, if it has one.

> **Pitfall:** ports *published* by Docker (`ports:` in compose) bypass ufw. That is why
> `docker-compose.prod.yml` publishes only Caddy's 80/443: postgres, redis, kafka and the app have no
> `ports:` at all and are reachable only inside the compose network.

### 1.7 The server's `.env` (secrets live only here)

```bash
sudo -iu deploy
mkdir -p ~/esep && cd ~/esep
nano .env            # paste .env.example from the repository and fill it in (values below)
chmod 600 .env
```

| Variable | Example | How to get it |
|---|---|---|
| `DOMAIN` | `api.example.com` | your DNS name from 1.2 |
| `CORS_ALLOWED_ORIGINS` | `https://bakhyzh.github.io` | Pages origin: lowercase, no `/esep-web`, no trailing slash |
| `DB_URL` | `jdbc:postgresql://postgres:5432/esep` | keep as is |
| `DB_USER` | `esep` | keep or change **before** the first start |
| `DB_PASSWORD` | *(generated)* | `openssl rand -base64 24` |
| `JWT_SECRET` | *(generated)* | `openssl rand -base64 48` (at least 32 bytes; changing it logs everyone out) |
| `REDIS_HOST` | `redis` | keep as is |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | keep as is |
| `DEMO_USER_ENABLED` | `true` | `false` = no demo user |
| `DEMO_USER_EMAIL` | `demo@example.com` | any email-shaped login |
| `DEMO_USER_PASSWORD` | *(generated)* | `openssl rand -base64 12` (at least 8 characters) |
| `IMAGE_TAG` | `latest` | written by the workflow on every deploy |

The Postgres user and password are applied **only on the first start** (empty volume). Changing
`DB_PASSWORD` later needs `ALTER USER` inside the database as well.

---

## 2. GitHub Secrets and Variables

Secrets are encrypted and masked in logs; variables are plain configuration. Path in GitHub:
**Settings → Secrets and variables → Actions**.

### 2.1 esep-api

**Environment** `production` (Settings → Environments → New environment):

- [ ] Deployment branches and tags: **Selected branches → `main`**.
- [ ] Optional: Required reviewers (yourself) to approve each deploy manually.

**Secrets** (repository secrets, or environment secrets of `production`, which are stricter):

| Name | Example (not real) | Notes |
|---|---|---|
| `SSH_HOST` | `203.0.113.10` | server IP or hostname |
| `SSH_USER` | `deploy` | the user from 1.5 |
| `SSH_KEY` | `-----BEGIN OPENSSH PRIVATE KEY-----` … | full content of `~/.ssh/esep_deploy` (the **private** key, with BEGIN/END lines) |
| `SSH_PORT` | `22` | optional; only if SSH is not on 22 |
| `SSH_KNOWN_HOSTS` | `203.0.113.10 ssh-ed25519 AAAAC3Nza…` | output of `ssh-keyscan -t ed25519 203.0.113.10` (add `-p <port>` for a custom port) |

Verify `SSH_KNOWN_HOSTS` before saving it: the fingerprint of `ssh-keyscan … | ssh-keygen -lf -` on your
computer must equal `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` run on the server (provider console).
This pins the server's identity, so the workflow never sends secrets to an impostor.

**Variables**:

| Name | Example | Notes |
|---|---|---|
| `DEPLOY_ENABLED` | `true` | until it is `true` the deploy job is skipped (tests and images still run) |
| `API_DOMAIN` | `api.example.com` | used for the HTTPS health check and the environment URL |

No registry secret is needed: the workflow pushes to GHCR with the built-in `GITHUB_TOKEN` and the server
pulls with the deploy job's short-lived token.

**GHCR package visibility** (after the first image push: github.com/Bakhyzh → Packages → esep-api →
Package settings): either **Public** (simplest, the image contains no secrets; manual rollbacks need no login)
or keep it Private (deploys still work; a manual pull on the server needs `docker login ghcr.io`
with a personal token that has only `read:packages`).

### 2.2 esep-web

| Kind | Name | Example | Notes |
|---|---|---|---|
| Variable | `VITE_API_URL` | `https://api.example.com` | no trailing slash; built into the public JS bundle, so **not a secret** |

- [ ] Settings → Pages → Build and deployment → Source: **GitHub Actions**.
- [ ] The `github-pages` environment is created automatically on the first deploy.

No secrets at all in esep-web: anything in a frontend bundle is readable by every visitor.

---

## 3. First launch and checks

Order matters: the server and its `.env` first, then the switch that lets GitHub Actions deploy.

1. [ ] Server ready (section 1): DNS resolves, Docker works for `deploy`, firewall on, `~/esep/.env` filled in.
2. [ ] Secrets and variables of esep-api set (2.1), **`DEPLOY_ENABLED` still unset**.
3. [ ] Merge esep-api PRs in order: `feature/prod-deploy` → `feature/ci-cd` → `feature/deploy-docs`.
       The Deploy run on `main` tests the code and pushes the image; the deploy job is skipped.
4. [ ] Set the variable `DEPLOY_ENABLED=true`, then Actions → **Deploy** → **Run workflow** (branch `main`).
       The run uploads `docker-compose.prod.yml` and `Caddyfile`, pulls, starts and waits for health.
       The first start takes longer: images download, Flyway creates the schema, Caddy gets the certificate.
5. [ ] Check the API from your computer:

   ```bash
   curl -s https://api.example.com/actuator/health
   # {"groups":["liveness","readiness"],"status":"UP"}

   curl -sI http://api.example.com/actuator/health | head -1     # 308 redirect to HTTPS
   curl -s -o /dev/null -w '%{http_code}\n' https://api.example.com/swagger-ui.html   # 404 in prod
   curl -s -o /dev/null -w '%{http_code}\n' https://api.example.com/actuator/env      # 404

   # demo user (credentials from the server's .env)
   TOKEN=$(curl -s https://api.example.com/api/auth/login -H 'Content-Type: application/json' \
     -d '{"email":"demo@example.com","password":"<DEMO_USER_PASSWORD>"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
   curl -s https://api.example.com/api/accounts -H "Authorization: Bearer $TOKEN"
   # one KZT account with balance 100000

   # CORS for the Pages origin
   curl -s -o /dev/null -D - -X OPTIONS https://api.example.com/api/accounts \
     -H 'Origin: https://bakhyzh.github.io' -H 'Access-Control-Request-Method: GET' | grep -i access-control-allow-origin
   ```

6. [ ] esep-web: set `VITE_API_URL`, enable Pages (2.2), merge `feature/pages-deploy`.
       Open `https://bakhyzh.github.io/esep-web/`, sign in as the demo user, refresh a page (must not 404).
7. [ ] Fill in the **Live demo** links in both READMEs.

If something fails: the failed workflow step prints the last 100 app log lines; see section 4.

---

## 4. Logs, operations and rollback

All commands on the server as `deploy`, in `~/esep`. A shortcut saves typing:

```bash
alias dc='docker compose -f docker-compose.prod.yml --env-file .env'
```

| Task | Command |
|---|---|
| Status and health | `dc ps` |
| App logs (follow) | `dc logs -f --tail 200 app` |
| Caddy / certificate logs | `dc logs --tail 100 caddy` |
| All services, since a time | `dc logs --since 30m` |
| Memory and CPU | `docker stats --no-stream` |
| Version running now | `grep IMAGE_TAG .env` |
| Restart the app only | `dc restart app` |
| Stop / start everything | `dc down` / `dc up -d --wait` (volumes, i.e. data, are kept) |
| psql | `dc exec postgres psql -U esep -d esep` |
| Disk usage | `df -h && docker system df` |

Never run `dc down -v`: `-v` deletes the database and the certificates.

### Rollback to a previous image

Every deployed commit has an image tagged with its 7-character sha. The version running now is
`IMAGE_TAG` in `~/esep/.env`.

**Find the previous good sha:** the summary of a successful *Deploy* run in GitHub Actions,
the package page (github.com/Bakhyzh/esep-api/pkgs/container/esep-api), or
`git log --format='%h %s' --abbrev=7 origin/main`.

**Option A: on the server (fastest, about a minute)**

```bash
cd ~/esep
grep IMAGE_TAG .env                                # what runs now
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=abc1234/' .env   # the previous good sha
docker compose -f docker-compose.prod.yml --env-file .env pull app
docker compose -f docker-compose.prod.yml --env-file .env up -d --wait app
curl -s https://api.example.com/actuator/health
```

If the GHCR package is private, run `docker login ghcr.io` first (a personal token with `read:packages`).
Images of the last 10 days usually are still on the server, then `pull` is not even needed.

**Option B: through git (keeps `main` equal to production)**

```bash
git revert <bad-commit> && git push origin main   # the pipeline tests and deploys the reverted code
```

After option A the next push to `main` deploys the new commit again, so fix or revert in git as well.

**Database caveat.** Flyway migrations only go forward. Rolling the app back does not roll the schema back,
so a migration must stay compatible with the previous app version (expand/contract: add a column first,
start using it in the next release, drop the old one later). Never edit an applied migration.

---

## 5. Known limitations and next steps

This is a portfolio demo on one cheap server. Deliberate simplifications:

| Limitation | Risk | In a real production I would |
|---|---|---|
| One server | any server problem = downtime; every deploy restarts the app (10–30 s of 502 while Spring starts) | 2+ app instances behind a load balancer, rolling or blue-green deploys, or a managed platform (Kubernetes, ECS, Fly.io) |
| One Kafka node, replication factor 1 | a broken disk loses unconsumed events; the broker is a single point of failure | 3 brokers with `min.insync.replicas=2`, or a managed Kafka |
| **No database backups** | a disk failure or a bad command loses all data | daily `pg_dump` (or WAL archiving with pgBackRest / WAL-G) to object storage with retention, and regular restore tests; or managed PostgreSQL with point-in-time recovery |
| Postgres, Redis, Kafka in containers next to the app | they compete for 2 GB RAM | managed database and cache, or separate hosts |
| Secrets in a `.env` file on the server | anyone with server access reads them; rotation is manual | a secret manager (Vault, AWS/GCP Secret Manager, Doppler) with rotation, short-lived DB credentials |
| `deploy` is in the `docker` group | a stolen deploy key = root on the server | pull-based deploys (the server pulls a signed release) or a restricted deploy agent; signed images (cosign) |
| No monitoring or alerting | problems are noticed by users | Prometheus + Grafana (Micrometer is already in Spring Boot Actuator), uptime checks, alerts; centralized logs (Loki/ELK) and tracing (OpenTelemetry) |
| Rate limit only on auth, per IP, fixed window | users behind one NAT share a limit | token bucket per IP and per account, limits on the API gateway / CDN, WAF |
| Images not scanned | known CVEs in base images go unnoticed | Trivy/Grype in CI, Dependabot/Renovate for dependencies and base images |
| Frontend and API on different origins | CORS preflight on every new endpoint | serve both from one domain behind the same proxy |
| JWTs cannot be revoked | a stolen token works until it expires (1 h) | short-lived access tokens + refresh tokens with revocation |
