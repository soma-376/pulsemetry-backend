package com.team376.pulsemetry.telemetry.collector.masking

import com.google.protobuf.ByteString
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest
import io.opentelemetry.proto.common.v1.AnyValue
import io.opentelemetry.proto.common.v1.KeyValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * 마스킹이 닿는 자리와 닿지 않는 자리의 특성화 테스트.
 *
 * 값이 어떻게 바뀌는지는 `SecretMaskerTest` 가 golden 으로 본다. 여기서는 **어디를 훑는가**만 본다.
 */
class AttributeWalkerTest {

	private val walker = AttributeWalker(SecretMasker())

	private fun stringAttr(key: String, value: String): KeyValue.Builder =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value))

	private fun intAttr(key: String, value: Long): KeyValue.Builder =
		KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value))

	@Test
	@DisplayName("logs — resource · scope · 레코드 속성과 body 를 모두 훑는다")
	fun masksEveryLogSite() {
		val secret = "sk-abcdefghij1234567890"
		val request = ExportLogsServiceRequest.newBuilder()
		val resourceLogs = request.addResourceLogsBuilder()
		resourceLogs.resourceBuilder.addAttributes(stringAttr("res", secret))
		val scopeLogs = resourceLogs.addScopeLogsBuilder()
		scopeLogs.scopeBuilder.addAttributes(stringAttr("scope", secret))
		val record = scopeLogs.addLogRecordsBuilder()
		record.addAttributes(stringAttr("rec", secret))
		record.bodyBuilder.stringValue = "body $secret"

		walker.maskLogs(request)

		val out = request.build().getResourceLogs(0)
		assertThat(out.resource.getAttributes(0).value.stringValue).isEqualTo("****")
		assertThat(out.getScopeLogs(0).scope.getAttributes(0).value.stringValue).isEqualTo("****")
		assertThat(out.getScopeLogs(0).getLogRecords(0).getAttributes(0).value.stringValue).isEqualTo("****")
		assertThat(out.getScopeLogs(0).getLogRecords(0).body.stringValue).isEqualTo("body ****")
	}

	@Test
	@DisplayName("logs — body 의 map 과 array 를 재귀로 훑는다")
	fun recursesIntoStructuredBody() {
		val secret = "AKIAEEEEEEEEEEEEEEEE"
		val request = ExportLogsServiceRequest.newBuilder()
		val record = request.addResourceLogsBuilder().addScopeLogsBuilder().addLogRecordsBuilder()

		val body = record.bodyBuilder.kvlistValueBuilder
		body.addValues(stringAttr("flat", secret))
		val nested = body.addValuesBuilder().setKey("nested").valueBuilder.kvlistValueBuilder
		nested.addValues(stringAttr("deep", secret))
		val array = body.addValuesBuilder().setKey("list").valueBuilder.arrayValueBuilder
		array.addValuesBuilder().stringValue = secret

		walker.maskLogs(request)

		val out = request.build().getResourceLogs(0).getScopeLogs(0).getLogRecords(0).body.kvlistValue
		assertThat(out.getValues(0).value.stringValue).isEqualTo("****")
		assertThat(out.getValues(1).value.kvlistValue.getValues(0).value.stringValue).isEqualTo("****")
		assertThat(out.getValues(2).value.arrayValue.getValues(0).stringValue).isEqualTo("****")
	}

	@Test
	@DisplayName("traces — resource · scope · span 속성과 span event 속성을 훑는다")
	fun masksEveryTraceSite() {
		val secret = "ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
		val request = ExportTraceServiceRequest.newBuilder()
		val resourceSpans = request.addResourceSpansBuilder()
		resourceSpans.resourceBuilder.addAttributes(stringAttr("res", secret))
		val scopeSpans = resourceSpans.addScopeSpansBuilder()
		scopeSpans.scopeBuilder.addAttributes(stringAttr("scope", secret))
		val span = scopeSpans.addSpansBuilder()
		span.addAttributes(stringAttr("span", secret))
		span.addEventsBuilder().addAttributes(stringAttr("event", secret))

		walker.maskTraces(request)

		val out = request.build().getResourceSpans(0)
		assertThat(out.resource.getAttributes(0).value.stringValue).isEqualTo("****")
		assertThat(out.getScopeSpans(0).scope.getAttributes(0).value.stringValue).isEqualTo("****")
		assertThat(out.getScopeSpans(0).getSpans(0).getAttributes(0).value.stringValue).isEqualTo("****")
		assertThat(out.getScopeSpans(0).getSpans(0).getEvents(0).getAttributes(0).value.stringValue)
			.isEqualTo("****")
	}

	@Test
	@DisplayName("traces — span link 속성은 훑지 않는다. 상위의 공백을 그대로 옮긴 것이다")
	fun leavesSpanLinkAttributesAlone() {
		// 상위 processResourceSpan 은 processSpanEvents 만 부르고 링크는 지나친다.
		// 고치는 것이 아니라 옮기는 티켓이므로 그 공백까지 같이 옮긴다. 여기가 깨졌다면
		// 상위 동작이 바뀌었거나 우리가 임의로 범위를 넓힌 것이다 — 둘 다 확인이 필요하다.
		val secret = "sk-abcdefghij1234567890"
		val request = ExportTraceServiceRequest.newBuilder()
		val span = request.addResourceSpansBuilder().addScopeSpansBuilder().addSpansBuilder()
		span.addLinksBuilder().addAttributes(stringAttr("link", secret))

		walker.maskTraces(request)

		assertThat(request.build().getResourceSpans(0).getScopeSpans(0).getSpans(0).getLinks(0)
			.getAttributes(0).value.stringValue).isEqualTo(secret)
	}

	@Test
	@DisplayName("속성은 문자열만 본다 — 비문자열은 타입도 값도 그대로다 (redact_all_types: false)")
	fun leavesNonStringAttributesUntouched() {
		val request = ExportLogsServiceRequest.newBuilder()
		val record = request.addResourceLogsBuilder().addScopeLogsBuilder().addLogRecordsBuilder()
		record.addAttributes(intAttr("payload.tokens.input", 4_111_111_111_111_111L))
		record.addAttributes(
			KeyValue.newBuilder().setKey("flag").setValue(AnyValue.newBuilder().setBoolValue(true)),
		)
		record.bodyBuilder.stringValue = "no secret here"

		walker.maskLogs(request)

		val out = request.build().getResourceLogs(0).getScopeLogs(0).getLogRecords(0)
		assertThat(out.getAttributes(0).value.hasIntValue()).isTrue()
		assertThat(out.getAttributes(0).value.intValue).isEqualTo(4_111_111_111_111_111L)
		assertThat(out.getAttributes(1).value.hasBoolValue()).isTrue()
	}

	@Test
	@DisplayName("body 는 redact_all_types 를 무시한다 — 비문자열 body 는 스캔되고 매치되면 문자열이 된다")
	fun logBodyIsScannedRegardlessOfType() {
		// 상위 processLogBody 의 스칼라 분기가 AsString() 을 무조건 쓰기 때문에 생기는 비대칭이다.
		// 배포 중인 열넷은 숫자만으로는 매치되지 않아 이 경로가 겉으로 드러나지 않는다.
		// 규칙을 갈아 끼워 경로 자체를 고정한다 — 규칙이 늘면 실제로 이렇게 동작한다.
		val masker = SecretMasker(listOf(Pattern.compile("4111\\d+")))
		val walker = AttributeWalker(masker)

		val request = ExportLogsServiceRequest.newBuilder()
		val record = request.addResourceLogsBuilder().addScopeLogsBuilder().addLogRecordsBuilder()
		record.addAttributes(intAttr("attr", 4_111_111_111_111_111L))
		record.bodyBuilder.intValue = 4_111_111_111_111_111L

		walker.maskLogs(request)

		val out = request.build().getResourceLogs(0).getScopeLogs(0).getLogRecords(0)
		// 속성은 int 그대로 — Str() 이 "" 를 주므로 규칙이 닿지 않는다.
		assertThat(out.getAttributes(0).value.hasIntValue()).isTrue()
		// body 는 문자열로 바뀐다 — AsString() 이 "4111111111111111" 을 주고 규칙이 매치한다.
		assertThat(out.body.hasStringValue()).isTrue()
		assertThat(out.body.stringValue).isEqualTo("****")
	}

	@Test
	@DisplayName("body 의 bytes 는 base64 로 펴서 스캔한다 — 상위 AsString() 과 같다")
	fun logBodyBytesAreScannedAsBase64() {
		val masker = SecretMasker(listOf(Pattern.compile("aGVsbG8=")))
		val walker = AttributeWalker(masker)

		val request = ExportLogsServiceRequest.newBuilder()
		val record = request.addResourceLogsBuilder().addScopeLogsBuilder().addLogRecordsBuilder()
		record.bodyBuilder.bytesValue = ByteString.copyFromUtf8("hello")

		walker.maskLogs(request)

		assertThat(request.build().getResourceLogs(0).getScopeLogs(0).getLogRecords(0).body.stringValue)
			.isEqualTo("****")
	}
}
