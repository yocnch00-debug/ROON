# ON ShareLink v0.3 ONE-TAP

개인용 S26 ↔ R8/Android 인터넷 공유 프로젝트.

## v0.3 한방 연결 방식
- S26 Host는 고정 Wi-Fi Direct 그룹 `DIRECT-ON-ShareLink`를 생성한다.
- 사용자가 S26 Host에서 지정한 **숫자 8자리 연결 코드**를 Wi-Fi Direct WPA2 passphrase와 ShareLink SOCKS5 인증 비밀번호에 같이 사용한다.
- R8 II / 다른 Android Client에는 같은 8자리 코드만 한 번 입력한다.
- Android 10+ Client는 `WifiNetworkSuggestion`으로 `DIRECT-ON-ShareLink`를 자동 연결 대상으로 등록한다. Android 보안상 최초 앱 Wi-Fi 제안 허용은 한 번 필요할 수 있으며 이후 auto-join을 사용한다.
- Client는 실제 Wi-Fi Network에서 S26 gateway의 ON ShareLink SOCKS5 인증과 S26 `TRANSPORT_CELLULAR` outbound 연결을 검증한 뒤에만 VPN을 시작한다.
- 따라서 앱 표시만 연결되고 실제 Wi-Fi/인터넷이 죽어 있는 v0.1식 false-positive를 허용하지 않는다.
- 사용자가 Wi-Fi 설정 화면에서 SSID/비밀번호를 매번 수동 입력하는 v0.2 흐름은 제거했다.

## 네트워크 목표
- 외부 서버, VPS, 상용 프록시, 하드코딩 공인 IP 없음.
- S26 Host는 TCP/UDP SOCKS5 요청을 S26의 `TRANSPORT_CELLULAR` Network에 강제로 바인딩해 LTE/5G로 내보낸다.
- Client는 Android `VpnService` TUN을 통해 일반 인터넷만 S26로 전달한다.
- 10/8, 172.16/12, 192.168/16, link-local, multicast 등 로컬 목적지는 VPN route에서 제외해 RoonLink/HiBy Roon Ready/SMB가 물리 Wi-Fi로 직통한다.
- 한 S26 Host에 여러 Client가 동시에 접속 가능하며 R8 II와 다른 Android 폰이 같은 Client APK를 사용한다.

## Clean-room 원칙
NetShare APK의 코드/리소스/문자열은 포함하거나 복제하지 않는다. 사용자가 원하는 연결 UX와 Android 공개 API 동작을 기준으로 새로 구현한다.

Client의 TUN↔SOCKS5 변환에는 독립 오픈소스 `heiher/hev-socks5-tunnel`을 고정 커밋으로 빌드해 사용한다. 해당 프로젝트는 MIT License이며 THIRD_PARTY_NOTICES.md를 따른다.

PC/Roon Server는 ShareLink 인터넷 공유 자체에는 관여하지 않으며 기존 ON RoonLink와 별도 유지한다.
