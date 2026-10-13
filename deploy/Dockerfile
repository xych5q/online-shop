# ============================================================================
# 在线购物系统 · 容器镜像（两阶段构建）
# 第 1 组（组长：陈星宇） | 迭代 1 基线 v0.1-iter1
#
# 构建上下文必须是**仓库根目录**（因为要 COPY server/*）：
#   docker build -f deploy/Dockerfile -t online-shop:0.1 .
# 说明：builder 阶段跑 -DskipTests，单测在宿主机由 `mvn test` 单独执行，
#       避免镜像构建被测试拖慢、也避免把测试数据打进镜像。
# ============================================================================

# ---------- 阶段 1：构建（Maven + JDK 21） ----------
FROM maven:3.9-eclipse-temurin-21 AS builder

LABEL stage=builder
LABEL org.opencontainers.image.title="online-shop"
LABEL org.opencontainers.image.description="第1组 在线购物系统 迭代1基线镜像（构建阶段）"
LABEL org.opencontainers.image.version="0.1-iter1"

WORKDIR /app

# 先只拷 pom.xml，让依赖下载这层可以被 Docker 缓存复用
COPY server/pom.xml ./pom.xml
RUN mvn -B dependency:go-offline -DskipTests || true

# 再拷源码打包
COPY server/src ./src
RUN mvn -B clean package -DskipTests

# ---------- 阶段 2：运行（仅 JRE 21） ----------
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="online-shop"
LABEL org.opencontainers.image.description="第1组 在线购物系统 迭代1基线镜像"
LABEL org.opencontainers.image.version="0.1-iter1"
LABEL org.opencontainers.image.authors="第1组 陈星宇 <online-shop@group1>"

WORKDIR /app

# 容器内统一用东八区，避免接口返回的时间戳与本地差 8 小时
ENV TZ=Asia/Shanghai
ENV JAVA_OPTS=""

COPY --from=builder /app/target/online-shop.jar /app/app.jar

# H2 文件库与上传目录都在 /app/data 下，需要持久化时挂卷到这里
VOLUME ["/app/data"]

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
