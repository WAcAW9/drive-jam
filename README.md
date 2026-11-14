# 드라이브 잼 (Drive Jam)

내 운전이 그대로 음악이 됩니다. 깜빡이는 하이햇, 핸들은 필터, 급정거는 멈칫. 누구와 어떤 기분으로 가는지에 맞춰 그 순간의 곡을 차와 함께 연주하는 Pleos Connect 차량 전용 음악 앱입니다. 플레이리스트를 대신하지 않고, 플레이리스트가 어색한 순간(여럿이 떠드는 드라이브, 비 오는 밤, 지루한 고속도로)을 맡습니다.

2026 현대자동차그룹 차량용 앱 개발 공모전 출품작. 기획서: [docs/proposal.md](docs/proposal.md)

## 지금 동작하는 것 (프로토타입 0.2)

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

- **음악 플레이어처럼**: 곡마다 생성한 앨범 커버, 제목, ⏮ ⏯ ⏭. ⏭는 다른 장르의 새 곡으로 크로스페이드, ⏮는 직전 곡 그대로.
- **"~한 음악으로"**: "비 오는 날 카페 같은 잔잔한 음악으로" → 더 차분한 로파이. 온디바이스 키워드 사전(주차 중 문장 입력, 주행 중엔 버튼 3개). MediaSession 검색에도 연결.
- **장르 6개**: 시티팝, 하우스, 로파이, 신스웨이브, 어쿠스틱, 칠 — 장르별 드럼·베이스·코드 패턴과 음색. 넘긴 장르는 덜 나오도록 학습.
- **MediaBrowserService + MediaSession**: 차량 미디어 시스템, 스티어링 휠 키.
- **무드 컨덕터**: 출발 전 "누구와(혼자/친구들/연인/가족)"와 "기분" 칩 → 기분에 맞춰 시작해서 동승 모드의 목표 분위기로 천천히 이동(동질성 원리). 에너지·템포에는 상한이 있습니다.
- **음악 엔진**: 녹음된 음원 없이 앱 안에서 실시간 합성. 808·909식 드럼 모델링(킥 클릭, 톤+노이즈 스네어, 금속성 하이햇, 크래시), FM 일렉트릭 피아노, 카플러스-스트롱 기타, 톱니파 신스, 장르별 베이스, 곡 시드로 만든 멜로디 모티프. 곡 구성은 인트로 2 · 벌스 4 · 코러스 8 · 브레이크 4 · 코러스 8마디이고, 코러스 앞 필인과 벨로시티 흔들기가 들어갑니다. `EngineRenderTest`가 장르 6개를 PC에서 렌더링해 무음·과부하·속도를 검사하고 `app/build/demo-audio/*.wav`로 남깁니다.
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
  Jam.kt            플레이어 상태: 곡 선택, ⏮ ⏭, 요청 처리, 취향 학습, 오디오 포커스
  Track.kt          장르 프리셋 6개, 곡(장르·키·시드), 곡 고르기
  MusicRequest.kt   "~한 음악으로" 해석기 (순수 함수, 단위 테스트)
  MusicEngine.kt    AudioTrack 실시간 합성 엔진 (장르별 패턴·음색, 곡 크로스페이드)
  JamService.kt     MediaBrowserService + MediaSession
  CoverArt.kt       곡마다 생성하는 앨범 커버, 재생 버튼 아이콘
  MainActivity.kt   재생 화면 (앱 이름을 길게 누르면 개발자 보기: 시각화·DEV 입력)
```
