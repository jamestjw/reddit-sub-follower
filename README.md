# reddit-sub-follower

## How to run

### Database backend

`DATABASE_BACKEND` controls which DB implementation is used.

- `sqlite` (default): set `DB_FILE`
- `postgres`: set `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USER`, `DATABASE_PASSWORD`

With REPL
```bash
dotenv clj -- -M:run
```

Run a single scrape cycle (useful for cron):
```bash
dotenv clj -- -M:run --once
```

Compile and run JAR

```bash
dotenv clj -- -T:build uber
dotenv java -- -jar target/reddit-sub-follower-1.x.x-standalone.jar
```
