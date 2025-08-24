# reddit-sub-follower

## How to run

With REPL
```bash
dotenv clj -- -M:run
```

Compile and run JAR

```bash
dotenv clj -- -T:build uber
dotenv java -- -jar target/reddit-sub-follower-1.x.x-standalone.jar
```
