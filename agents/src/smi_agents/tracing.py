"""Tracing: Agent Framework's OpenTelemetry spans (workflow, executors, model calls) exported to
Application Insights when APPLICATIONINSIGHTS_CONNECTION_STRING is set. Off otherwise."""

import os
from collections.abc import Mapping

SERVICE_NAME = "smi-agents"


def setup(env: Mapping[str, str] = os.environ) -> bool:
    connection_string = env.get("APPLICATIONINSIGHTS_CONNECTION_STRING")
    if not connection_string:
        return False
    from agent_framework.observability import enable_instrumentation
    from azure.monitor.opentelemetry import configure_azure_monitor

    os.environ.setdefault("OTEL_SERVICE_NAME", SERVICE_NAME)
    configure_azure_monitor(connection_string=connection_string)
    enable_instrumentation()
    return True
