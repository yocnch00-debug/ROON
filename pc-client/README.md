# ON ShareLink PC Client v0.1

Windows 10/11 x64 client for the existing ON ShareLink S26 Host v1.6 PERFORMANCE.

## Goal

Laptop -> DIRECT-ON-ShareLink -> S26 ShareLink Host 192.168.49.1:51950 -> S26 LTE/5G -> Internet.

No change is made to the S26 Host, R8 Client, R8 Sidecar, PC RoonLink, or S26 RoonLink.

## Behavior

- Runs elevated because Windows TUN/routes require administrator rights.
- Can create/update a saved WPA2 profile for `DIRECT-ON-ShareLink` using the same 8-digit pairing code and request Windows to connect.
- Verifies SOCKS5 username/password authentication against `192.168.49.1:51950` before creating the tunnel.
- Uses HEV Socks5 Tunnel + official Wintun signed DLL for a real Windows TUN path; this is not a browser-only proxy.
- Routes only public IPv4 prefixes through ShareLink. Private/local networks, including `192.168.49.0/24`, remain outside the tunnel so the S26 gateway cannot loop into itself.
- TCP and UDP are carried through the existing S26 SOCKS5 server.
- DNS on the temporary Wintun adapter uses public resolvers that themselves travel through the ShareLink public routes.
- On normal exit the public routes and tunnel process are removed.

## First hardware test

1. Keep S26 ShareLink Host v1.6 PERFORMANCE ON.
2. Run `ON-ShareLink-PC-Client.exe` as normal; its manifest will request administrator elevation.
3. Enter the same 8-digit S26 code and press Connect.
4. If Windows refuses automatic Wi-Fi provisioning on a particular WLAN driver, connect to `DIRECT-ON-ShareLink` once from the Windows Wi-Fi menu, then press Connect again.
5. Verify normal web browsing and UDP-capable apps.

This v0.1 is intentionally isolated from the already hardware-proven Android golden set.
