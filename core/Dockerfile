# The core service (and the generator job) as one image. Build the jar first: `sbt assembly`
# (azd runs it as a prepackage hook). Images are built in Azure Container Registry.
FROM eclipse-temurin:25-jre

ARG AI_AGENT_VERSION=3.7.10
WORKDIR /app

# Application Insights Java agent: traces and metrics without code changes. Only active when
# JAVA_TOOL_OPTIONS=-javaagent:/app/applicationinsights-agent.jar is set (it is in Azure).
ADD https://github.com/microsoft/ApplicationInsights-Java/releases/download/${AI_AGENT_VERSION}/applicationinsights-agent-${AI_AGENT_VERSION}.jar /app/applicationinsights-agent.jar
COPY target/scala-3.9.0/app.jar /app/app.jar
RUN chmod 644 /app/*.jar && useradd --system --uid 10001 smi

USER smi
ENV SMI_HOST=0.0.0.0 SMI_PORT=8080
EXPOSE 8080
# Generator job: java -cp /app/app.jar smi.Publish --seed 154 --speed 1
ENTRYPOINT ["java", "--sun-misc-unsafe-memory-access=allow", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
