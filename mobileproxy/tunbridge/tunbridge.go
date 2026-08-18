package tunbridge

import (
    "fmt"
    "sync"
    "syscall"

    "github.com/xjasonlyu/tun2socks/v2/engine"
)

var mu sync.Mutex
var running bool
var ownedFD = -1

// Start runs tun2socks on a packet-oriented file descriptor (AF_UNIX SOCK_DGRAM
// endpoint from Android). The peer endpoint is owned by the Java policy router.
func Start(fd int64, proxyURL string, mtu int64) error {
    mu.Lock()
    defer mu.Unlock()
    if fd < 0 { return fmt.Errorf("invalid fd") }
    if proxyURL == "" { return fmt.Errorf("empty proxy url") }
    if mtu <= 0 { mtu = 1280 }
    if running {
        func(){ defer func(){ _ = recover() }(); engine.Stop() }()
        running = false
    }
    if ownedFD >= 0 { _ = syscall.Close(ownedFD); ownedFD = -1 }
    dup, err := syscall.Dup(int(fd))
    if err != nil { return fmt.Errorf("dup fd: %w", err) }
    key := &engine.Key{
        Proxy: proxyURL,
        Device: fmt.Sprintf("fd://%d", dup),
        MTU: int(mtu),
    }
    engine.Insert(key)
    engine.Start()
    ownedFD = dup
    running = true
    return nil
}

func Stop() {
    mu.Lock()
    defer mu.Unlock()
    if running {
        func(){ defer func(){ _ = recover() }(); engine.Stop() }()
        running = false
    }
    if ownedFD >= 0 { _ = syscall.Close(ownedFD); ownedFD = -1 }
}
