# 构建阶段：用 Maven 打包
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY server/pom.xml .
COPY server/src ./src
RUN mvn -B clean package -DskipTests

# 运行阶段：只带 JRE，镜像更小
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
