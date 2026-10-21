# Agent service: narration API on 8090. The MCP server runs inside as a stdio subprocess.
FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    SMI_MODEL=foundry

WORKDIR /app
COPY pyproject.toml ./
COPY src ./src
RUN pip install . \
    && useradd --create-home --uid 10001 smi

USER smi
EXPOSE 8090
CMD ["python", "-m", "smi_agents", "serve", "--host", "0.0.0.0", "--port", "8090"]
