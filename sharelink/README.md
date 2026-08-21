# ON ShareLink v1.0 REBUILD

개인용 Galaxy S26 ↔ HiBy R8 II / Android 인터넷 공유 프로젝트.

## 왜 v1.0으로 다시 설계했는가
2026-08-22 R8 실기 로그에서 v0.x의 두 문제가 분리되어 확인됐다.

1. `DIRECT-ON-ShareLink` SSID는 실제 스캔되지만 v0.3의 `WifiNetworkSuggestion`만으로는 R8이 실제 저장 Wi-Fi처럼 안정적으로 자동 접속하지 않았다.
2. 사용자가 수동으로 Wi-Fi를 연결해 `wlan0=192.168.49.x`가 된 뒤에도 S26 `192.168.49.1:51950` SOCKS probe가 EOF/ECONNREFUSED로 끝났다. Host가 SOCKS target을 읽기 전에 `CELLULAR Network`를 무조건 요구하던 race와 listener lifecycle을 함께 수정해야 했다.

따라서 v1.0은 v0.x에 덧칠하지 않고 Wi-Fi provisioning, Host lifecycle/UI, SOCKS relay를 다시 정리한다.

## Host (Galaxy S26)
- 화면에 `공유망 Wi-Fi ON/OFF` 스위치 제공.
- 고정 SSID: `DIRECT-ON-ShareLink`.
- 사용자가 정한 숫자 8자리 코드를 Wi-Fi WPA2 passphrase와 ShareLink SOCKS5 인증 비밀번호에 함께 사용.
- OFF 시 SOCKS server를 닫고 Wi-Fi Direct group도 실제 제거.
- ON 시 group owner를 생성하고 부팅 후 enabled 상태면 자동 복구.
- `WifiP2pGroup.getClientList()` 기반 실제 접속 기기 수/이름/MAC을 Host 화면에 표시.
- Internet outbound는 `TRANSPORT_CELLULAR` Network에 강제로 바인딩해 S26 LTE/5G로 보냄.
- TCP CONNECT + UDP ASSOCIATE 지원.
- CELLULAR Network가 순간적으로 unavailable이어도 SOCKS 인증 소켓을 EOF로 끊지 않고 정상 SOCKS failure reply를 반환하여 Client가 상태를 구분하고 재시도할 수 있게 함.
- TCP 51950 listener는 wildcard로 유지해 Wi-Fi Direct 주소 전환에 덜 민감하게 했고, 3초 watchdog이 listener/group 상태를 감시해 필요 시 재기동.
- S26에서 `S26 Host 진단 로그 복사` 가능.

## Client (R8 II / Android)
- R8 II Android 12에서 NetShare처럼 실제 저장 Wi-Fi에 가까운 동작을 위해 sideload Client의 `targetSdk`를 28로 설정.
- 최초 등록 시 먼저 `WifiConfiguration → addNetwork/updateNetwork → saveConfiguration → enableNetwork → reconnect`를 직접 시도한다.
- 이 legacy saved-network 경로를 기기 정책이 막을 경우에만 Android 11+ `ACTION_WIFI_ADD_NETWORKS` 저장 화면으로 fallback한다.
- v0.3의 `WifiNetworkSuggestion` 상태는 v1 최초 실행에서 제거해 duplicate suggestion/saved-network 충돌을 피한다.
- 연결이 끊겨 있으면 저장된 `DIRECT-ON-ShareLink`에 주기적으로 `enableNetwork + reconnect`를 재시도한다.
- 실제 `wlan0`에 `192.168.49.x` 주소가 생겨야 2단계로 넘어감.
- `192.168.49.1:51950`에서 SOCKS5 인증 성공 + S26가 cellular로 `1.1.1.1:443` CONNECT 성공을 반환해야 VPN을 시작.
- 단계 표시: 1/4 Wi-Fi → 2/4 Host → 3/4 Cellular → 4/4 VPN.
- 진단 로그 `sharelink-client.log` + HEV 로그 `hev-sharelink.log` 유지.

## Roon 보호
- Client VPN은 public IPv4만 라우팅한다.
- `10/8`, `172.16/12`, `192.168/16`, link-local, multicast 등 로컬 목적지는 VPN 밖에 둔다.
- 따라서 ON RoonLink R8 Sidecar → S26 `192.168.49.1:51921`, HiBy Roon Ready local endpoint, SMB는 물리 Wi-Fi 직통을 유지한다.
- 기존 PC RoonLink / S26 RoonLink / R8 Sidecar 코드는 이 ShareLink 프로젝트에서 수정하지 않는다.

## 다중 Client
- Host TCP server는 connection별 worker 구조이고 UDP association도 session별로 분리한다.
- Wi-Fi Direct group의 실제 client list를 표시한다.
- R8 II와 다른 Android 폰이 같은 Client APK 및 같은 8자리 코드를 사용할 수 있다.

## Clean-room 원칙
NetShare APK의 소스/리소스/문자열을 복제하지 않는다. 실기에서 확인된 Android 네트워크 동작과 공개 Android API를 기준으로 새 코드를 구현한다.

Client TUN↔SOCKS5 변환은 독립 오픈소스 `heiher/hev-socks5-tunnel` commit `0428c4ebb0df933ebac8e507832f252ef7da47f1`을 소스 빌드하며 MIT License/THIRD_PARTY_NOTICES를 따른다.
