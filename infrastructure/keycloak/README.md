# ESEP Phase 2.1 — локальный Keycloak

Отдельная identity-инфраструктура. **ESEP пока продолжает собственный HS256 login.**
Здесь не меняются Java, Flyway, ID пользователей, данные ESEP, Kafka/Redis и production.
Контекст, проверки и дальнейшая интеграция:
[phase report](../../docs/architecture/PHASE_2_1_KEYCLOAK_REPORT.md).
Первоначальный архитектурный аудит хранится отдельно и не входит в этот commit.

Phase 2.2 добавляет отдельный opt-in профиль backend, не меняя default auth:
[identity mapping и безопасный trial](../../docs/runbooks/keycloak-identity-migration.md).

## Состав

| Ресурс | Значение |
|---|---|
| Compose project | `esep-identity-dev` — helper фиксирует имя и Compose-файл |
| Keycloak | `quay.io/keycloak/keycloak:26.7.0`, закреплён SHA-256 digest |
| PostgreSQL | `postgres:17.11-alpine`, закреплён SHA-256 digest, отдельный container |
| Сеть | `esep-identity-dev_identity`, bridge, не связана с ESEP/db-lab |
| Постоянный volume | `esep-identity-dev_keycloak_pgdata` |
| База / DB runtime role | `keycloak_db` / `keycloak` (не superuser) |
| PostgreSQL bootstrap role | `kc_bootstrap`, отдельный пароль; не используется Keycloak |
| Публичный локальный порт | Только `127.0.0.1:8180` → Keycloak 8080 |
| Management health | 9000 внутри контейнера; не публикуется на Mac |

В Keycloak явно включён PostgreSQL (`KC_DB=postgres`), не H2/dev-file/in-memory.
Liquibase Keycloak управляет своей схемой; ESEP Flyway не запускается здесь.
`postgres-init.sql` создаёт DB/ограниченную роль из env только на новом volume.
Повторное ручное применение не удаляет данные и не меняет пароль существующей роли.
Healthcheck PostgreSQL выполняет реальный authenticated `SELECT 1` по TCP;
Keycloak проверяет `/health/ready` через встроенный bash `/dev/tcp`, без curl в image.

## Запуск на Mac M1

Нужен работающий Docker Desktop с Compose v2 (`--wait`) и Python 3 для smoke runner.
Образы проверены как ARM64, `platform: linux/amd64` не требуется.
Новые зависимости helper не устанавливает. Первый запуск скачивает два образа.
Все следующие команды из корня репозитория:

```bash
bash infrastructure/keycloak/keycloak.sh init
bash infrastructure/keycloak/keycloak.sh config
bash infrastructure/keycloak/keycloak.sh start
bash infrastructure/keycloak/keycloak.sh status
```

`init` создаёт `.env` с тремя разными случайными паролями, правами 0600 и
локальным admin username. Не перезаписывает существующий `.env`; если файл уже
есть, используйте его, не запускайте init повторно. Для ручной настройки есть
`.env.example`: замените все placeholders; рекомендуются независимые hex-пароли.
Не используйте реквизиты ESEP/production. `.env` уже исключён корневым `.gitignore`.

Keycloak console: **http://localhost:8180/admin/**. Admin username/password
смотрите локально в `.env` редактором; не вставляйте их в чат или Git.
Это bootstrap-администратор Keycloak master realm, не бизнес-пользователь ADMIN.
В realm `esep` бизнес-пользователи не создаются при импорте.

`start` ждёт ready до 240 секунд. Начальные limits: Keycloak 768 MiB / 1.5 CPU,
PostgreSQL 384 MiB / 1 CPU, pool Keycloak min=1/max=5. Это локальные budgets,
не throughput/SLA. Начальный JVM heap 30%, max 65% container RAM.
Не запускайте всю observability-платформу ради этого этапа. Порт 8180 должен
быть свободен; не меняйте только port mapping без согласованной смены hostname/issuer.

## Управление

```bash
bash infrastructure/keycloak/keycloak.sh logs
bash infrastructure/keycloak/keycloak.sh restart
bash infrastructure/keycloak/keycloak.sh stop
```

`stop` останавливает только этот project. Данные и container definitions сохраняются.
`restart` ждёт ready после перезапуска. Volume переживает restart/recreate, но это
не backup. Не удаляйте volumes и не сбрасывайте базу при ошибке подключения.
Переменные `KC_*`, экспортированные в shell, имеют приоритет над `.env` — проверьте
их при неожиданном поведении. Полный `docker compose config` раскрывает пароли;
helper `config` использует `--quiet`.

## Realm, clients и роли

Realm `esep` импортируется из `realm/esep-realm.json`, mount read-only.

| Параметр | Значение |
|---|---|
| Frontend client | `esep-web`, public, без secret |
| Flow | Authorization Code, обязательный PKCE `S256` |
| Direct Access Grants / implicit / service accounts | Выключены для frontend |
| Callback | Точно `http://localhost:5173/oidc/callback` |
| Web Origin | Точно `http://localhost:5173` |
| Post-logout redirect | Точно `http://localhost:5173/` |
| API resource | `esep-api`, bearer-only, flows выключены |
| Client roles | `esep-api`: `USER`, `ADMIN` |
| Audience | Hardcoded audience mapper `esep-api`, только access token |
| Role claim | `resource_access.esep-api.roles`, multivalued; role scope явно ограничен |
| Subject | Default client scope `basic` добавляет обязательный access-token `sub` |
| Signing / access TTL | RS256 / 300 секунд |
| SSO | idle 30 минут, max 8 часов; refresh rotation без reuse |
| Auth code | 60 секунд после выдачи |

Нет wildcard redirects/origins, secrets, users или паролей в realm JSON.
Self-registration и reset password отключены: в этой локальной фазе нет SMTP/
legacy user activation. Роли назначаются явно; никто не получает ADMIN по умолчанию.
Для ручного demo можно создать нового пользователя в `esep` через Admin Console,
задать локальный пароль и назначить client role USER; не связывайте его со старым
финансовым аккаунтом. Такой пользователь никак не меняет существующую ESEP DB.

Страница frontend callback пока **не реализована в esep-web**. URL зарезервирован
для следующей интеграции, текущий frontend не переключается этим заданием.
Валидация перехватывает redirect, поэтому для smoke не нужен сервер на 5173.

Startup import пропускает уже существующий realm: правка JSON + restart не обновляет
живую конфигурацию. Не удаляйте realm/volume для обновления; следующий шаг —
отдельная согласованная Admin API migration. Realm JSON не полноценный backup БД.

Для realm, созданного Phase 2.1 без scope `basic`, сначала выполните dry-run,
затем явное применение. Добавляется только scope текущему `esep-web`, без reset
и без изменения user IDs. Script использует admin Authorization Code + PKCE:

```bash
python3 infrastructure/keycloak/migrate-subject-scope.py
python3 infrastructure/keycloak/migrate-subject-scope.py --apply
```

В другом worktree добавьте `--identity-dir /absolute/path/to/original/infrastructure/keycloak`
для доступа к существующему ignored `.env`. Новая установка уже импортирует basic.
Повтор миграции не меняет конфигурацию. Access tokens, выданные до исправления,
не получают sub задним числом: после применения выполните новый OIDC login.

## Issuer и JWKS: хост, Docker, production

| Где | Canonical issuer | JWKS |
|---|---|---|
| Браузер / JVM на Mac | `http://localhost:8180/realms/esep` | `http://localhost:8180/realms/esep/protocol/openid-connect/certs` |
| Будущий backend в Docker | Тот же внешний issuer, без замены `iss` | `http://keycloak:8080/realms/esep/protocol/openid-connect/certs` после явного подключения identity network |
| Будущий production | Постоянный HTTPS URL своего IdP | Его HTTPS discovery/JWKS; точные URL задаются deployment |

Сейчас никакой backend к identity network не подключён. `localhost` внутри
контейнера означает сам контейнер, не Mac и не Keycloak. Изменение JWKS адреса
не отменяет строгую проверку canonical issuer. Hostname зафиксирован в Compose.

Discovery:

```bash
curl --fail --silent --show-error 'http://localhost:8180/realms/esep/.well-known/openid-configuration'
curl --fail --silent --show-error 'http://localhost:8180/realms/esep/protocol/openid-connect/certs'
```

Будущий Spring Resource Server (пример, **не применён к текущему приложению**):

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${OIDC_ISSUER_URI}
          jwk-set-uri: ${OIDC_JWK_SET_URI}
          audiences: [esep-api]
```

Требуются signature RS256, issuer, audience, expiry/nbf и явное преобразование
ролей только esep-api → ROLE_USER/ROLE_ADMIN. ID token не отправляется в API.
`sub` непрозрачный: будущая approved mapping `(issuer, subject) → users.id`
сохраняет numeric IDs; email matching не даёт права на старый счёт.
Снятие роли/logout не отзывает уже выданный JWT мгновенно.

## Воспроизводимые проверки

После успешного `start`:

```bash
bash infrastructure/keycloak/keycloak.sh validate
bash infrastructure/keycloak/keycloak.sh persistence
```

`validate.py` использует только Python stdlib и Docker CLI:

1. Читает Compose config в память без вывода credentials.
2. Проверяет discovery/JWKS и signing key.
3. Входит в master Admin Console через реальный Authorization Code + PKCE flow.
4. Через Admin API читает clients/roles/mapper/TTL и disabled grants.
5. Проверяет отклонение authorization без challenge и с методом plain.
6. Создаёт две случайные временные identity, назначает USER и USER+ADMIN,
   проверяет code exchange и неверный verifier, RS256-подпись, issuer/audience/TTL/roles.
7. В finally удаляет только созданные им identity. Пароли и tokens не печатает.

Пароль вводится в HTML login form Keycloak, как при браузерном login; token endpoint
получает только `grant_type=authorization_code`. Password grant не используется
даже для admin. Флаг `directAccessGrantsEnabled=false` проверяется через Admin API;
запрос с password grant намеренно не отправляется.

Smoke runner эмулирует HTML form/cookies, а не запускает настоящий browser engine.
Для `localhost:8180` он учитывает Secure-cookie поведение современных браузеров;
для произвольных HTTP hosts исключение не действует. Production OIDC клиент
нужно строить на поддерживаемой библиотеке, не на этом тестовом helper.

`persistence` дополнительно сохраняет runtime realm attribute marker, перезапускает
и пересоздаёт оба контейнера без удаления volume, проверяет realm ID, test users
и signing keys. Прерывает подключения только этого project. Runtime marker остаётся
в realm как безопасное свидетельство записи; users удаляются. Не запускайте
одновременно несколько persistence checks. При аварии проверьте в console
пользователей `phase21-check-*` перед повтором; helper не удаляет посторонних users.

PASS выводится после проверки; ошибка даёт ненулевой exit code. Протокол,
первоначальные ошибки и фактические результаты: [PHASE_2_1 report](../../docs/architecture/PHASE_2_1_KEYCLOAK_REPORT.md).

## Безопасность и rollback

`start-dev` здесь **только LOCAL**, не production template. Loopback binding
не защищает от программ/пользователей с доступом к Docker daemon. `.env` и
container env доступны администратору Docker; храните машину и файлы защищённо.

Будущий production — отдельная задача: `start --optimized`, TLS, постоянный hostname,
trusted proxy headers, защищённый admin plane, MFA policy, secrets management,
ограниченные grants и проверенные backup/restore. Здесь production не изменён.

Rollback phase 2.1:

```bash
bash infrastructure/keycloak/keycloak.sh stop
```

Сохраните `.env` и named volume; для возврата выполните `start`. Ни ESEP auth,
ни financial DB переключать обратно не нужно: они не переключались. Не меняйте
пароли только в `.env` на существующем volume: DB role/admin password в БД от
этого не обновятся. Для изменения нужен отдельный согласованный rotation process.

При прерванной первичной инициализации проверьте логи. Если база уже создана,
а role отсутствует, после диагностики допустимо применить только этот bootstrap
к **новой dedicated Keycloak DB**, без reset:

```bash
docker compose -p esep-identity-dev --project-directory infrastructure/keycloak \
  --env-file infrastructure/keycloak/.env -f infrastructure/keycloak/compose.yaml \
  exec -T keycloak-db psql -U kc_bootstrap -d postgres \
  -f /docker-entrypoint-initdb.d/10-keycloak.sql
```

## Источники

- [Keycloak supported databases](https://www.keycloak.org/server/supported-configurations).
- [Health checks без curl](https://www.keycloak.org/observability/health).
- [Container startup / production](https://www.keycloak.org/server/containers).
- [Realm import](https://www.keycloak.org/server/importExport).
- [OIDC / PKCE adapter](https://www.keycloak.org/securing-apps/javascript-adapter).
