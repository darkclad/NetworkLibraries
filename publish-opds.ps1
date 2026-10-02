<#
  Publish an OPDS Library build to the self-hosted distribution server.

  Builds the APK, copies it to \\SERVERNASN3\appdata\dist\opds
  (served at https://dist.darkclad.org/opds/ via Cloudflare tunnel), and writes an
  index.json manifest (versionCode + sha256 + url) that the in-app updater reads.

  APK names are zero-padded (opds-00005.apk) so any lexicographic directory listing
  sorts the newest build last (same convention as the MyNavvy pipeline).

  Usage:
    .\publish-opds.ps1                    # build release + publish
    .\publish-opds.ps1 -SkipBuild         # publish the already-built release APK
    .\publish-opds.ps1 -Bump              # increment versionCode, then build + publish
    .\publish-opds.ps1 -VersionName 1.1   # set a new versionName, then build + publish

  NOTE: the FIELD app is the RELEASE variant (applicationId com.example.opdslibrary),
  signed with release-keystore.jks. That keystore is gitignored (NOT in the repo) and lives
  only on this machine -- BACK IT UP. As long as the same keystore signs each build, the
  in-app updater installs new builds in place, preserving all data (its password is in
  app/build.gradle.kts). A NEW build needs a NEW versionCode (use -Bump) or the updater
  won't offer it.

  The device was migrated from the old DEBUG variant (com.example.opdslibrary.debug) to
  this release package on 2026-08-10 (data copied across via run-as). The 'debug' channel
  now builds a DIFFERENT, separate package that does NOT share the release app's data --
  only use it for local Android Studio testing.
#>
param(
  [ValidateSet('release','debug')][string]$BuildType = 'release',
  [switch]$SkipBuild,
  [switch]$Bump,                 # increment versionCode by 1 before building
  [string]$VersionName           # optionally set a new versionName, e.g. "1.1"
)
$ErrorActionPreference = 'Stop'
$proj    = 'D:\Work\Programming\Android\opdsLibrary'
$gradle  = "$proj\app\build.gradle.kts"
$distDir = 'V:\dist\opds'                          # = /mnt/user/appdata/dist/opds
$baseUrl = 'https://dist.darkclad.org/opds'
# Cloudflare Access service token for the in-app updater to reach a gated /opds path.
# Read from the SOPS vault (secret 'distribution-cf') and embedded into the APK's BuildConfig.
$cfSecret = 'distribution-cf'

if (-not (Test-Path $distDir)) { throw "Dist share not reachable: $distDir (is V: mapped to \\SERVERNASN3\appdata?)" }

# 0. Optionally bump versionCode / versionName in the Gradle file, then continue.
if ($Bump -or $VersionName) {
  if ($SkipBuild) { throw "-Bump/-VersionName require a real build; do not combine with -SkipBuild." }
  # -Encoding UTF8: the gradle file is UTF-8; without this PS 5.1 reads it as the ANSI codepage
  # and WriteAllText below would re-encode any non-ASCII into mojibake.
  $src = Get-Content $gradle -Raw -Encoding UTF8
  if ($Bump) {
    $src = [regex]::Replace($src, '(versionCode\s*=\s*)(\d+)', { param($m) $m.Groups[1].Value + ([int]$m.Groups[2].Value + 1) })
  }
  if ($VersionName) {
    $src = [regex]::Replace($src, '(versionName\s*=\s*")[^"]*(")', ('${1}' + $VersionName + '${2}'))
  }
  [System.IO.File]::WriteAllText($gradle, $src, (New-Object System.Text.UTF8Encoding($false)))
}

# 1. Parse version from the Gradle build file (authoritative source).
$g = Get-Content $gradle -Raw -Encoding UTF8
$verCode = [regex]::Match($g, 'versionCode\s*=\s*(\d+)').Groups[1].Value
$verName = [regex]::Match($g, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $verCode) { throw "Could not parse versionCode from build.gradle.kts" }
# The manifest 'package' must match the INSTALLED app's applicationId. Debug appends '.debug'.
$pkg = if ($BuildType -eq 'debug') { 'com.example.opdslibrary.debug' } else { 'com.example.opdslibrary' }
Write-Host "OPDS Library $verName (versionCode $verCode) [$BuildType] -> $pkg" -ForegroundColor Cyan

# 2. Build.
if (-not $SkipBuild) {
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  $task = if ($BuildType -eq 'release') { ':app:assembleRelease' } else { ':app:assembleDebug' }
  # Embed the Cloudflare Access service token so the shipped APK's updater can fetch a gated
  # /opds path over WAN. A build without it silently loses off-LAN updates, so missing = fail.
  $cfArgs = @()
  if ($BuildType -eq 'release') {
    $cid  = (secret get $cfSecret cf_access_client_id)     -replace '^CF-Access-Client-Id:\s*', ''
    $csec = (secret get $cfSecret cf_access_client_secret) -replace '^CF-Access-Client-Secret:\s*', ''
    if (-not $cid -or -not $csec) { throw "Could not read the Cloudflare Access token from vault secret '$cfSecret'" }
    $cfArgs = @("-PCF_ACCESS_CLIENT_ID=$($cid.Trim())", "-PCF_ACCESS_CLIENT_SECRET=$($csec.Trim())")
    Write-Host "  (embedding Cloudflare Access service token for the updater)" -ForegroundColor DarkGray
  }
  & "$proj\gradlew.bat" -p $proj $task @cfArgs --console=plain
  if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
}

# 3. Locate the built APK.
$apk = Get-ChildItem "$proj\app\build\outputs\apk\$BuildType\*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $apk) { throw "APK not found under app\build\outputs\apk\$BuildType" }

# 4. Copy to the dist folder under a ZERO-PADDED versioned name (lexicographic == numeric).
$destName = "opds-{0:D5}.apk" -f [int]$verCode
$dest = Join-Path $distDir $destName
Copy-Item $apk.FullName $dest -Force
$sha  = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $dest).Length

# 5. Write the manifest the in-app updater reads.
$json = [ordered]@{
  package      = $pkg
  versionName  = $verName
  versionCode  = [int]$verCode
  buildType    = $BuildType
  apk          = "$baseUrl/$destName"
  sha256       = $sha
  size         = $size
  publishedUtc = (Get-Date).ToUniversalTime().ToString('o')
} | ConvertTo-Json
# Write UTF-8 WITHOUT BOM (PS 5.1's -Encoding utf8 adds a BOM that breaks strict JSON parsers).
[System.IO.File]::WriteAllText((Join-Path $distDir 'index.json'), $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "Published -> $baseUrl/$destName" -ForegroundColor Green
Write-Host ("  sha256 {0}" -f $sha)
Write-Host ("  size   {0} MB" -f ([math]::Round($size/1MB,1)))
Write-Host "The app's in-app updater will offer this build the next time OPDS Library starts."
