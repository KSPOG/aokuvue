using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;

namespace AokuvueDevInstaller;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
            return InstallerCore.RunSelfTest();

        ApplicationConfiguration.Initialize();
        Application.Run(new InstallerForm());
        return 0;
    }
}

internal sealed class InstallerForm : Form
{
    private const string AppName = "Aokuvue Dev";
    private readonly TextBox installPath = new();
    private readonly Label status = new();
    private readonly ProgressBar progress = new();
    private readonly Button installButton = new();
    private readonly Button browseButton = new();
    private readonly Button launchButton = new();
    private readonly RichTextBox log = new();

    internal InstallerForm()
    {
        Text = "Aokuvue Dev / Installer";
        ClientSize = new Size(860, 570);
        MinimumSize = new Size(760, 540);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Color.FromArgb(7, 7, 13);
        ForeColor = Color.FromArgb(241, 237, 250);
        Font = new Font("Segoe UI", 9F);

        var title = new Label
        {
            Text = "Aokuvue Dev\nInstaller & Updater",
            Font = new Font("Segoe UI", 25F, FontStyle.Regular),
            ForeColor = Color.White,
            AutoSize = true,
            Location = new Point(52, 42)
        };
        var subtitle = new Label
        {
            Text = "Verified prebuilt releases · no source compilation or developer tools required",
            ForeColor = Color.FromArgb(183, 174, 211),
            AutoSize = true,
            Location = new Point(56, 128)
        };

        installPath.Text = InstallerCore.LoadInstallPath();
        installPath.SetBounds(56, 166, 650, 32);
        StyleTextBox(installPath);

        browseButton.Text = "Browse…";
        browseButton.SetBounds(716, 165, 90, 34);
        StyleButton(browseButton, false);
        browseButton.Click += (_, _) => Browse();

        status.Text = "Ready to install or update Aokuvue Dev.";
        status.ForeColor = Color.FromArgb(183, 174, 211);
        status.SetBounds(56, 216, 750, 42);

        progress.SetBounds(56, 259, 750, 16);
        progress.Style = ProgressBarStyle.Continuous;

        installButton.Text = "Install & Update";
        installButton.SetBounds(56, 294, 144, 40);
        StyleButton(installButton, true);
        installButton.Click += async (_, _) => await InstallAsync();

        launchButton.Text = "Launch Aokuvue Dev";
        launchButton.SetBounds(210, 294, 170, 40);
        StyleButton(launchButton, false);
        launchButton.Click += (_, _) => Launch();

        log.SetBounds(56, 354, 750, 166);
        log.BackColor = Color.FromArgb(5, 5, 10);
        log.ForeColor = Color.FromArgb(220, 215, 234);
        log.BorderStyle = BorderStyle.FixedSingle;
        log.ReadOnly = true;
        log.Font = new Font("Cascadia Mono", 8.5F);

        Controls.AddRange([title, subtitle, installPath, browseButton, status, progress, installButton, launchButton, log]);
        RefreshLaunchState();
    }

    private static void StyleTextBox(TextBox control)
    {
        control.BackColor = Color.FromArgb(17, 17, 27);
        control.ForeColor = Color.White;
        control.BorderStyle = BorderStyle.FixedSingle;
    }

    private static void StyleButton(Button control, bool primary)
    {
        control.FlatStyle = FlatStyle.Flat;
        control.FlatAppearance.BorderColor = Color.FromArgb(83, 66, 130);
        control.BackColor = primary ? Color.FromArgb(142, 124, 255) : Color.FromArgb(17, 17, 27);
        control.ForeColor = primary ? Color.Black : Color.White;
        control.Cursor = Cursors.Hand;
    }

    private void Browse()
    {
        using var dialog = new FolderBrowserDialog
        {
            Description = "Choose where Aokuvue Dev should be installed",
            SelectedPath = installPath.Text,
            UseDescriptionForTitle = true
        };
        if (dialog.ShowDialog(this) == DialogResult.OK)
            installPath.Text = dialog.SelectedPath;
    }

    private async Task InstallAsync()
    {
        var destinationText = installPath.Text.Trim();
        if (destinationText.Length == 0)
        {
            MessageBox.Show(this, "Choose an install folder first.", Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        SetBusy(true);
        log.Clear();
        try
        {
            var destination = Path.GetFullPath(destinationText);
            InstallerCore.SaveInstallPath(destination);
            var reporter = new Progress<InstallProgress>(update =>
            {
                progress.Value = Math.Clamp(update.Percent, 0, 100);
                status.Text = update.Status;
                if (!string.IsNullOrWhiteSpace(update.LogLine)) AppendLog(update.LogLine);
            });
            var version = await InstallerCore.InstallLatestAsync(destination, reporter, CancellationToken.None);
            status.Text = $"Aokuvue Dev {version} installed successfully.";
            status.ForeColor = Color.FromArgb(158, 216, 179);
            AppendLog($"Installation complete: Aokuvue Dev {version}");
        }
        catch (Exception error)
        {
            status.Text = $"Installation failed: {error.Message}";
            status.ForeColor = Color.FromArgb(255, 141, 155);
            AppendLog($"ERROR: {error}");
        }
        finally
        {
            SetBusy(false);
            RefreshLaunchState();
        }
    }

    private void Launch()
    {
        try
        {
            InstallerCore.Launch(Path.GetFullPath(installPath.Text.Trim()));
            Close();
        }
        catch (Exception error) { MessageBox.Show(this, error.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Error); }
    }

    private void SetBusy(bool busy)
    {
        installButton.Enabled = !busy;
        browseButton.Enabled = !busy;
        launchButton.Enabled = !busy && File.Exists(Path.Combine(installPath.Text.Trim(), "Aokuvue Dev.exe"));
        installPath.Enabled = !busy;
        if (busy) status.ForeColor = Color.FromArgb(183, 174, 211);
    }

    private void RefreshLaunchState() =>
        launchButton.Enabled = File.Exists(Path.Combine(installPath.Text.Trim(), "Aokuvue Dev.exe"));

    private void AppendLog(string line)
    {
        log.AppendText(line.TrimEnd() + Environment.NewLine);
        log.SelectionStart = log.TextLength;
        log.ScrollToCaret();
    }
}

internal sealed record InstallProgress(int Percent, string Status, string? LogLine = null);
internal sealed record ReleaseAsset(string Name, string DownloadUrl);
internal sealed record DevRelease(string Version, ReleaseAsset Package, ReleaseAsset Checksum);

internal static class InstallerCore
{
    private const string AppName = "Aokuvue Dev";
    private const string InstallerVersion = "0.2.1";
    private const string ReleaseApi = "https://api.github.com/repos/KSPOG/aokuvue/releases/tags/dev-app-latest";
    private const string ReleaseDownloadBase = "https://github.com/KSPOG/aokuvue/releases/download/dev-app-latest/";
    private const string PackageName = "AokuvueDev-windows-x64.zip";
    private const string ChecksumName = "AokuvueDev-windows-x64.zip.sha256";
    private static readonly HttpClient Http = CreateHttpClient();

    internal static string DefaultInstallPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", AppName);

    private static string StatePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), AppName, "installer", "state.json");

    private static HttpClient CreateHttpClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("AokuvueDevInstaller", InstallerVersion));
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        client.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28");
        return client;
    }

    internal static string LoadInstallPath()
    {
        try
        {
            if (!File.Exists(StatePath)) return DefaultInstallPath;
            using var document = JsonDocument.Parse(File.ReadAllText(StatePath));
            if (document.RootElement.TryGetProperty("installPath", out var value))
                return value.GetString() ?? DefaultInstallPath;
        }
        catch (Exception) { }
        return DefaultInstallPath;
    }

    internal static void SaveInstallPath(string value)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(StatePath)!);
        File.WriteAllText(StatePath, JsonSerializer.Serialize(new { installPath = value }));
    }

    internal static async Task<string> InstallLatestAsync(
        string destination,
        IProgress<InstallProgress> progress,
        CancellationToken cancellationToken)
    {
        progress.Report(new(2, "Checking the latest verified development build…", $"Aokuvue Dev Installer {InstallerVersion}"));
        var release = await ResolveReleaseAsync(cancellationToken);
        progress.Report(new(8, $"Downloading Aokuvue Dev {release.Version}…", $"Latest package: {release.Package.Name}"));

        var parent = Directory.GetParent(destination)?.FullName
            ?? throw new InvalidOperationException("The install directory must have a parent folder.");
        Directory.CreateDirectory(parent);
        var work = Path.Combine(parent, $".AokuvueDevInstaller-{Guid.NewGuid():N}");
        var archive = Path.Combine(work, PackageName);
        var extracted = Path.Combine(work, "app");
        Directory.CreateDirectory(work);

        try
        {
            var checksumText = await Http.GetStringAsync(release.Checksum.DownloadUrl, cancellationToken);
            var expectedHash = ParseSha256(checksumText);
            await DownloadAsync(release.Package.DownloadUrl, archive, progress, cancellationToken);
            progress.Report(new(72, "Verifying the downloaded package…", $"Expected SHA-256: {expectedHash}"));
            var actualHash = await ComputeSha256Async(archive, cancellationToken);
            if (!actualHash.Equals(expectedHash, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException($"Package checksum mismatch. Expected {expectedHash}, received {actualHash}.");

            ZipFile.ExtractToDirectory(archive, extracted);
            var executable = Path.Combine(extracted, "Aokuvue Dev.exe");
            if (!File.Exists(executable))
                throw new InvalidDataException("The verified package does not contain Aokuvue Dev.exe.");

            progress.Report(new(86, "Closing Aokuvue Dev before updating…"));
            StopRunningApplication();
            progress.Report(new(90, "Installing the verified Aokuvue Dev build…"));
            ReplaceInstallation(extracted, destination, progress);
            File.WriteAllText(Path.Combine(destination, ".aokuvue-version"), release.Version);
            progress.Report(new(100, $"Aokuvue Dev {release.Version} is ready."));
            return release.Version;
        }
        finally
        {
            TryDeleteDirectory(work, TimeSpan.FromSeconds(8));
        }
    }

    private static async Task<DevRelease> ResolveReleaseAsync(CancellationToken cancellationToken)
    {
        var version = "latest development build";
        for (var attempt = 0; attempt < 3; attempt++)
        {
            try
            {
                using var request = new HttpRequestMessage(
                    HttpMethod.Get,
                    $"{ReleaseApi}?installer={InstallerVersion}&attempt={attempt}&time={DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}");
                request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true, NoStore = true };
                using var response = await Http.SendAsync(request, cancellationToken);
                response.EnsureSuccessStatusCode();
                using var document = JsonDocument.Parse(await response.Content.ReadAsStreamAsync(cancellationToken));
                var root = document.RootElement;
                if (root.TryGetProperty("name", out var name)) version = NormalizeReleaseVersion(name.GetString());
                ReleaseAsset? package = null;
                ReleaseAsset? checksum = null;
                if (root.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
                {
                    foreach (var asset in assets.EnumerateArray())
                    {
                        var assetName = asset.TryGetProperty("name", out var assetNameNode) ? assetNameNode.GetString() ?? "" : "";
                        var url = asset.TryGetProperty("browser_download_url", out var urlNode) ? urlNode.GetString() ?? "" : "";
                        if (assetName == PackageName && !string.IsNullOrWhiteSpace(url)) package = new(assetName, url);
                        if (assetName == ChecksumName && !string.IsNullOrWhiteSpace(url)) checksum = new(assetName, url);
                    }
                }
                if (package is not null && checksum is not null) return new(version, package, checksum);
            }
            catch (Exception error) when (error is HttpRequestException or JsonException)
            {
                // The stable download fallback below remains checksum-protected.
            }
            if (attempt < 2) await Task.Delay(TimeSpan.FromMilliseconds(500 * (attempt + 1)), cancellationToken);
        }

        // The moving release briefly has an empty API asset list while GitHub replaces its files.
        // Its download URLs are stable, so fall back to those exact paths and retain checksum verification.
        return new(version,
            new ReleaseAsset(PackageName, ReleaseDownloadBase + PackageName),
            new ReleaseAsset(ChecksumName, ReleaseDownloadBase + ChecksumName));
    }

    private static string NormalizeReleaseVersion(string? value)
    {
        var version = string.IsNullOrWhiteSpace(value) ? "latest development build" : value.Trim();
        return version.StartsWith("Aokuvue Dev ", StringComparison.OrdinalIgnoreCase)
            ? version["Aokuvue Dev ".Length..]
            : version;
    }

    private static async Task DownloadAsync(
        string url,
        string destination,
        IProgress<InstallProgress> progress,
        CancellationToken cancellationToken)
    {
        using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        var total = response.Content.Headers.ContentLength;
        await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
        await using var output = new FileStream(destination, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1024 * 128, true);
        var buffer = new byte[1024 * 128];
        long received = 0;
        int read;
        while ((read = await input.ReadAsync(buffer, cancellationToken)) > 0)
        {
            await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            received += read;
            var percent = total is > 0 ? 8 + (int)(received * 60 / total.Value) : 35;
            progress.Report(new(percent, $"Downloading Aokuvue Dev… {received / 1_048_576:N0} MB"));
        }
    }

    private static string ParseSha256(string value)
    {
        var token = value.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? "";
        return token.Length == 64 && token.All(Uri.IsHexDigit)
            ? token.ToLowerInvariant()
            : throw new InvalidDataException("The release checksum file is invalid.");
    }

    private static async Task<string> ComputeSha256Async(string path, CancellationToken cancellationToken)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 128, true);
        var hash = await SHA256.HashDataAsync(stream, cancellationToken);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static void StopRunningApplication()
    {
        foreach (var process in Process.GetProcessesByName("Aokuvue Dev"))
        {
            using (process)
            {
                try
                {
                    process.Kill(true);
                    process.WaitForExit(15_000);
                }
                catch (InvalidOperationException) { }
            }
        }
    }

    private static void ReplaceInstallation(string extracted, string destination, IProgress<InstallProgress> progress)
    {
        var parent = Directory.GetParent(destination)!.FullName;
        var staging = Path.Combine(parent, $".Aokuvue Dev.staging-{Guid.NewGuid():N}");
        var backup = Path.Combine(parent, $".Aokuvue Dev.previous-{Guid.NewGuid():N}");
        Directory.Move(extracted, staging);
        var oldMoved = false;
        var newActivated = false;
        try
        {
            if (Directory.Exists(destination))
            {
                Retry(() => Directory.Move(destination, backup), "back up the existing installation", progress);
                oldMoved = true;
            }
            Retry(() => Directory.Move(staging, destination), "activate the new installation", progress);
            newActivated = true;
            if (!File.Exists(Path.Combine(destination, "Aokuvue Dev.exe")))
                throw new InvalidDataException("The installed application could not be verified.");
            TryDeleteDirectory(backup, TimeSpan.FromSeconds(8));
        }
        catch
        {
            if (newActivated) TryDeleteDirectory(destination, TimeSpan.FromSeconds(8));
            if (oldMoved && Directory.Exists(backup))
                Retry(() => Directory.Move(backup, destination), "restore the previous installation", progress);
            throw;
        }
        finally
        {
            TryDeleteDirectory(staging, TimeSpan.FromSeconds(3));
        }
    }

    private static void Retry(Action action, string description, IProgress<InstallProgress> progress)
    {
        var deadline = Stopwatch.StartNew();
        Exception? last = null;
        while (deadline.Elapsed < TimeSpan.FromSeconds(30))
        {
            try { action(); return; }
            catch (IOException error) { last = error; }
            catch (UnauthorizedAccessException error) { last = error; }
            progress.Report(new(92, $"Waiting for Windows to {description}…"));
            Thread.Sleep(500);
        }
        throw new IOException($"Could not {description} because Windows kept a file locked.", last);
    }

    private static bool TryDeleteDirectory(string path, TimeSpan timeout)
    {
        if (!Directory.Exists(path)) return true;
        var timer = Stopwatch.StartNew();
        do
        {
            try { Directory.Delete(path, true); return true; }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
            Thread.Sleep(300);
        } while (timer.Elapsed < timeout);
        return false;
    }

    internal static void Launch(string installDirectory)
    {
        var executable = Path.Combine(installDirectory, "Aokuvue Dev.exe");
        if (!File.Exists(executable)) throw new FileNotFoundException("Aokuvue Dev is not installed at the selected path.", executable);
        var process = Process.Start(new ProcessStartInfo(executable) { WorkingDirectory = installDirectory, UseShellExecute = true });
        if (process is null) throw new InvalidOperationException("Windows did not start Aokuvue Dev.");
    }

    internal static int RunSelfTest()
    {
        if (ParseSha256(new string('a', 64) + "  package.zip") != new string('a', 64)) return 1;
        if (!PackageName.EndsWith(".zip", StringComparison.Ordinal)) return 2;
        if (!ReleaseApi.StartsWith("https://api.github.com/", StringComparison.Ordinal)) return 3;
        if (NormalizeReleaseVersion("Aokuvue Dev Beta - 0.0.4") != "Beta - 0.0.4") return 4;
        if (!ReleaseDownloadBase.StartsWith("https://github.com/KSPOG/aokuvue/releases/download/dev-app-latest/", StringComparison.Ordinal)) return 5;
        Console.WriteLine($"Aokuvue Dev native installer {InstallerVersion} self-test passed.");
        return 0;
    }
}
