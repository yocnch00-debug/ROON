using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;

namespace ONShareLinkPC;

internal static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

internal sealed class MainForm : Form
{
    private const string Ssid = "DIRECT-ON-ShareLink";
    private const string Host = "192.168.49.1";
    private const int Port = 51950;
    private const string TunName = "ON ShareLink";
    private const string TunIp = "198.18.0.2";

    private readonly TextBox codeBox = new() { MaxLength = 8, Width = 170, Font = new Font("Segoe UI", 16F) };
    private readonly CheckBox wifiAuto = new() { Text = "DIRECT-ON-ShareLink Wi-Fi 자동 연결", Checked = true, AutoSize = true };
    private readonly Button startButton = new() { Text = "연결", Width = 150, Height = 42 };
    private readonly Button stopButton = new() { Text = "연결 해제", Width = 150, Height = 42, Enabled = false };
    private readonly Label status = new() { Text = "대기중", AutoSize = false, Height = 52, Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft };
    private readonly TextBox logBox = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill };

    private readonly string workDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ON ShareLink PC");
    private Process? tunnelProcess;
    private CancellationTokenSource? monitorCts;
    private bool closing;

    private static readonly string[] PublicRoutes =
    {
        "1.0.0.0/8","2.0.0.0/7","4.0.0.0/6","8.0.0.0/7","11.0.0.0/8","12.0.0.0/6","16.0.0.0/4","32.0.0.0/3","64.0.0.0/3","96.0.0.0/6","100.0.0.0/10","100.128.0.0/9","101.0.0.0/8","102.0.0.0/7","104.0.0.0/5","112.0.0.0/5","120.0.0.0/6","124.0.0.0/7","126.0.0.0/8","128.0.0.0/3","160.0.0.0/5","168.0.0.0/8","169.0.0.0/9","169.128.0.0/10","169.192.0.0/11","169.224.0.0/12","169.240.0.0/13","169.248.0.0/14","169.252.0.0/15","169.255.0.0/16","170.0.0.0/7","172.0.0.0/12","172.32.0.0/11","172.64.0.0/10","172.128.0.0/9","173.0.0.0/8","174.0.0.0/7","176.0.0.0/4","192.0.0.0/9","192.128.0.0/11","192.160.0.0/13","192.169.0.0/16","192.170.0.0/15","192.172.0.0/14","192.176.0.0/12","192.192.0.0/10","193.0.0.0/8","194.0.0.0/7","196.0.0.0/7","198.0.0.0/12","198.16.0.0/15","198.20.0.0/14","198.24.0.0/13","198.32.0.0/11","198.64.0.0/10","198.128.0.0/9","199.0.0.0/8","200.0.0.0/5","208.0.0.0/4"
    };

    public MainForm()
    {
        Text = "ON ShareLink PC Client v0.1";
        Width = 620;
        Height = 500;
        MinimumSize = new Size(620, 500);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 10F);

        var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(18), ColumnCount = 1, RowCount = 8 };
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 58));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 56));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 10));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 28));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        var title = new Label { Text = "ON ShareLink PC Client", Font = new Font("Segoe UI", 20F, FontStyle.Bold), Dock = DockStyle.Fill };
        var codeRow = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
        codeRow.Controls.Add(new Label { Text = "S26 8자리 코드", AutoSize = true, Padding = new Padding(0, 8, 10, 0) });
        codeRow.Controls.Add(codeBox);
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.LeftToRight };
        buttons.Controls.Add(startButton); buttons.Controls.Add(stopButton);
        var logTitle = new Label { Text = "진단", Dock = DockStyle.Fill, TextAlign = ContentAlignment.BottomLeft };

        root.Controls.Add(title, 0, 0);
        root.Controls.Add(codeRow, 0, 1);
        root.Controls.Add(wifiAuto, 0, 2);
        root.Controls.Add(status, 0, 3);
        root.Controls.Add(buttons, 0, 4);
        root.Controls.Add(new Panel(), 0, 5);
        root.Controls.Add(logTitle, 0, 6);
        root.Controls.Add(logBox, 0, 7);
        Controls.Add(root);

        codeBox.KeyPress += (_, e) => { if (!char.IsControl(e.KeyChar) && !char.IsDigit(e.KeyChar)) e.Handled = true; };
        startButton.Click += async (_, _) => await StartClicked();
        stopButton.Click += async (_, _) => await StopClicked();
        FormClosing += OnFormClosing;
        Append("PC Client 준비 완료 · S26 Host는 변경하지 않습니다.");
    }

    private async Task StartClicked()
    {
        string code = codeBox.Text.Trim();
        if (!Regex.IsMatch(code, "^[0-9]{8}$"))
        {
            MessageBox.Show("S26 ShareLink와 같은 숫자 8자리 코드를 입력해 주세요.", "ON ShareLink", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        SetBusy(true);
        try
        {
            await Task.Run(() => StartCore(code));
            stopButton.Enabled = true;
            startButton.Enabled = false;
            codeBox.Enabled = false;
            wifiAuto.Enabled = false;
            SetStatus("연결됨 · 노트북 인터넷 → S26 LTE/5G");
            Append("ShareLink 연결 완료. 로컬/사설망은 터널 밖으로 유지됩니다.");
            StartMonitor(code);
        }
        catch (Exception ex)
        {
            await Task.Run(() => StopCore());
            SetStatus("연결 실패");
            Append("ERROR " + ex.Message);
            MessageBox.Show(ex.Message, "ON ShareLink 연결 실패", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally { if (startButton.Enabled) SetBusy(false); }
    }

    private async Task StopClicked()
    {
        SetStatus("연결 해제중");
        await Task.Run(() => StopCore());
        monitorCts?.Cancel();
        startButton.Enabled = true;
        stopButton.Enabled = false;
        codeBox.Enabled = true;
        wifiAuto.Enabled = true;
        SetBusy(false);
        SetStatus("연결 해제됨");
        Append("ShareLink 터널과 공인 IPv4 경로를 정리했습니다.");
    }

    private void StartCore(string code)
    {
        Directory.CreateDirectory(workDir);
        CleanupRoutes();
        KillOldTunnel();

        if (!HasS26Link())
        {
            if (wifiAuto.Checked)
            {
                SetStatus("1/4 DIRECT-ON-ShareLink Wi-Fi 연결중");
                Append("Wi-Fi 프로필 등록/연결 시도");
                ProvisionWifi(code);
            }
            WaitForS26Link(TimeSpan.FromSeconds(22));
        }
        Append("Wi-Fi Direct 연결 확인: 192.168.49.x");

        SetStatus("2/4 S26 인증 확인중");
        ProbeSocks(code, 5000);
        Append("S26 SOCKS5 인증 성공");

        ExtractEmbedded("ONShareLink.hev-socks5-tunnel.exe", Path.Combine(workDir, "hev-socks5-tunnel.exe"));
        ExtractEmbedded("ONShareLink.wintun.dll", Path.Combine(workDir, "wintun.dll"));
        string cfg = WriteTunnelConfig(code);

        SetStatus("3/4 Windows TUN 시작중");
        StartTunnel(cfg);
        int ifIndex = WaitForTunAdapter(TimeSpan.FromSeconds(15));
        Append($"Wintun 준비됨 · IF={ifIndex}");

        ConfigureTunDns();
        InstallRoutes(ifIndex);
        RunHidden("ipconfig.exe", "/flushdns", false);

        SetStatus("4/4 실제 인터넷 확인중");
        if (!CanConnect("1.1.1.1", 443, 6000))
            throw new IOException("S26를 통한 실제 인터넷 연결 확인에 실패했습니다. S26 모바일 데이터를 확인해 주세요.");
    }

    private void StopCore()
    {
        try { CleanupRoutes(); } catch { }
        try
        {
            if (tunnelProcess != null && !tunnelProcess.HasExited)
            {
                tunnelProcess.Kill(entireProcessTree: true);
                tunnelProcess.WaitForExit(4000);
            }
        }
        catch { }
        tunnelProcess?.Dispose();
        tunnelProcess = null;
        KillOldTunnel();
    }

    private void StartTunnel(string configPath)
    {
        string exe = Path.Combine(workDir, "hev-socks5-tunnel.exe");
        var psi = new ProcessStartInfo(exe, $"\"{configPath}\"")
        {
            WorkingDirectory = workDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        tunnelProcess = new Process { StartInfo = psi, EnableRaisingEvents = true };
        tunnelProcess.OutputDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) Append("HEV " + e.Data); };
        tunnelProcess.ErrorDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) Append("HEV " + e.Data); };
        if (!tunnelProcess.Start()) throw new IOException("터널 엔진을 시작하지 못했습니다.");
        tunnelProcess.BeginOutputReadLine();
        tunnelProcess.BeginErrorReadLine();
        Thread.Sleep(500);
        if (tunnelProcess.HasExited) throw new IOException("터널 엔진이 바로 종료되었습니다. 진단 로그를 확인해 주세요.");
    }

    private string WriteTunnelConfig(string code)
    {
        string logPath = Path.Combine(workDir, "hev-pc.log").Replace("'", "''");
        string cfgPath = Path.Combine(workDir, "sharelink-pc.yml");
        string text = $"""
            tunnel:
              name: '{TunName}'
              mtu: 1500
              ipv4: '{TunIp}'
              ipv6: ''
              icmp: 'off'
            socks5:
              address: '{Host}'
              port: {Port}
              udp: 'udp'
              username: 'onshare'
              password: '{code}'
            misc:
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: '{logPath}'
              log-level: warn
            """;
        File.WriteAllText(cfgPath, text, new UTF8Encoding(false));
        return cfgPath;
    }

    private void ProvisionWifi(string code)
    {
        string profile = Path.Combine(workDir, "sharelink-wifi.xml");
        string xml = $"""
            <?xml version="1.0"?>
            <WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
              <name>{Ssid}</name>
              <SSIDConfig><SSID><name>{Ssid}</name></SSID><nonBroadcast>false</nonBroadcast></SSIDConfig>
              <connectionType>ESS</connectionType><connectionMode>auto</connectionMode>
              <MSM><security>
                <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>
                <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>{code}</keyMaterial></sharedKey>
              </security></MSM>
            </WLANProfile>
            """;
        File.WriteAllText(profile, xml, new UTF8Encoding(false));
        RunHidden("netsh.exe", $"wlan add profile filename=\"{profile}\" user=current", false);
        RunHidden("netsh.exe", $"wlan connect name=\"{Ssid}\" ssid=\"{Ssid}\"", false);
    }

    private static bool HasS26Link() => FindAddress("192.168.49.") != null;

    private static string? FindAddress(string prefix)
    {
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            foreach (var u in ni.GetIPProperties().UnicastAddresses)
                if (u.Address.AddressFamily == AddressFamily.InterNetwork && u.Address.ToString().StartsWith(prefix, StringComparison.Ordinal))
                    return u.Address.ToString();
        }
        return null;
    }

    private void WaitForS26Link(TimeSpan timeout)
    {
        var sw = Stopwatch.StartNew();
        while (sw.Elapsed < timeout)
        {
            if (HasS26Link()) return;
            Thread.Sleep(500);
        }
        throw new IOException($"{Ssid} 연결을 확인하지 못했습니다. Windows Wi-Fi 목록에서 이 망에 한 번 직접 연결한 뒤 다시 눌러 주세요.");
    }

    private static int WaitForTunAdapter(TimeSpan timeout)
    {
        var sw = Stopwatch.StartNew();
        while (sw.Elapsed < timeout)
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                var ip = ni.GetIPProperties().UnicastAddresses.FirstOrDefault(x => x.Address.AddressFamily == AddressFamily.InterNetwork && x.Address.ToString() == TunIp);
                if (ip != null)
                {
                    int? idx = ni.GetIPProperties().GetIPv4Properties()?.Index;
                    if (idx.HasValue) return idx.Value;
                }
            }
            Thread.Sleep(300);
        }
        throw new IOException("Windows TUN 어댑터가 준비되지 않았습니다.");
    }

    private static void ProbeSocks(string code, int timeoutMs)
    {
        using var client = new TcpClient();
        var ar = client.BeginConnect(Host, Port, null, null);
        if (!ar.AsyncWaitHandle.WaitOne(timeoutMs)) throw new IOException("S26 ShareLink 서버에 연결할 수 없습니다.");
        client.EndConnect(ar);
        client.ReceiveTimeout = timeoutMs; client.SendTimeout = timeoutMs;
        using NetworkStream s = client.GetStream();
        s.Write(new byte[] { 5, 1, 2 });
        byte[] hello = ReadN(s, 2);
        if (hello[0] != 5 || hello[1] != 2) throw new IOException("S26 SOCKS5 인증 방식이 맞지 않습니다.");
        byte[] user = Encoding.UTF8.GetBytes("onshare");
        byte[] pass = Encoding.UTF8.GetBytes(code);
        using var ms = new MemoryStream();
        ms.WriteByte(1); ms.WriteByte((byte)user.Length); ms.Write(user); ms.WriteByte((byte)pass.Length); ms.Write(pass);
        s.Write(ms.ToArray());
        byte[] auth = ReadN(s, 2);
        if (auth[0] != 1 || auth[1] != 0) throw new IOException("S26과 8자리 코드가 다릅니다.");
    }

    private static byte[] ReadN(Stream s, int n)
    {
        byte[] b = new byte[n]; int p = 0;
        while (p < n) { int r = s.Read(b, p, n - p); if (r <= 0) throw new EndOfStreamException(); p += r; }
        return b;
    }

    private static bool CanConnect(string host, int port, int timeoutMs)
    {
        try
        {
            using var c = new TcpClient();
            var ar = c.BeginConnect(host, port, null, null);
            if (!ar.AsyncWaitHandle.WaitOne(timeoutMs)) return false;
            c.EndConnect(ar); return true;
        }
        catch { return false; }
    }

    private void ConfigureTunDns()
    {
        RunHidden("netsh.exe", $"interface ipv4 set dnsservers name=\"{TunName}\" static 1.1.1.1 primary validate=no", false);
        RunHidden("netsh.exe", $"interface ipv4 add dnsservers name=\"{TunName}\" 8.8.8.8 index=2 validate=no", false);
    }

    private void InstallRoutes(int ifIndex)
    {
        CleanupRoutes();
        foreach (string cidr in PublicRoutes)
        {
            var (network, mask) = CidrToRoute(cidr);
            int ec = RunHidden("route.exe", $"ADD {network} MASK {mask} {TunIp} METRIC 5 IF {ifIndex}", false);
            if (ec != 0) throw new IOException("Windows 공인 IPv4 경로 생성 실패: " + cidr);
        }
        Append($"공인 IPv4 split routes {PublicRoutes.Length}개 적용");
    }

    private void CleanupRoutes()
    {
        foreach (string cidr in PublicRoutes)
        {
            var (network, mask) = CidrToRoute(cidr);
            RunHidden("route.exe", $"DELETE {network} MASK {mask}", false);
        }
    }

    private static (string network, string mask) CidrToRoute(string cidr)
    {
        string[] p = cidr.Split('/');
        int prefix = int.Parse(p[1]);
        uint mask = prefix == 0 ? 0 : uint.MaxValue << (32 - prefix);
        string m = string.Join('.', new[] { (mask >> 24) & 255, (mask >> 16) & 255, (mask >> 8) & 255, mask & 255 });
        return (p[0], m);
    }

    private void KillOldTunnel()
    {
        try
        {
            foreach (var p in Process.GetProcessesByName("hev-socks5-tunnel"))
            {
                try { p.Kill(entireProcessTree: true); p.WaitForExit(2000); } catch { }
                p.Dispose();
            }
        }
        catch { }
    }

    private static int RunHidden(string file, string args, bool throwOnError)
    {
        using var p = Process.Start(new ProcessStartInfo(file, args)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        }) ?? throw new IOException("프로세스 시작 실패: " + file);
        string stdout = p.StandardOutput.ReadToEnd(); string stderr = p.StandardError.ReadToEnd();
        p.WaitForExit(15000);
        if (throwOnError && p.ExitCode != 0) throw new IOException($"{file} 실패({p.ExitCode}) {stdout} {stderr}");
        return p.ExitCode;
    }

    private static void ExtractEmbedded(string resourceName, string outputPath)
    {
        using Stream src = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName) ?? throw new IOException("내장 엔진 누락: " + resourceName);
        using FileStream dst = File.Create(outputPath);
        src.CopyTo(dst);
    }

    private void StartMonitor(string code)
    {
        monitorCts?.Cancel(); monitorCts = new CancellationTokenSource();
        var token = monitorCts.Token;
        _ = Task.Run(async () =>
        {
            int misses = 0;
            while (!token.IsCancellationRequested && !closing)
            {
                await Task.Delay(10000, token).ConfigureAwait(false);
                try
                {
                    if (tunnelProcess == null || tunnelProcess.HasExited || !HasS26Link()) throw new IOException("link lost");
                    ProbeSocks(code, 3000); misses = 0;
                }
                catch
                {
                    misses++;
                    if (misses >= 3)
                    {
                        SetStatus("연결 이상 감지 · 재연결 필요");
                        Append("S26 연결 상태를 3회 연속 확인하지 못했습니다.");
                        break;
                    }
                }
            }
        }, token);
    }

    private void SetBusy(bool busy)
    {
        startButton.Enabled = !busy;
        if (busy) { stopButton.Enabled = false; codeBox.Enabled = false; wifiAuto.Enabled = false; }
        else if (tunnelProcess == null) { codeBox.Enabled = true; wifiAuto.Enabled = true; }
    }

    private void SetStatus(string text)
    {
        if (InvokeRequired) { BeginInvoke(() => SetStatus(text)); return; }
        status.Text = text;
    }

    private void Append(string text)
    {
        if (InvokeRequired) { BeginInvoke(() => Append(text)); return; }
        string line = DateTime.Now.ToString("HH:mm:ss") + "  " + text + Environment.NewLine;
        logBox.AppendText(line);
    }

    private void OnFormClosing(object? sender, FormClosingEventArgs e)
    {
        closing = true; monitorCts?.Cancel();
        try { StopCore(); } catch { }
    }
}
