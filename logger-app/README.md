# ON R8 Logcat Recorder v1.0

HiBy R8 II에서 앱 실행 이후의 전체 Android logcat을 TXT로 기록하는 진단용 독립 앱.

## 사용
1. APK 설치
2. 최초 1회 PC에서 권한 부여:
   `adb shell pm grant com.onr8.logcatrecorder android.permission.READ_LOGS`
3. 앱 실행: 자동으로 `logcat -b all -v threadtime -T 1` 기록 시작
4. HiBy Music / Roon Ready / USB DAC 테스트
5. 앱으로 돌아와 `기록 종료 + TXT 저장`
6. 결과 TXT: `Download/ON_Logcat/ON_R8_LOG_YYYYMMDD_HHMMSS.txt`

기록 중 앱 화면을 벗어나도 foreground service와 partial wakelock으로 계속 기록한다. 최대 파일 크기는 안전을 위해 300MB로 제한한다.

이 앱은 기존 ON RoonLink / NetShare / RAAT 앱과 다른 패키지(`com.onr8.logcatrecorder`)를 사용하며 기존 통신 로직을 수정하지 않는다.
