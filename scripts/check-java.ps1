$ErrorActionPreference = 'Stop'

function Get-JavaMajor([string] $exe) {
    $output = & $exe -version 2>&1 | Out-String
    if ($output -match 'version\s+"(?<v>[0-9]+)(?:\.([0-9]+))?') {
        $major = [int]$Matches['v']
        if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
        return $major
    }
    return 0
}

$java = Get-Command java -ErrorAction SilentlyContinue
$javac = Get-Command javac -ErrorAction SilentlyContinue
if (-not $java -or -not $javac) {
    Write-Host 'A full Java 21 JDK is required. java.exe and javac.exe must both be on PATH.' -ForegroundColor Red
    exit 1
}

$javaMajor = Get-JavaMajor $java.Source
$javacMajor = Get-JavaMajor $javac.Source
if ($javaMajor -ne 21 -or $javacMajor -ne 21) {
    Write-Host "KSP Ani Clean requires JDK 21. PATH currently resolves java=$javaMajor, javac=$javacMajor." -ForegroundColor Red
    Write-Host 'Set JAVA_HOME to your JDK 21 directory and put %JAVA_HOME%\bin before older Java installations on PATH.' -ForegroundColor Yellow
    exit 1
}

Write-Host "Java 21 JDK detected: $($java.Source)" -ForegroundColor Green
exit 0
