package com.team376.pulsemetry.telemetry.observability

/**
 * 플랫폼 trace의 공통 규칙(ADR 0069). Spring·OpenTelemetry 타입 없이 값과 판정만 둔다 — Gateway와 ingest가 같은 규칙으로 조립한다.
 *
 * 고객이 보낸 OTLP의 trace와 섞이지 않도록 공개 요청의 trace header는 받지 않고, 플랫폼 span의 resource는 아래 값으로만 정한다.
 */
public object PlatformTracing {
    /** `PULSEMETRY_TRACING_ENABLED`가 이 Boot 설정으로 들어온다. 꺼져 있으면 exporter·전파가 없다. */
    public const val ENABLED_PROPERTY: String = "management.tracing.export.enabled"

    /** `PULSEMETRY_TRACING_OTLP_TRACES_ENDPOINT`. OTLP/HTTP의 `/v1/traces`까지 쓴 전체 URL이다. */
    public const val ENDPOINT_PROPERTY: String = "management.opentelemetry.tracing.export.otlp.endpoint"

    public const val GATEWAY_SERVICE: String = "telemetry-gateway"
    public const val INGEST_SERVICE: String = "telemetry-ingest"

    /** Kafka record에 싣는 유일한 trace header다. W3C trace context의 `traceparent`는 55자 고정이다. */
    public const val TRACEPARENT: String = "traceparent"
    public const val TRACEPARENT_LENGTH: Int = 55

    /**
     * 공개 요청에서 지우는 header. 외부 입력이 플랫폼 trace의 부모·baggage를 고르지 못하게 한다.
     * 소문자로 비교한다(HTTP header 이름은 대소문자를 가리지 않는다).
     */
    public val INBOUND_TRACE_HEADERS: Set<String> = setOf(
        TRACEPARENT, "tracestate", "baggage",
        "b3", "x-b3-traceid", "x-b3-spanid", "x-b3-parentspanid", "x-b3-sampled", "x-b3-flags",
    )

    public fun isInboundTraceHeader(name: String): Boolean = name.lowercase() in INBOUND_TRACE_HEADERS

    /** ClickHouse 호출의 operation·table 허용목록. 지표 label과 span 속성이 같은 목록을 쓴다(ADR 0068·0069). 밖의 값은 `other`다. */
    public val CLICKHOUSE_OPERATIONS: Set<String> = setOf("insert", "select", "delete", "schema", "other")
    public val CLICKHOUSE_TABLES: Set<String> =
        setOf("telemetry_events", "telemetry_metric_points", "telemetry_ingest_ledger", "telemetry_retention_fence")

    public fun clickHouseOperation(value: String?): String = value.takeIf { it in CLICKHOUSE_OPERATIONS } ?: "other"

    public fun clickHouseTable(value: String?): String = value.takeIf { it in CLICKHOUSE_TABLES } ?: "other"

    /** 켜져 있는데 endpoint가 비어 있으면 기동하지 않는다. 조용히 span을 버리는 배포를 막는다. */
    public fun requireEndpoint(enabled: Boolean, endpoint: String?) {
        require(!enabled || !endpoint.isNullOrBlank()) {
            "PULSEMETRY_TRACING_OTLP_TRACES_ENDPOINT is required when PULSEMETRY_TRACING_ENABLED=true"
        }
    }

    /**
     * span resource 속성. `service.name`은 앱 이름(`spring.application.name`)과 따로 고정한다.
     * 값이 비어 있는 속성은 넣지 않는다 — 로컬 실행에는 Kubernetes 값이 없다.
     */
    public fun resourceAttributes(
        serviceName: String,
        environment: String?,
        cluster: String?,
        namespace: String?,
        pod: String?,
    ): Map<String, String> = linkedMapOf(
        "service.name" to serviceName,
        "deployment.environment.name" to environment,
        "k8s.cluster.name" to cluster,
        "k8s.namespace.name" to namespace,
        "k8s.pod.name" to pod,
    ).filterValues { !it.isNullOrBlank() }.mapValues { it.value!! }

    /** health와 관리 경로는 span을 만들지 않는다. probe·scrape가 sampling 예산을 쓰지 않게 한다. */
    public fun tracesServerPath(path: String): Boolean =
        path != "/api/v1/healthz" && path != "/actuator" && !path.startsWith("/actuator/")

    /**
     * Kafka record header에서 부모 문맥으로 쓸 `traceparent`를 고른다. 정확히 하나이고 길이가 맞을 때만 돌려준다.
     * 없음·중복·과대 길이는 `null`이다 — 호출자는 새 root로 처리한다. 형식 검사는 W3C propagator가 한 번 더 한다.
     */
    public fun singleTraceparent(values: List<ByteArray>): String? {
        val value = values.singleOrNull() ?: return null
        if (value.size != TRACEPARENT_LENGTH) return null
        return value.toString(Charsets.US_ASCII)
    }
}
