# EventRelay 외부 메일 계약 v2

앱 0.3.0부터 Gmail 본문은 UTF-8 JSON `eventrelay.message.v2`다. 제목은 `[EventRelay] SMS` 또는 `[EventRelay] NOTIFICATION`을 유지한다. From/To는 승인된 사용자 본인 계정이며 Cc/Bcc는 없다. Gmail 권한은 `gmail.send` 하나다. 라벨과 필터는 Gmail에서 별도로 관리한다.

## 필드

| 필드 | 의미 |
| --- | --- |
| schema | `eventrelay.message.v2` |
| eventId | 기존 이벤트의 안정적인 불투명 식별자. 소비자가 중복을 확인할 때 사용하며 내부 형식을 해석하지 않는다. Gmail의 중복 방지를 보장하지 않는다. |
| sourceType | `SMS` 또는 `NOTIFICATION` |
| appName | 문자이면 `SMS`, 알림이면 수집된 앱 이름. 이름을 조회할 수 없으면 패키지명으로 대체한다. |
| occurredAt | 원래 발생 시각을 UTC ISO-8601로 표현. 밀리초 정밀도를 보존한다. |
| sender | 문자 발신자. 메시지형 알림은 각 messages 항목에 포함한다. |
| title | 비어 있지 않은 원래 알림 제목. |
| body | 원문 본문. 메시지 목록으로 이미 정확히 표현되는 경우 생략할 수 있다. |
| messages | 순서가 보존된 메시지별 text, 필요한 sender/occurredAt. 과거 메시지는 historical=true. |
| additionalText | 주 본문과 다른 확장문·줄 목록·부제·요약·대화명 등 원문 표현의 label/text. |

`sourceRecordId`, 로컬 수집 시각 `observedAt*`, 원본 필드 컨테이너, 전송 상태·시도 횟수·계정 정보·토큰은 메일 JSON에 포함하지 않는다. 메시지에 필요한 발신자와 시각은 보존한다. JSON Schema는 [message-v2.schema.json](message-v2.schema.json)을 참조한다.

## 원문 보존과 정확한 중복 제거

1. 로컬 Room에는 기존 `eventrelay.event.v1` 원본 전체를 변경 없이 보관한다. 메일 투영은 새로운 객체를 만들며 원본을 수정하지 않는다. 식별자·필터·수집 재전달 중복 판단은 기존 계약을 유지한다.
2. 문자는 합쳐진 multipart 본문을 한 번 보낸다. 동일한 originalFields.body 복사본과 partCount를 다시 보내지 않는다. 예상과 다른 원문 표현이면 additionalText에 보존한다.
3. 메시지형 알림은 각 메시지의 정확한 텍스트, 발신자와 시각을 보존한다. 텍스트가 같아도 여러 메시지의 발생은 제거하지 않는다. 현재 메시지 순서를 먼저, 과거 메시지 순서를 이어 보존하고 과거 여부를 표시한다.
4. 확장 본문, 짧은 본문, textLines를 순서대로 확인한다. 메시지 원문 또는 메시지들을 줄바꿈으로 연결한 표현과 **완전히 같을 때만** 중복 표현을 생략한다. 첫 고유 표현은 body, 이후 고유 표현은 additionalText로 보낸다. 줄 목록은 순서·반복·빈 줄을 포함해 줄바꿈으로 연결한다.
5. 공백 제거, Unicode 정규화, 부분 문자열·접두사 추정, 요약, 길이 제한은 하지 않는다. CRLF와 LF가 다르면 별도 원문 표현이다. 포함 관계인 짧은/긴 본문도 둘 다 유지한다.
6. 검색용으로 합친 내부 event.body는 알림의 원본 텍스트 표현이나 메시지가 존재할 때 별도로 보내지 않는다. 원본 표현이 없으면 event.body를 그대로 사용한다. Android에서 수집하는 비어 있지 않은 공개 텍스트 필드는 모두 투영 대상이다. 임의의 내부·미정의 필드는 로컬 원본에 남으며 외부 계약에는 추가하지 않는다.

## 가짜 메일 예시

실제 이메일·발신자·앱·계정이나 실전 전송을 사용하지 않은 예시다.

```text
From: owner@example.test
To: owner@example.test
Subject: [EventRelay] SMS
Content-Type: text/plain; charset=UTF-8

{
  "schema": "eventrelay.message.v2",
  "eventId": "er1_fake_sms_01",
  "sourceType": "SMS",
  "appName": "SMS",
  "occurredAt": "2023-11-14T22:13:20Z",
  "sender": "FAKE-SENDER",
  "body": "가짜 첫 부분\n가짜 두 번째 부분"
}
```

메시지형 알림의 `text/body`가 아래 메시지 텍스트와 같으면 별도 body가 없다. 같은 텍스트라도 두 발생을 보존한다.

```json
{
  "schema": "eventrelay.message.v2",
  "eventId": "er1_fake_notification_01",
  "sourceType": "NOTIFICATION",
  "appName": "가짜 메신저",
  "occurredAt": "2023-11-14T22:13:20.002Z",
  "title": "가짜 대화",
  "messages": [
    {"text": "같은 메시지", "sender": "가짜 A", "occurredAt": "2023-11-14T22:13:20Z"},
    {"text": "같은 메시지", "sender": "가짜 B", "occurredAt": "2023-11-14T22:13:20.001Z"}
  ]
}
```

## 회귀 검사

MailPayloadTest와 GmailApiGatewayTest는 정확한 중복 표현 제거, 반복 메시지·과거 메시지, 구별되는 공백/줄바꿈/부분 본문, 긴 Unicode 원문, 내부 정보 제외, `[EventRelay]` 제목과 send-only/본인 수신자 계약을 가짜 데이터·가짜 transport로 검증한다. 실제 메일을 보내지 않는다.
