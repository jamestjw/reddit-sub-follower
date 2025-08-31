FROM clojure:tools-deps AS builder

WORKDIR /app
COPY deps.edn build.clj ./
# Trick to download all dependencies and cache them
RUN clj -T:build clean

COPY src ./src
RUN clj -T:build uber

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=builder /app/target/*-standalone.jar ./app.jar

CMD ["java", "-jar", "app.jar"]
