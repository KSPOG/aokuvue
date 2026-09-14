param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Split-Path -Parent $PSScriptRoot
$version = '8.10.2'
$expectedSha256 = '31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26'

if (-not [string]::IsNullOrWhiteSpace($env:AOKUVUE_GRADLE_CACHE)) {
    $tools = $env:AOKUVUE_GRADLE_CACHE
} elseif (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $tools = Join-Path $env:LOCALAPPDATA 'AOKUVUE\build-tools\gradle'
} else {
    $tools = Join-Path $root '.tools'
}

$gradleDir = Join-Path $tools "gradle-$version"
$gradleBat = Join-Path $gradleDir 'bin\gradle.bat'
$zip = Join-Path $tools "gradle-$version-bin.zip"
$partialZip = "$zip.part"
$urls = @(
    "https://downloads.gradle.org/distributions/gradle-$version-bin.zip",
    "https://services.gradle.org/distributions/gradle-$version-bin.zip"
)

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string] $Path)

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $bytes = $sha.ComputeHash($stream)
            return (-join ($bytes | ForEach-Object { $_.ToString('x2') }))
        } finally {
            $sha.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Test-GradleArchive {
    param([Parameter(Mandatory = $true)][string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $false
    }

    try {
        $file = Get-Item -LiteralPath $Path
        if ($file.Length -lt 100MB) {
            Write-Warning "Ignoring incomplete Gradle archive ($($file.Length) bytes)."
            return $false
        }

        $actual = (Get-Sha256 -Path $Path).ToLowerInvariant()
        if ($actual -ne $expectedSha256) {
            Write-Warning "Ignoring Gradle archive with invalid SHA-256: $actual"
            return $false
        }
        return $true
    } catch {
        Write-Warning "Could not validate cached Gradle archive: $($_.Exception.Message)"
        return $false
    }
}

function Download-GradleArchive {
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    $lastError = $null

    for ($index = 0; $index -lt $urls.Count; $index++) {
        $url = $urls[$index]
        $attempt = $index + 1
        Remove-Item -LiteralPath $partialZip -Force -ErrorAction SilentlyContinue
        Write-Host "Downloading Gradle $version ($attempt/$($urls.Count)) from $url" -ForegroundColor Cyan

        $downloaded = $false
        if ($curl) {
            & $curl.Source -fL --silent --show-error --retry 5 --retry-delay 2 --connect-timeout 20 --max-time 900 -o $partialZip $url
            if ($LASTEXITCODE -eq 0) {
                $downloaded = $true
            } else {
                $lastError = "curl exit code $LASTEXITCODE"
                Write-Warning "Gradle download through curl failed ($lastError). Falling back to PowerShell."
                Remove-Item -LiteralPath $partialZip -Force -ErrorAction SilentlyContinue
            }
        }

        if (-not $downloaded) {
            try {
                $previousPreference = $ProgressPreference
                $ProgressPreference = 'SilentlyContinue'
                try {
                    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $partialZip -TimeoutSec 900
                } finally {
                    $ProgressPreference = $previousPreference
                }
                $downloaded = $true
            } catch {
                $lastError = $_.Exception.Message
                Write-Warning "Gradle download through PowerShell failed: $lastError"
                Remove-Item -LiteralPath $partialZip -Force -ErrorAction SilentlyContinue
            }
        }

        if ($downloaded -and (Test-GradleArchive -Path $partialZip)) {
            Move-Item -LiteralPath $partialZip -Destination $zip -Force
            Write-Host "Gradle $version download verified (SHA-256)." -ForegroundColor Green
            return
        }

        if ($downloaded) {
            $lastError = 'downloaded archive failed size or SHA-256 validation'
            Remove-Item -LiteralPath $partialZip -Force -ErrorAction SilentlyContinue
        }
    }

    throw "Unable to download a verified Gradle $version distribution. Last error: $lastError"
}

New-Item -ItemType Directory -Force -Path $tools | Out-Null

if (-not (Test-Path -LiteralPath $gradleBat -PathType Leaf)) {
    if (-not (Test-GradleArchive -Path $zip)) {
        Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
        Download-GradleArchive
    } else {
        Write-Host "Using verified cached Gradle $version archive." -ForegroundColor DarkGray
    }

    # A previous interrupted extraction must never poison future installer runs.
    Remove-Item -LiteralPath $gradleDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Extracting Gradle $version..." -ForegroundColor Cyan
    try {
        Expand-Archive -LiteralPath $zip -DestinationPath $tools -Force
    } catch {
        Remove-Item -LiteralPath $gradleDir -Recurse -Force -ErrorAction SilentlyContinue
        throw "Gradle extraction failed: $($_.Exception.Message)"
    }
}

if (-not (Test-Path -LiteralPath $gradleBat -PathType Leaf)) {
    throw "Gradle bootstrap failed: $gradleBat not found after verified download/extraction."
}

Write-Host "Using Gradle $version from $gradleDir" -ForegroundColor DarkGray
& $gradleBat @GradleArguments
$exitCode = $LASTEXITCODE
if ($null -eq $exitCode) { $exitCode = 1 }
exit $exitCode
