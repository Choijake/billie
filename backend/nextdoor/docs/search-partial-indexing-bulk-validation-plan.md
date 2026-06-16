# 부분 색인 Bulk 설정 검증 계획

## 목적

현재 부분 색인 파이프라인은 worker에서 SQS 메시지를 메모리 버퍼에 모은 뒤 Elasticsearch Bulk API로 flush한다.

현재 기본값은 다음과 같다.

```yaml
search:
  bulk:
    maxConcurrency: 2
    sliceMaxActions: 300
    scheduleMs: 1000
```

이 문서의 목적은 `scheduleMs=1000ms`가 단순 경험값이 아니라, 특정 가정 환경에서 SLO를 만족하는 flush 주기인지 검증하기 위한 테스트 조건과 판정 기준을 남기는 것이다. `sliceMaxActions=300`은 현재 가정 부하에서는 도달하지 않는 안전 상한으로 보고, 비교 실험의 주축에서 제외한다.

중요한 전제는 이 값이 Elasticsearch의 일반적인 최적값이 아니라는 점이다. ES bulk 크기는 문서 크기, shard 수, heap, CPU, refresh interval, I/O 성능, 동시 검색 부하에 따라 달라진다. 따라서 이 테스트는 "아래 가정 환경에서는 1초 flush 주기가 합리적이다"를 증명하는 용도다.

## 테스트 대상 파이프라인

이 테스트는 SQS FIFO 큐 통합과 Outbox 발행 병렬화가 적용된 이후의 파이프라인을 대상으로 한다.

```text
Outbox
  -> 단일 post-index.fifo
  -> SqsPostIndexListener
  -> PostIndexConsumer (단일 buffer)
  -> UPSERT/DELETE 혼합 dedup
  -> ES bulk
  -> manual ACK
```

기존 분리 파이프라인인 `SqsUpsertListener + SqsDeleteListener` 구조는 테스트 대상이 아니다.

핵심 flush 조건은 다음 두 가지다.

```java
buffer.add(new Pending(...));

if (buffer.size() >= sliceMaxActions) {
    flush();
}
```

```java
@Scheduled(fixedDelayString = "${search.bulk.schedule-ms:1000}")
public void periodicFlush() {
    periodicFlushInternal();
}
```

따라서 검증해야 하는 값은 다음이다.

| 설정 | 현재값 | 의미 |
|---|---:|---|
| `sliceMaxActions` | 300 | 정상 부하에서는 도달하지 않는 예외적 burst 안전 상한 |
| `scheduleMs` | 1000ms | 버퍼 대기 시간 상한 |
| `maxConcurrency` | 2 | 동시에 실행할 ES bulk slice 수 |

## 가정 환경

아래 환경을 기준 가정으로 둔다.

| 항목 | 값 | 근거 |
|---|---:|---|
| 게시글 수 | 100만 건 | 서비스 규모 가정 |
| DAU | 10만 | 서비스 규모 가정 |
| 검색 QPS | 100 이하 | DAU 10만 기준, 피크 시간대 집중 감안 |
| 게시글 변경 events/sec | steady 5~10, peak 30~50 | DAU 10만 중 게시글 CUD 비율은 검색 대비 낮음 |
| PostDocument 크기 | 사전 측정 필요 | 테스트 전 실제 직렬화 크기 p50/p95 확인 |
| ES | 8.x 단일 노드, heap 4GB | flush 설정 변수 격리를 위해 GC 병목 제거 |
| refresh_interval | 1s | 기본값 유지 |
| worker | 1 JVM, `maxConcurrency=2` | 현재 코드 기본값 |

## 게시글 변경 부하 산출 근거

DAU 10만에서 게시글 생성/수정/삭제는 전체 트래픽의 일부다. C2C 대여 플랫폼 특성상 대부분의 사용자 행동은 검색과 조회이고, 게시글 CUD는 소수 사용자가 수행한다.

```text
DAU 10만
-> 일일 게시글 CUD: 보수적으로 DAU의 5% = 5,000건/일
-> 평균: 5,000 / 86,400 ~= 0.06 events/sec
-> 피크 집중 (전체의 30%가 2시간에 집중): 1,500 / 7,200 ~= 0.2 events/sec

이 수준에서는 flush 설정이 병목이 될 수 없다.
따라서 테스트 부하는 "현재 규모"가 아니라 "이 설정이 한계에 도달하는 지점"을 찾기 위해 인위적으로 높인 값을 사용한다.

steady: 10 events/sec (실제 피크의 50배, 설정 민감도 확인용)
peak: 50 events/sec (실제 피크의 250배, 부하 한계 확인용)
```

## 사전 측정

테스트 실행 전에 `PostDocument` 직렬화 크기를 먼저 측정하고 기록한다.

실제 게시글 데이터를 `PostDocument`로 변환한 뒤 JSON 직렬화하여 크기 분포를 확인한다.

```text
측정 방법:
1. 게시글 1,000건 샘플링
2. PostDocument 변환
3. ObjectMapper.writeValueAsBytes()로 byte 수 기록
4. p50 / p95 / p99 산출

기록 예시:
  p50: ___KB
  p95: ___KB
  p99: ___KB
  max: ___KB
```

이 값에 따라 300건 bulk의 예상 payload 크기를 계산한다.

```text
300건 x p50 크기 = 예상 bulk payload (일반)
300건 x p95 크기 = 예상 bulk payload (최악)

ES 권장: 단일 bulk request 5~15MB 이하
-> 300건 x p95가 15MB를 초과하면 sliceMaxActions를 줄여야 함
```

## `sliceMaxActions=300` 해석

DAU 10만 기준 피크 변경 부하는 약 0.2 events/sec이다. 50배 인위 부하인 10 events/sec에서도 1초 안에 buffer가 300건에 도달하지 않는다.

peak 시나리오인 50 events/sec에서도 `scheduleMs=1000`이면 1초 안에 buffer가 최대 약 50건까지만 쌓인다. 따라서 `sliceMaxActions=100`, `300`, `500` 비교는 세 설정 모두 timer flush로 동작해 동일한 결과가 나올 가능성이 높다.

이 테스트에서 실제 flush는 `scheduleMs` timer에 의해 발생하며, `sliceMaxActions=300`은 예외적 burst 상황의 안전 상한으로 기능한다. `300건 x p95`가 ES 권장 bulk payload 범위를 넘는지만 사전 측정에서 확인한다.

## 비교 설정

비교 설정은 `sliceMaxActions=300`, `maxConcurrency=2`를 고정하고 `scheduleMs`만 변경한다.

### Round 0: 검색 baseline

색인 부하 없이 검색 QPS 50과 QPS 100의 p95를 먼저 측정한다. 이 값은 색인 부하 중 검색 p95가 얼마나 악화되는지 판단하는 기준선이다.

| 설정 | 목적 |
|---|---|
| 검색 QPS 50 | steady 시나리오의 검색 baseline |
| 검색 QPS 100 | peak 시나리오의 검색 baseline |

### Round 1: `scheduleMs` 변경

| 설정 | 목적 |
|---|---|
| `scheduleMs=500` | 더 짧은 latency cap |
| `scheduleMs=1000` | 현재 기본값 |
| `scheduleMs=2000` | 더 긴 latency cap의 e2e 영향 |

본 테스트는 위 3개 설정을 steady/peak 시나리오에 적용한다.

## 부하 시나리오

| 시나리오 | 부하 | 시간 | 목적 |
|---|---:|---:|---|
| steady | 10 events/sec + 검색 QPS 50 | 10분 | 안정 상태 색인 지연, 검색 영향 |
| peak | 50 events/sec + 검색 QPS 100 | 5분 | 부하 시 backlog 증가 여부, 회복 시간 |

총 테스트 횟수는 다음과 같다.

```text
baseline 2회 + 본 테스트 3개 설정 x 2개 시나리오 = 8회
```

`burst(1초 500건)`와 `hot update`는 제외한다.

```text
burst 제외 이유:
DAU 10만 규모에서 1초에 500건 게시글 변경은 발생하지 않는다.
pending queue drain도 50 events/sec 수준이면 peak 시나리오로 충분히 커버한다.

hot update 제외 이유:
dedup 효과는 steady/peak에서 같은 postId 이벤트가 자연스럽게 발생하면 부수적으로 확인 가능하다.
별도 시나리오로 분리하지 않는다.
```

## 부하 생성 방법

이 테스트의 파이프라인 latency는 `outbox_event.created_at`부터 측정한다. 따라서 부하 생성은 반드시 Outbox 구간을 통과해야 한다.

기본 방식은 직접 `outbox_event`를 INSERT하는 것이다. 이 방식은 API, 비즈니스 트랜잭션, Post 저장 비용을 제외하고 Outbox polling 이후의 색인 파이프라인을 안정적으로 압박할 수 있다.

API 호출 방식은 `PostCommandService -> postRepository.save() -> postIndexPort.requestUpsert()`까지 함께 검증해야 할 때 보조 방식으로 사용한다. 이 경우 결과 기록에 API 호출 방식으로 생성했다고 명시한다.

SQS 직접 발행은 사용하지 않는다. SQS 직접 발행은 Outbox polling 구간을 건너뛰므로 `outbox_event.created_at -> ES bulk 완료` latency를 측정할 수 없다.

## e2e latency 측정 방법

측정 구간을 두 단계로 분리한다.

```text
구간 1: 파이프라인 latency (직접 측정)
  시작: outbox_event.created_at
  끝: ES bulk response 수신 시각
  -> worker 코드에서 bulk 완료 시 System.currentTimeMillis() - event.createdAt 기록

구간 2: 검색 반영 지연 (간접 추정)
  refresh_interval=1s이므로 구간 1 + 최대 1초
  별도 polling 측정 불필요, 설정값으로 추정
```

이 테스트에서는 e2e latency를 구간 1, 즉 파이프라인 latency로 정의한다. refresh 지연은 ES 설정에 의존하므로 flush 설정 검증과 분리한다.

## SLO 기준값과 산출 근거

| 항목 | 기준 | 근거 |
|---|---:|---|
| 파이프라인 latency p95 | <= 3초 | outbox polling 2초 + bulk 처리 약 1초. 3초면 1회 polling 주기 + 여유 |
| 파이프라인 latency p99 | <= 5초 | polling 2회분 + bulk 재시도 여유 |
| ES bulk latency p95 | <= 500ms | bulk 자체가 1초를 넘으면 `scheduleMs=1000`과 충돌 |
| ES write rejected | 0건 | rejected 발생 시 설정 과대 |
| 검색 latency p95 | 색인 미실행 시 대비 20% 이내 악화 | 색인이 검색을 해치지 않는다는 근거 |
| 검색 error rate | 0건 | 색인 부하 중 검색 오류가 없어야 함 |

### 3초 산출

```text
outbox polling: 최대 2초 대기 (fixedDelay=2000ms)
SQS 전달: ~100ms (LocalStack 기준)
buffer 대기: 최대 1초 (scheduleMs=1000)
bulk 처리: ~500ms (p95 기준)
합계: ~3.6초

-> p95 <= 3초는 "대부분의 이벤트가 1회 polling 주기 안에 처리된다"는 의미
-> p99 <= 5초는 "2회 polling 주기를 넘기지 않는다"는 의미
```

## 측정 지표

### 필수 수집

```text
파이프라인 latency: p50 / p95 / p99 (outbox created_at -> bulk 완료)
ES bulk latency: p50 / p95 / p99
ES bulk request 건수 (설정 간 호출 빈도 비교)
ES write rejected count
version_conflict_engine_exception count
검색 latency: p50 / p95 / p99 (색인 부하 중)
outbox published=false count 추이 (backlog)
```

### 선택 수집

```text
ES CPU / heap / GC pause (이상 징후 확인용)
SQS redelivery count
buffer dedup률 (dedup된 건수 / 전체 buffer 건수)
```

## 실행 기록 템플릿

| 설정 | 시나리오 | events/sec | 검색 QPS | 파이프라인 p95 | 파이프라인 p99 | bulk p95 | rejected | conflict | 검색 p95 | 판정 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| baseline | 검색 only | 0 | 50 | - | - | - | - | - | ___ | baseline |
| baseline | 검색 only | 0 | 100 | - | - | - | - | - | ___ | baseline |
| 300/500/c2 | steady | 10 | 50 | | | | | | | |
| 300/500/c2 | peak | 50 | 100 | | | | | | | |
| 300/1000/c2 | steady | 10 | 50 | | | | | | | |
| 300/1000/c2 | peak | 50 | 100 | | | | | | | |
| 300/2000/c2 | steady | 10 | 50 | | | | | | | |
| 300/2000/c2 | peak | 50 | 100 | | | | | | | |

## 기대 결론 형태

테스트 후 결론은 다음 형식으로 작성한다.

```text
본 테스트는 단일 노드 ES 8.x (heap 4GB), 게시글 100만 건,
refresh_interval=1s, 부분 색인 peak 50 events/sec,
문서 p95 ___KB라는 가정에서 수행했다.

이 환경에서 실제 flush는 scheduleMs timer에 의해 발생한다.
sliceMaxActions=300은 buffer 안전 상한이며 정상 운영 시 도달하지 않는다.

scheduleMs=1000은 파이프라인 p95 3초 이내, 검색 p95 악화 20% 이내를 만족했다.
500ms는 bulk 호출 빈도가 증가하고, 2000ms는 파이프라인 p95가 ___로 악화되었다.

따라서 scheduleMs=1000은 본 시스템 가정 환경에서 SLO를 만족하는 검증된 기본값이다.
```

## 추가 검증 후보

이 테스트와 별개로 파이프라인에서 추가 검증이 필요한 항목이다.

| 항목 | 이유 |
|---|---|
| Redis pending queue 유실 가능성 | 전체 색인 중 Redis에 pending 저장 후 SQS ACK하는 구조라 Redis 장애나 drain 중 서버 사망 시 유실 가능 |
| pending queue drain 원자성 | 현재 `LRANGE` 후 `LTRIM`으로 먼저 제거하므로 ES 반영 전 서버가 죽으면 유실 가능 |
| outbox published row cleanup | 현재 SQS 발행 성공 후 `published=1`로만 마킹하고 운영 cleanup 로직이 없음 |
| outbox -> SQS 병렬 발행 검증 | postId 그룹 내 순차와 그룹 간 병렬이 실제 처리량 개선으로 이어지는지 별도 측정 필요 |
| 단일 FIFO queue 순서 검증 | 같은 postId의 UPSERT/DELETE가 동일 message group에서 순서 보장되는지 확인 필요 |
| DLQ / visibility timeout 설정 | ES 영구 실패 메시지와 느린 bulk 처리 시 재전달/블로킹 위험 |
| 부분 색인 consumer의 byte 기준 slicing | 현재 부분 색인은 건수 기준 slicing만 사용하므로 문서 크기가 커질 때 bulk가 과대해질 수 있음 |
