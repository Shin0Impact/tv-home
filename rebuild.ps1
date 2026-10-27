# One command: commit + push the code, wait for the GitHub build, install it on the TV.
# Usage:  .\rebuild.ps1                 (TV at 10.0.0.3)
#         .\rebuild.ps1 -Tv 10.0.0.20
#         .\rebuild.ps1 -Message "what changed"
param([string]$Tv = "10.0.0.3", [string]$Message = "update")
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Set-Location $PSScriptRoot

git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) { git commit -q -m $Message }
git push -q origin main
if ($LASTEXITCODE -ne 0) { throw "git push failed" }
$sha = (git rev-parse HEAD).Trim()
Write-Host "Pushed $($sha.Substring(0,7)). Waiting for the build..."

$api = "https://api.github.com/repos/Shin0Impact/tv-home/actions/runs?head_sha=$sha"
$ok = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 10
    try { $run = (Invoke-RestMethod -Uri $api -Headers @{ "User-Agent" = "rebuild" }).workflow_runs | Select-Object -First 1 } catch { continue }
    if ($null -eq $run) { continue }
    if ($run.status -eq "completed") {
        if ($run.conclusion -eq "success") { $ok = $true } else { Write-Host "Build FAILED: $($run.html_url)" }
        break
    }
}
if (-not $ok) { throw "No successful build (open the link above, download the log and send it to Claude)." }
Write-Host "Build done."

while ($true) {
    adb connect "${Tv}:5555" | Out-Null
    $state = (adb -s "${Tv}:5555" get-state 2>&1) -join ""
    if ($state -match "device") { break }
    $Tv = Read-Host "Cannot reach the TV. What is its IP now"
}
& "$PSScriptRoot\install.ps1" -Tv $Tv
adb -s "${Tv}:5555" shell cmd notification allow_listener app.tvhome/app.tvhome.MediaListener | Out-Null
Write-Host "TV Home updated on $Tv."
