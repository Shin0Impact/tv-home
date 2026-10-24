# Downloads the newest TV Home build from GitHub and installs it on the TV.
# Usage:  .\install.ps1            (uses 10.0.0.13)
#         .\install.ps1 -Tv 10.0.0.20
param([string]$Tv = "10.0.0.13")
$ErrorActionPreference = "Stop"
$url = "https://github.com/Shin0Impact/tv-home/releases/latest/download/tv-home.apk"
$apk = Join-Path $env:TEMP "tv-home.apk"
Write-Host "Downloading latest build..."
Invoke-WebRequest -Uri $url -OutFile $apk
adb connect "${Tv}:5555"
adb -s "${Tv}:5555" install -r $apk
Write-Host "Done."
