FROM clojure:tools-deps AS builder

WORKDIR /app
COPY deps.edn build.clj ./
# Trick to download all dependencies and cache them
# Cache Maven artifacts between builds to avoid re-downloading jars on each CI run.
# Cache git-based deps used by tools.deps (for git coordinates and cloned libs).
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    clj -T:build clean

COPY src ./src
COPY resources ./resources
# Reuse the same dependency caches during the actual uberjar build step.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    clj -T:build uber

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=builder /app/target/*-standalone.jar ./app.jar

CMD ["java", "-jar", "app.jar"]
