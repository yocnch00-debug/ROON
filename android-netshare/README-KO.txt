ON RoonLink Native v1 alpha3 - NetShare 호환판

목적
- 기존 alpha3 DIRECT 완성본은 그대로 유지합니다.
- 이 버전은 별도 패키지 ID(com.onroonlink.nativev1.netshare)라 원본 앱과 동시에 설치할 수 있습니다.
- PC Host 프로토콜은 기존 alpha3 DIRECT와 동일하므로 Host EXE는 수정하지 않습니다.

R8 II 사용 순서
1. S26 Ultra에서 NetShare의 Wi-Fi hotspot을 시작합니다.
2. R8 II를 NetShare Wi-Fi에 연결합니다.
3. R8 II의 NetShare 앱에서 CONNECT(VPN)는 누르지 않습니다.
4. ON RoonLink NS 앱을 실행합니다.
5. 장치 DAP, 기존 PC Host와 같은 4~8자리 숫자 비밀번호를 입력합니다.
6. NetShare 프록시는 기본값 192.168.49.1 / 8282를 그대로 둡니다.
7. 'NetShare 경유 연결'을 누르고 Android VPN 권한은 ON RoonLink NS에만 허용합니다.

동작 구조
R8 ON RoonLink NS(VpnService) -> protect된 transport socket -> NetShare HTTP proxy 192.168.49.1:8282 -> CONNECT 121.133.225.83:51900 -> TLS 1.3 -> PC Host

중요
- NetShare CONNECT(VPN)과 이 앱을 동시에 켜는 방식이 아닙니다.
- NetShare는 S26에서 hotspot/proxy 역할만 하고 R8의 VPN 자리는 ON RoonLink NS 하나만 씁니다.
- NetShare 공식 Android Proxy 문서의 기본 프록시 주소/포트(192.168.49.1:8282)를 사용합니다.
- 프록시가 CONNECT 51900을 허용하지 않는 환경이면 앱 알림에 'NetShare CONNECT 거부 HTTP xxx'가 표시됩니다. 이 경우 PC Host 포트/전송 방식을 다음 단계에서 조정해야 합니다.
