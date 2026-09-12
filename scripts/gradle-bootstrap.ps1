param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Split-Path -Parent $PSScriptRoot
$version = '8.10.2'
$tools = Join-Path $root '.tools'
$gradleDir = Join-Path $tools "gradle-$version"
$gradleBat = Join-Path $gradleDir 'bin\gradle.bat'
$zip = Join-Path $tools "gradle-$version-bin.zip"
$url = "https://services.gradle.org/distributions/gradle-$version-bin.zip"

New-Item -ItemType Directory -Force -Path $tools | Out-Null

if (-not (Test-Path $gradleBat)) {
    Write-Host "Downloading Gradle $version..." -ForegroundColor Cyan
    if (-not (Test-Path $zip)) {
        $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
        if ($curl) {
            & $curl.Source -fL --retry 3 --retry-delay 2 -o $zip $url
            if ($LASTEXITCODE -ne 0) {
                throw "Gradle download failed with curl exit code ${LASTEXITCODE}."
            }
        } else {
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip
        }
    }
    Expand-Archive -Path $zip -DestinationPath $tools -Force
}

if (-not (Test-Path $gradleBat)) {
    throw "Gradle bootstrap failed: $gradleBat not found."
}

& $gradleBat @GradleArguments
exit $LASTEXITCODE
