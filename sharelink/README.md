# ON ShareLink v0.1 clean-room

개인용 S26 ↔ R8/Android 인터넷 공유 프로젝트.

## 목표
- 외부 서버, VPS, 상용 프록시, 하드코딩 공인 IP 없음.
- S26가 Wi-Fi Direct Group Owner가 되고 R8/다른 Android 기기가 클라이언트로 연결.
- S26 Host는 TCP/UDP SOCKS5 요청을 S26의 `TRANSPORT_CELLULAR` Network에 강제로 바인딩해 LTE/5G로 내보냄.
- Client는 Android `VpnService` TUN을 통해 일반 인터넷만 S26로 전달.
- 10/8, 172.16/12, 192.168/16, link-local, multicast 등 로컬 목적지는 VPN route에서 제외해 RoonLink/HiBy Roon Ready/SMB가 물리 Wi-Fi로 직통.
- 한 S26 Host에 여러 Client가 동시에 접속 가능. 여자친구 Android 폰도 같은 Client APK 사용 가능.

## Clean-room 원칙
NetShare APK의 코드/리소스/문자열은 포함하거나 복제하지 않는다. 동작 요구사항과 Android 공개 API만을 기준으로 새로 구현한다.

Client의 TUN↔SOCKS5 변환에는 독립 오픈소스 `heiher/hev-socks5-tunnel`을 고정 커밋으로 빌드해 사용한다. 해당 프로젝트는 MIT License이며 THIRD_PARTY_NOTICES.md를 따른다.

## 현재 v0.1 기능
- Host Wi-Fi Direct group 자동 생성/복구
- DNS-SD `_onsharelink._tcp` 광고/탐색
- Client 자동 발견/접속
- Host 다중 TCP CONNECT
- Host 다중 UDP ASSOCIATE
- Host outbound CELLULAR 강제 binding
- Client VpnService + public IPv4 split-routes
- 로컬/private/multicast route 우회
- 부팅 후 자동 복구(사용자가 자동 연결을 켠 경우)
- Host/Client foreground 상태 표시 및 트래픽 통계

PC/Roon Server는 ShareLink 인터넷 공유 자체에는 관여하지 않으며 기존 ON RoonLink와 별도 유지한다.
