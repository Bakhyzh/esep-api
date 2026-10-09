# Deployment

How esep-api gets from `main` to the server. The full step-by-step server setup is added in the next part.

## Pipeline (`.github/workflows/deploy.yml`)

Push to `main` (or a manual run from the Actions tab):

1. **verify**: the CI job (`ci.yml`, reused): `./mvnw verify`, compose validation, Docker build.
2. **image**: build and push `ghcr.io/bakhyzh/esep-api:latest` and `ghcr.io/bakhyzh/esep-api:<short-sha>`.
3. **deploy** (only when the repository variable `DEPLOY_ENABLED` is `true`, only from `main`,
   environment `production`, one deploy at a time):
   upload `docker-compose.prod.yml` and `Caddyfile` to `~/esep` over SSH, write `IMAGE_TAG=<short-sha>`
   into `~/esep/.env`, `docker compose pull`, `docker compose up -d --wait`, then
   `curl https://$API_DOMAIN/actuator/health` until it answers `UP`.

Pull requests run only `ci.yml`.

## Rollback to a previous image

Every deployed commit has an image tagged with its 7-character sha. The version running now is
`IMAGE_TAG` in `~/esep/.env`.

**Find the previous good sha:** the summary of a successful *Deploy* run in GitHub Actions,
the package page (github.com/Bakhyzh/esep-api/pkgs/container/esep-api), or
`git log --format='%h %s' --abbrev=7 origin/main`.

**Option A: on the server (fastest, about a minute)**

```bash
ssh deploy@<server>
cd ~/esep
grep IMAGE_TAG .env                                # what runs now
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=abc1234/' .env   # the previous good sha
docker compose -f docker-compose.prod.yml --env-file .env pull app
docker compose -f docker-compose.prod.yml --env-file .env up -d --wait app
curl -s https://<api-domain>/actuator/health
```

If the GHCR package is private, run `docker login ghcr.io` first (a personal token with `read:packages`).

**Option B: through git (keeps `main` equal to production)**

```bash
git revert <bad-commit> && git push origin main   # the pipeline tests and deploys the reverted code
```

After option A, the next push to `main` deploys the new commit again, so fix or revert in git as well.

**Database caveat.** Flyway migrations only go forward. Rolling the app back does not roll the schema back,
so a migration must stay compatible with the previous app version (expand/contract: add a column first,
start using it in the next release, drop the old one later). Never edit an applied migration.
