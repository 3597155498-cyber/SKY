# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只拷贝 pom，利用镜像层缓存拉取依赖
COPY pom.xml ./
COPY sky-common/pom.xml sky-common/
COPY sky-pojo/pom.xml sky-pojo/
COPY sky-ai-server/pom.xml sky-ai-server/
COPY sky-server/pom.xml sky-server/
RUN mvn -B -q -DskipTests dependency:go-offline

COPY sky-common/src sky-common/src
COPY sky-pojo/src sky-pojo/src
COPY sky-ai-server/src sky-ai-server/src
COPY sky-server/src sky-server/src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/sky-server/target/sky-server-1.0-SNAPSHOT.jar app.jar
# 同一镜像内也带上 AI 服务，compose 可用 entrypoint 覆盖来单独起 sky-ai-server
COPY --from=build /build/sky-ai-server/target/sky-ai-server-1.0-SNAPSHOT.jar ai.jar
EXPOSE 8080 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
