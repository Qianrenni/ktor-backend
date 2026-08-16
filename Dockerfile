# 建议使用 JRE 版本，体积更小，启动更快
FROM amazoncorretto:21 AS runtime

WORKDIR /app

# 安全加固（L6）：非 root 运行 + 健康检查
# 提前创建好目录并授权给非 root 用户（应用会在 /app 下写 logs/ store/ static/）
RUN mkdir -p /app && useradd -r -u 1001 appuser && chown -R appuser:appuser /app

USER appuser

# 暴露你在 application.conf 中配置的端口
EXPOSE 8000

# 健康检查：TCP 探测应用端口
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8000'

# 启动命令保持不变
ENTRYPOINT ["java", "-jar", "/app/app.jar"]