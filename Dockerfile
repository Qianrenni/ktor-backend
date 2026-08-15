# 建议使用 JRE 版本，体积更小，启动更快
FROM amazoncorretto:21 AS runtime

WORKDIR /app

# 暴露你在 application.conf 中配置的端口
EXPOSE 8000

# 提前创建好目录（虽然挂载时会自动创建，但显式声明更规范）
RUN mkdir -p /app

# 启动命令保持不变
ENTRYPOINT ["java", "-jar", "/app/app.jar"]