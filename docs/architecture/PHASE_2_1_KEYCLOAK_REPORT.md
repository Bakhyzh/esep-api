# ESEP Phase 2.1 — Keycloak Infrastructure: completion report

Дата: 2026-11-06, Asia/Almaty (runtime logs используют UTC 2026-11-05).
Ветка: `feat/dba-lab-foundation`. Статус: **LOCAL infrastructure acceptance passed**.
Это не завершение phase 2 authentication migration и не production readiness.

Перед реализацией прочитаны CURRENT_STATE, TARGET_ARCHITECTURE,
MIGRATION_ROADMAP, SERVICE_BOUNDARIES, DATABASE_STRATEGY,
SECURITY_MIGRATION и RISKS из этого каталога. Соблюдён infrastructure-only scope.

## 1. Созданные файлы

| Файл | Назначение |
|---|---|
| [compose.yaml](../../infrastructure/keycloak/compose.yaml) | Отдельные Keycloak/PostgreSQL, сеть, volume, health, limits |
| [.env.example](../../infrastructure/keycloak/.env.example) | Только placeholders и local admin username |
| [realm/esep-realm.json](../../infrastructure/keycloak/realm/esep-realm.json) | Realm, clients, PKCE, роли, mapper, TTL; нет users/secrets |
| [postgres-init.sql](../../infrastructure/keycloak/postgres-init.sql) | Создание dedicated DB и non-superuser Keycloak role |
| [keycloak.sh](../../infrastructure/keycloak/keycloak.sh) | Scoped lifecycle, random local env setup и проверки |
| [validate.py](../../infrastructure/keycloak/validate.py) | Реальные code/PKCE, config/JWT и persistence smoke tests |
| [README.md](../../infrastructure/keycloak/README.md) | Mac setup, issuer/JWKS, security, rollback |
| Этот документ | Команды, фактические результаты и ограничения |

Локально создан `infrastructure/keycloak/.env` со случайными независимыми
паролями, mode 0600; он ignored, не входит в deliverables. Корневой `.gitignore`
уже исключает `.env` и разрешает `.env.example`, поэтому менять его не потребовалось.
Существующие файлы не модифицировались; предыдущие db-lab/architecture artifacts сохранены.
Первичная реализация выполнялась без commit, staging и push. Публикация Phase 2.1
проводится отдельным согласованным Git-шагом, описанным ниже.

## 2. Фактическая среда и ресурсы

- Docker Engine **28.1.1**, architecture **aarch64**.
- Docker Compose **v2.35.1-desktop.1**, Docker Desktop на Mac.
- Keycloak **26.7.0**, ARM64, image digest
  `sha256:0f198be292568439d700cdbfb893e69a6009bb43a94a06a945b1d3d506c76b13`.
- PostgreSQL **17.11-alpine**, ARM64, image digest
  `sha256:562a6e0a66b5136bc28bef5944bbc2ddddf8ea0058d4016b96cee63f2edaf143`.
- Project **esep-identity-dev**; network **esep-identity-dev_identity**.
- Volume **esep-identity-dev_keycloak_pgdata**; DB **keycloak_db**.
- Runtime DB role `keycloak`: superuser=false, createdb=false, createrole=false.
  `kc_bootstrap` — отдельная bootstrap role, пароль не передаётся Keycloak.
- Keycloak: **healthy**, published **127.0.0.1:8180 → 8080**.
- PostgreSQL: **healthy**, host port **не опубликован**.
- 9000/8443 в `docker compose ps` — exposed image ports, не host publications.
- Однократный idle sample `docker stats`: Keycloak **497.2 MiB / 768 MiB**,
  PostgreSQL **31.12 MiB / 384 MiB**. Это не load-test или capacity guarantee.

Основной новый stack оставлен запущенным для локального использования.
Существующий `esep-postgres` сохранил container ID
`ee16bc0c7ce5f84a1260eea09b2bc0f045f1e7a44e891194bb5487618f55a739`,
volume `esep-api_esep-pgdata` и состояние exited. Его остановка произошла до
начала запуска нового стека: `2026-11-05T21:22:23.567334632Z`.
Команд подключения, старта, SQL или записи в ESEP PostgreSQL не выполнялось.
Побайтовая сверка содержимого старого PGDATA не выполнялась; изоляция подтверждена
отсутствием общих mounts/networks/DB URLs и неизменным старым container/mount.

## 3. Команды, выполненные при реализации

Все lifecycle-команды ниже scoped к новой инфраструктуре; обычные shell/Python
reads использовались для чтения документов, snapshot/checksums и записи новых файлов.
`rtk` не установлен в PATH, не устанавливался. Один Docker-доступ из sandbox
вернул permission denied; runtime-команды затем выполнены с разрешённым Docker access.

```bash
git status --short
git branch --show-current
docker compose version
docker info --format '{{.ServerVersion}} {{.Architecture}}'
docker ps -a --format '{{.ID}} {{.Names}} {{.Status}}'
docker volume ls --format '{{.Name}}'
docker inspect esep-postgres --format '{{.Id}} {{json .State.Status}} {{json .Mounts}}'
docker pull quay.io/keycloak/keycloak:26.7.0
docker pull postgres:17.11-alpine
bash -n infrastructure/keycloak/keycloak.sh
git check-ignore infrastructure/keycloak/.env
bash infrastructure/keycloak/keycloak.sh config
bash infrastructure/keycloak/keycloak.sh start
bash infrastructure/keycloak/keycloak.sh validate
bash infrastructure/keycloak/keycloak.sh persistence
```

Первый `.env` создан Python `secrets.token_hex(24)` с exclusive file creation и
chmod 0600 без вывода паролей. То же поведение доступно командой helper `init`.
JSON разбирался `json.loads`, Python проверялся `ast.parse` без создания pycache.

Для logs/диагностики и работы с dedicated DB использовался общий prefix:

```bash
docker compose -p esep-identity-dev --project-directory infrastructure/keycloak \
  --env-file infrastructure/keycloak/.env -f infrastructure/keycloak/compose.yaml ...
```

Suffixes: `ps`, `logs --tail 20 keycloak` (также tail 35/45/70 во время диагностики),
`up -d --no-deps keycloak-db`, `exec -T keycloak-db psql -U kc_bootstrap -d postgres
-f /docker-entrypoint-initdb.d/10-keycloak.sql`, read-only SQL проверки version/roles/
count пользователей в `keycloak_db`. При диагностике также читался
`docker logs --tail 500 esep-identity-dev-keycloak-db-1`.
Три коротких Python diagnostic runs выводили только HTTP status/form names,
error text или cookie metadata — не credentials, cookie values или tokens.

Persistence runner реально выполняет:

```text
scoped compose restart
scoped compose up -d --wait --wait-timeout 240
scoped compose up -d --force-recreate --wait --wait-timeout 240
```

Автоматический SQL bootstrap отдельно проверен на **новом** тестовом volume:

```bash
docker compose -p esep-identity-bootstrap-check --project-directory infrastructure/keycloak \
  --env-file infrastructure/keycloak/.env -f infrastructure/keycloak/compose.yaml \
  up -d --wait --wait-timeout 90 keycloak-db
docker compose -p esep-identity-bootstrap-check --project-directory infrastructure/keycloak \
  --env-file infrastructure/keycloak/.env -f infrastructure/keycloak/compose.yaml \
  exec -T keycloak-db psql -U kc_bootstrap -d keycloak_db \
  -Atc "SELECT current_database(),rolname,rolsuper,rolcreatedb,rolcreaterole FROM pg_roles WHERE rolname='keycloak'"
docker compose -p esep-identity-bootstrap-check --project-directory infrastructure/keycloak \
  --env-file infrastructure/keycloak/.env -f infrastructure/keycloak/compose.yaml stop
```

Результат role query: `keycloak_db|keycloak|f|f|f`. Вспомогательный контейнер
остановлен. Его volume `esep-identity-bootstrap-check_keycloak_pgdata` и сеть
сохранены, не удалялись. Ни один существующий Docker volume не удалён.

## 4. Проверки: PASSED

| Проверка | Фактический результат |
|---|---|
| Compose config | `config --quiet` exit 0 |
| Syntax | Bash syntax / JSON parse / Python AST прошли |
| ARM64 | Оба образа inspect: arm64 |
| Readiness | Оба контейнера healthy, PostgreSQL authenticated SELECT 1 |
| Realm | Импорт `esep` в startup log, затем Admin API HTTP 200 |
| Discovery | HTTP 200; issuer `http://localhost:8180/realms/esep` |
| JWKS | HTTP 200; RSA signing key RS256; публичный endpoint без credentials |
| Frontend config | public esep-web, standard flow true, PKCE S256, точный callback/origin |
| API config | esep-api bearer-only; required client roles USER/ADMIN |
| Forbidden grants | Admin API: directAccessGrantsEnabled=false, implicitFlowEnabled=false; serviceAccountsEnabled=false для frontend |
| PKCE authorization | Нет challenge и метод plain отклонены |
| PKCE token exchange | Неверный verifier → HTTP 400 `invalid_grant` |
| Redirect allowlist | Неодобренный callback → HTTP 400 |
| Implicit request | response_type=token отклонён с OAuth error |
| Реальный USER token | Code+PKCE successful; RS256 подпись проверена по JWKS; issuer/aud/expiry/TTL/USER верны |
| Реальный USER+ADMIN token | Code+PKCE successful; обе client roles, подпись/issuer/aud/TTL верны |
| Audience mapper | Access token aud включает esep-api; ID token aud=esep-web |
| Persistence restart | Realm ID, runtime marker, test user IDs и key IDs сохранены |
| Persistence recreate | Оба контейнера пересозданы с прежним volume; те же realm marker, identities и keys |
| Test-user cleanup | Финальная SQL count пользователей realm esep = 0 |
| Fresh PG bootstrap | Новый отдельный volume создал keycloak_db и non-superuser role автоматически |
| Scope/security | Existing files checksum check, env ignored/0600, secrets scan и git diff checks — см. итоговую проверку |

Smoke runner получает admin access token также через Authorization Code + PKCE
client `security-admin-console` master realm. Использует только временных
случайных business identities, не default accounts с hardcoded passwords.
**Password grant ни разу не использовался:** его отключение проверено конфигурацией,
запрос `grant_type=password` намеренно не отправлялся.

## 5. Первоначальные FAILED / устранённые проблемы

1. Docker socket из одной sandbox-команды: permission denied. Повтор с разрешённым
   доступом прошёл; работоспособность Docker затем подтверждена runtime.
2. Первый bootstrap был bind-mounted `.sh`; Docker Desktop вернул
   `bad interpreter: Permission denied`. Role/DB не создались, PostgreSQL
   pg_isready при этом был недостаточным индикатором. Заменено на `.sql`,
   healthcheck усилен до authenticated SELECT 1. Исправление применено к
   новому Keycloak volume без reset; fresh-volume bootstrap проверен отдельно.
3. Преждевременный HTTP smoke до завершения startup: `RemoteDisconnected`.
   Проверки повторены после healthy, успешны.
4. Первая версия stdlib smoke client не отправляла Secure cookies по localhost
   HTTP: Keycloak вернул HTTP 400 `Restart login cookie not found`.
   Runner получил узкое localhost-cookie policy, соответствующее browser secure
   context; настройки cookies Keycloak не ослаблялись. Последующий code flow прошёл.

Эти первоначальные ошибки не скрыты и не считаются PASS. Итоговые исправленные
`validate` и `persistence` завершились exit 0.

### Итоговая проверка scope и секретов

- SHA-256 **190** существовавших non-env файлов совпал с snapshot до реализации,
  включая семь архитектурных документов и db-lab.
- `git diff --exit-code HEAD` и `git diff --check`: exit 0; tracked файлы не менялись.
- `git check-ignore infrastructure/keycloak/.env`: файл ignored, permissions 0600.
- Генерированные значения всех трёх паролей не найдены ни в одном новом deliverable.
- `init` на существующем `.env` ожидаемо отказал с exit 1, checksum файла сохранён.
- Финальная проверка Docker: оба контейнера healthy, restart_count=0 после
  пересоздания; binding 127.0.0.1:8180, DB ports отсутствуют, сеть/mounts изолированы.
- Все локальные ссылки новых Markdown, JSON, Bash syntax и Python AST проверены.

## 6. NOT EXECUTED и известные ограничения

- Нет UI e2e в настоящем browser engine: runner использует реальные Keycloak
  endpoints, HTML form и cookies через stdlib. Интеграция frontend callback отложена.
- ESEP backend unit/integration tests, Keycloak resource-server validation в
  Spring, V8 identity migration и user linking не выполнялись и не реализованы.
  Текущий backend всё ещё ожидает собственный HS256 token.
- JWT signature проверена smoke helper, а не production Spring decoder; negative
  signature/issuer/audience и ownership tests Spring — следующий этап.
- Нет SMTP, self-registration/reset-password, production MFA policy, backup/restore,
  HA, load tests, CVE scan или production deployment.
- `start-dev` исключительно local; никаких production изменений.
- Realm startup import не обновляет существующий realm. Следующие настройки
  требуют явной admin migration; удаление данных не является способом обновления.
- `.env` меняет bootstrap параметры только при инициализации; реальная смена
  DB/admin password требует rotation procedure, не restart.
- Bootstrap admin временный в терминологии Keycloak. Для длительного использования
  создайте отдельного local admin через console; test runner использует configured
  bootstrap credentials и потребует актуальных реквизитов после их смены.
- Runtime realm marker остаётся для свидетельства persistence, PII/секретов в нём нет.

## 7. Security, rollback и следующий шаг

Loopback-only host binding; DB/management ports private; отдельный non-superuser
DB runtime login; никаких shared volumes/network с ESEP. Secrets только ignored
local env. Full resolved Compose config и token responses не печатались.
API roles берутся в будущем только из resource_access.esep-api.roles, без
автоматического ADMIN из Keycloak management roles. Не связывать аккаунты по email.

Rollback: `bash infrastructure/keycloak/keycloak.sh stop`. Новый named volume
и `.env` сохранить; вернуть stack можно командой `start`. Прежний ESEP не требует
отката, потому что его код, данные и auth mode не переключались.

Следующий implementation task — phase 2.2: additive identity schema/mapping и
реальные Spring Resource Server tests (iss/aud/signature/roles), затем отдельное
согласованное переключение auth/клиента. До этого Keycloak infrastructure сама
по себе не даёт доступа к существующим счетам.

## Повторная проверка перед Git-публикацией

По отдельному разрешению пользователя подготовлен task-only commit с сообщением
`feat(keycloak): add local identity infrastructure`. Семь документов первоначального
аудита и db-lab остаются локальными untracked-файлами, без изменений.

Повторно выполнены и завершились успешно:

- `bash infrastructure/keycloak/keycloak.sh config`;
- `bash -n infrastructure/keycloak/keycloak.sh`, Python AST и JSON parsing;
- `bash infrastructure/keycloak/keycloak.sh validate` — exit 0;
- `bash infrastructure/keycloak/keycloak.sh persistence` — exit 0, restart и
  recreate с сохранением realm/identity/keys; временные пользователи удалены.

Файлы просмотрены через Git diff; публикация ограничена infrastructure/keycloak
без local .env и этим отчётом. Для интеграции с актуальным origin/main используется
отдельный worktree, без stash/reset/clean и без force push. Итоговый SHA и результат
push проверяются отдельно после создания коммита, а не объявляются заранее.

## Официальные источники

- [Keycloak PostgreSQL compatibility](https://www.keycloak.org/server/supported-configurations).
- [Container health и bash TCP probe](https://www.keycloak.org/observability/health).
- [Keycloak production/container configuration](https://www.keycloak.org/server/containers).
- [Realm import semantics](https://www.keycloak.org/server/importExport).
