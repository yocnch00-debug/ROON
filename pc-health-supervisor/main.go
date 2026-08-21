package main

import (
    "bufio"
    "crypto/sha256"
    "encoding/hex"
    "fmt"
    "io"
    "os"
    "os/exec"
    "path/filepath"
    "strings"
    "sync"
    "syscall"
    "time"
)

const (
    targetPort = ":51920"
    goldenEngineSHA = "400e9bad1b219e8a5f2fdbb00f8790309c7ac6a9d0a425792eccf051003f6266"
)

var logMu sync.Mutex

func main() {
    local := os.Getenv("LOCALAPPDATA")
    if local == "" { return }
    base := filepath.Join(local, "ON RoonLink", "Integrated v3.4")
    logPath := filepath.Join(local, "ON RoonLink", "health-supervisor.log")
    _ = os.MkdirAll(filepath.Dir(logPath), 0755)
    logf(logPath, "START · passive 51920 health supervisor · golden data path unchanged")

    misses := 0
    cooldownUntil := time.Time{}
    for {
        listening, pid := listener51920()
        if listening {
            if misses > 0 { logf(logPath, "RECOVERED · 51920 LISTENING pid=%s", pid) }
            misses = 0
            time.Sleep(10 * time.Second)
            continue
        }

        misses++
        if misses == 1 { logf(logPath, "WARN · 51920 listener missing") }
        if misses < 3 || time.Now().Before(cooldownUntil) {
            time.Sleep(10 * time.Second)
            continue
        }

        engine, err := findGoldenEngine(base)
        if err != nil {
            logf(logPath, "NO-ACTION · golden v3.0 engine not found: %v", err)
            misses = 0
            time.Sleep(30 * time.Second)
            continue
        }

        logf(logPath, "HEALTH_FAIL · 51920 absent 3 checks · recycling ONLY golden R8 engine: %s", engine)
        _ = killImage(filepath.Base(engine))
        time.Sleep(4 * time.Second)

        if ok, pid := listener51920(); ok {
            logf(logPath, "RECOVERED_BY_V34 · wrapper restored listener pid=%s", pid)
            misses = 0
            cooldownUntil = time.Now().Add(60 * time.Second)
            continue
        }

        if err := startHidden(engine); err != nil {
            logf(logPath, "RESTART_FAIL · %v", err)
        } else {
            logf(logPath, "ENGINE_RESTART · launched golden R8 engine")
        }
        misses = 0
        cooldownUntil = time.Now().Add(60 * time.Second)
        time.Sleep(10 * time.Second)
    }
}

func listener51920() (bool, string) {
    cmd := exec.Command("netstat", "-ano", "-p", "tcp")
    cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
    out, err := cmd.Output()
    if err != nil { return false, "" }
    sc := bufio.NewScanner(strings.NewReader(string(out)))
    for sc.Scan() {
        line := strings.TrimSpace(sc.Text())
        u := strings.ToUpper(line)
        if strings.Contains(line, targetPort) && strings.Contains(u, "LISTENING") {
            f := strings.Fields(line)
            if len(f) > 0 { return true, f[len(f)-1] }
            return true, "?"
        }
    }
    return false, ""
}

func findGoldenEngine(root string) (string, error) {
    st, err := os.Stat(root)
    if err != nil || !st.IsDir() { return "", fmt.Errorf("v3.4 install folder missing") }
    var found string
    err = filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
        if err != nil || info == nil || info.IsDir() { return nil }
        if !strings.EqualFold(filepath.Ext(info.Name()), ".exe") { return nil }
        h, e := fileSHA256(path)
        if e == nil && strings.EqualFold(h, goldenEngineSHA) {
            found = path
            return io.EOF
        }
        return nil
    })
    if err == io.EOF && found != "" { return found, nil }
    if found != "" { return found, nil }
    return "", fmt.Errorf("SHA256 %s not present", goldenEngineSHA)
}

func fileSHA256(path string) (string, error) {
    f, err := os.Open(path)
    if err != nil { return "", err }
    defer f.Close()
    h := sha256.New()
    if _, err = io.Copy(h, f); err != nil { return "", err }
    return hex.EncodeToString(h.Sum(nil)), nil
}

func killImage(name string) error {
    cmd := exec.Command("taskkill", "/F", "/IM", name)
    cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
    _ = cmd.Run() // dead already is fine
    return nil
}

func startHidden(path string) error {
    cmd := exec.Command(path)
    cmd.Dir = filepath.Dir(path)
    cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x00000008}
    return cmd.Start()
}

func logf(path, format string, args ...interface{}) {
    logMu.Lock()
    defer logMu.Unlock()
    f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
    if err != nil { return }
    defer f.Close()
    _, _ = fmt.Fprintf(f, "%s · %s\r\n", time.Now().Format("2006-01-02 15:04:05"), fmt.Sprintf(format, args...))
}
