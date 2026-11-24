package com.team376.pulsemetry.telemetry.consumer.batch

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecords
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 배치 리스너·오류 처리기·재할당 리스너가 소비자마다 나눠 갖는 상태(PROJ-341).
 *
 * 컨테이너는 소비자 하나에 스레드 하나이고, 리스너·오류 처리기·재할당 콜백은 그 소비 스레드에서 불린다. 그래서 전달 단위의 상태(완료
 * 표시·다음 대기)는 스레드 지역 값이다. 재할당 카운터는 `handleBatch` 와 재할당 리스너에 **같이 전달되는 `Consumer` 객체**를 키로
 * 한다 — `handleBatch` 의 `container` 인자는 부모 컨테이너이고 재할당 리스너 인스턴스는 소비자들이 공유하기 때문이다.
 *
 * ## 완료 표시
 *
 * 리스너는 모든 필수 쓰기 또는 넘김 목록 기록을 끝내고 정상 반환하는 마지막 지점에서만 이번 전달(원본 `ConsumerRecords`)을 표시한다.
 * 오류 처리기는 자기에게 온 객체와 표시를 대조해, 정상 반환 뒤의 동기 커밋 실패를 미완료 배치와 가른다. 약한 참조라 유휴 소비자가
 * 마지막 배치 본문을 붙들지 않는다. 각 리스너 호출 시작·핸들러 사용·재할당 때 지운다.
 *
 * ## 재할당 카운터
 *
 * 객체 동일성으로 구분하는 약한 키 맵이다(`KafkaConsumer` 는 `equals` 를 재정의하지 않는다). 값은 소비자를 역참조하지 않는 카운터라
 * 종료 통지가 생략돼도 맵이 소비자의 가비지 컬렉션을 막지 않는다. 정상 종료는 [forget] 으로 바로 지운다.
 */
class BatchDeliveryState {

	private val completed = ThreadLocal<WeakReference<ConsumerRecords<*, *>>?>()
	private val pendingDelay = ThreadLocal<Long?>()
	private val rebalances: MutableMap<Consumer<*, *>, AtomicLong> = Collections.synchronizedMap(WeakHashMap())

	/** 이번 전달의 필수 쓰기가 끝났다. 리스너가 정상 반환하기 직전에만 부른다. */
	fun markCompleted(records: ConsumerRecords<*, *>) {
		completed.set(WeakReference(records))
	}

	fun clearCompleted() {
		completed.remove()
	}

	/** [records] 가 이 스레드에서 완료 표시된 바로 그 객체인가. 확인하면 표시를 지운다 — 한 번만 쓴다. */
	fun takeCompleted(records: ConsumerRecords<*, *>?): Boolean {
		val marked = completed.get()?.get()
		completed.remove()
		return records != null && marked === records
	}

	/** 다음 호출까지의 대기를 정한다. 리스너가 미완료로 던지기 직전에 부른다. */
	fun requestDelay(millis: Long) {
		pendingDelay.set(millis)
	}

	/** 정해 둔 대기를 꺼낸다. 없으면(리스너 밖에서 온 예외) [fallback] 이다. */
	fun takeDelay(fallback: Long): Long {
		val delay = pendingDelay.get()
		pendingDelay.remove()
		return delay ?: fallback
	}

	fun rebalanceCount(consumer: Consumer<*, *>): Long = rebalances[consumer]?.get() ?: 0L

	/** 이 소비자에서 회수·상실이 일어났다. 진행 중인 재시도의 옛 배치는 ack 를 올리지 않는다. */
	fun rebalanced(consumer: Consumer<*, *>) {
		synchronized(rebalances) { rebalances.getOrPut(consumer) { AtomicLong() } }.incrementAndGet()
		completed.remove()
	}

	/** 소비자가 닫혔다. 엔트리를 지운다. 여러 번 불러도 된다. */
	fun forget(consumer: Consumer<*, *>) {
		rebalances.remove(consumer)
	}

	/** 시험용 — 남아 있는 재할당 카운터 엔트리 수. */
	internal fun trackedConsumers(): Int = rebalances.size
}
