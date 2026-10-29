# 后端镜像：Maven 多阶段构建，运行时用 JRE 非 root 用户
# 依赖层与源码层分开，只有 pom.xml 变动时才重新解析依赖

FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /app
# 国内网络用阿里云镜像，走默认中央仓库会很慢
COPY docker/maven-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -B -q -s /root/.m2/settings.xml dependency:go-offline

FROM deps AS builder
COPY src ./src
RUN mvn -B -q -s /root/.m2/settings.xml -DskipTests package

FROM eclipse-temurin:21-jre
RUN groupadd -r appuser && useradd -r -g appuser appuser
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
