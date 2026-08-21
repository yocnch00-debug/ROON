# ON ShareLink v0.2 Direct Wi-Fi

개인용 S26 ↔ R8/Android 인터넷 공유 프로젝트.

## v0.2 연결 방식
- S26 Host가 Wi-Fi Direct Group Owner를 생성한다.
- Host 앱 화면에 실제 `DIRECT-...` Wi-Fi 이름과 Android가 만든 Wi-Fi 비밀번호를 표시한다.
- R8 II / 다른 Android는 **일반 Wi-Fi 설정 화면에서 그 SSID와 비밀번호로 직접 연결**한다. v0.1의 앱 내부 P2P 자동-connect 방식은 사용하지 않는다.
- Client 앱은 현재 Wi-Fi의 IPv4 gateway에서 ON ShareLink SOCKS5 서버를 찾고, 사용자 지정 8자리 앱 페어링 코드로 인증한다.
- 인증 후 S26가 실제 `TRANSPORT_CELLULAR` 경로로 `1.1.1.1:443` TCP 연결을 열 수 있는지 확인한 뒤에만 VPN 터널을 시작한다. 따라서 Wi-Fi가 안 붙었는데 '인터넷 연결됨'으로 표시하지 않는다.

## 앱 페어링 코드
- S26 Host에서 숫자 8자리를 사용자가 직접 지정할 수 있다.
- 이 코드는 Wi-Fi 비밀번호와 별개다. Wi-Fi 비밀번호는 Android Wi-Fi Direct가 생성하며 Host 화면에 표시된다.
- R8/다른 Android Client에 같은 8자리 코드를 입력한다.

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
