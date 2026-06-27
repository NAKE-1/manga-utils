# Generates android-compat/lib/android.jar: an Android SDK stub jar with the classes
# we override (and JVM-conflicting packages) removed, so extensions can compile/run on the JVM.
# Adapted from Suwayomi-Server's AndroidCompat/getAndroid.ps1 (MPL-2.0) to this project's layout.
# Run from the repo root:  pwsh android-compat/generate-android-jar.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot   # repo root (android-compat is one level down)
if (-not (Test-Path "$root\settings.gradle.kts")) { $root = (Get-Location).Path }

$tmp = Join-Path $root "tmp-androidjar"
Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue | Out-Null
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

Write-Output "Downloading Android SDK 30 stub jar..."
$enc = (Invoke-WebRequest -UseBasicParsing -Uri "https://android.googlesource.com/platform/prebuilts/sdk/+/6cd31be5e4e25901aadf838120d71a79b46d9add/30/public/android.jar?format=TEXT").Content
$jar = Join-Path $tmp "android.jar"
[IO.File]::WriteAllBytes($jar, [Convert]::FromBase64String($enc))

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Remove-Entries($zipPath, [string[]]$patterns) {
    $stream = [IO.FileStream]::new($zipPath, [IO.FileMode]::Open)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Update)
    foreach ($p in $patterns) {
        @($zip.Entries | Where-Object { $_.FullName -like $p }) | ForEach-Object { $_.Delete() }
    }
    $zip.Dispose(); $stream.Close(); $stream.Dispose()
}

Write-Output "Removing packages provided by the JDK / other deps..."
Remove-Entries $jar @('org/json/*','org/apache/*','org/w3c/*','org/xml/*','org/xmlpull/*','junit/*','javax/*','java/*')

Write-Output "Removing classes we override in AndroidCompat..."
Remove-Entries $jar @(
    'android/app/Application.class','android/app/Service.class',
    'android/net/Uri.class','android/net/Uri$Builder.class',
    'android/os/Environment.class','android/text/format/Formatter.class','android/text/Html.class'
)

# NOTE: Suwayomi's getAndroid.ps1 also runs a "Dedupe" pass, but it matches on the
# source *filename incl. extension* (e.g. "Application.java.class") so it never actually
# matches a stub entry — it is a no-op. We omit it: the 7 explicit overrides above are the
# real removals, and any other override source simply takes precedence at compile time
# while its stub coexists harmlessly on the classpath (same as upstream).

$libDir = Join-Path $root "android-compat\lib"
New-Item -ItemType Directory -Force -Path $libDir | Out-Null
Move-Item -Force $jar (Join-Path $libDir "android.jar")
Remove-Item -Recurse -Force $tmp
Write-Output "Done -> android-compat/lib/android.jar"
