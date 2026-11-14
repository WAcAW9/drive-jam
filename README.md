# 드라이브 잼 (Drive Jam)

"무슨 노래 틀지?" — 차가 정합니다. 누구와, 어디로, 어떤 기분으로 가는지 읽고, 깜빡이·핸들·브레이크까지 악기 삼아 이 드라이브만의 음악을 실시간으로 만드는 Pleos Connect 차량 전용 앱입니다.

2026 현대자동차그룹 차량용 앱 개발 공모전 출품작. 기획서: [docs/proposal.md](docs/proposal.md)

## 지금 동작하는 것 (프로토타입 0.1)

| 차량 신호 (Vehicle SDK 2.3.2) | 음악 |
|---|---|
| 방향지시등 `TurnSignal` | 켜진 쪽으로 치우친 하이햇 16비트 |
| 조향각 `SteeringWheel` | 패드 필터가 열리고, 돈 방향으로 소리가 이동 |
| 브레이크 `Brake` (on/off) | 밟는 동안 메아리가 길어짐 |
| 브레이크 + 차속 급감 | 레코드를 긁듯 0.6초 '멈칫' |
| 와이퍼 `Wiper` | 빗소리·빗방울 레이어 (간헐/연속) |
| 전조등 `Light` (로우빔) | 밤 → 에너지 낮춤 |
| 주행 모드 `DrivingMode` | 에코 −, 스포츠 + 에너지 |
| 트립 주행거리 `Odometer` | Δ거리/Δ시간으로 차속 계산 → 템포 ±8 BPM (상한 128) |
| 기어 `Gear` | 상태 표시 |

- **무드 컨덕터**: 출발 전 "누구와(혼자/친구들/연인/가족)"와 "기분" 칩 → 기분에 맞춰 시작해서 동승 모드의 목표 분위기로 천천히 이동(동질성 원리). 에너지·템포에는 상한이 있습니다.
- **음악 엔진**: 녹음된 음원 없이 앱 안에서 실시간 합성(16스텝 시퀀서 + 킥·스네어·하이햇·베이스·패드·빗소리 + 핑퐁 딜레이). 조성·템포·레이어 수는 마디 경계에서만 바뀝니다.
- **DEV 패널**: Virtual Console에 차속 카드가 없어서 차속은 슬라이더로 넣습니다. Vehicle SDK가 없는 일반 에뮬레이터에서도 모든 신호를 버튼으로 흉내 낼 수 있습니다.

설계 문서: [docs/design-music-engine.md](docs/design-music-engine.md) · SDK 매핑: [docs/sdk-mapping.md](docs/sdk-mapping.md) · 결정 기록: [docs/decisions.md](docs/decisions.md)

## 빌드와 실행

요구 사항: JDK 17, Android SDK 35, Pleos Connect Emulator v2.1.0 (API 34, Automotive).

```bash
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb forward tcp:8088 tcp:8088   # Virtual Console: http://127.0.0.1:8088/
```

앱에서 칩을 고르고 **잼 시작** → Virtual Console에서 깜빡이·핸들·브레이크·와이퍼·라이트를 조작하면 음악이 바로 반응합니다.

## 구조

```
app/src/main/java/com/drivejam/
  SignalHub.kt      Vehicle SDK 리스너 → DriveState (SDK를 만지는 유일한 곳)
  MoodConductor.kt  무드 좌표, 동승 모드 목표, 안심 범위, 무드 → 음악 파라미터 (순수 함수)
  MusicEngine.kt    AudioTrack 실시간 합성 엔진
  MainActivity.kt   칩 UI, 오디오 포커스, DEV 패널
```
