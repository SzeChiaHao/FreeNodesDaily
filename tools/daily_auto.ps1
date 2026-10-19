param(
    [switch]$Force,
    [switch]$Quick
)

# Wrapper for daily_auto.py: runs it and captures UTF-8 logs.

$Tools = $PSScriptRoot
$LogDir = Join-Path $Tools 'logs'
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $LogDir "daily-$stamp.log"

# 不再写死某个人的 python.exe：优先 PATH 里的 python，其次 py 启动器
$py = (Get-Command python -ErrorAction SilentlyContinue).Source
$pyArgs = @()
if (-not $py) {
    $py = (Get-Command py -ErrorAction SilentlyContinue).Source
    if ($py) { $pyArgs += '-3' }
}
if (-not $py) {
    Write-Host "Python not found: 请安装 Python 并加入 PATH"
    exit 2
}

$env:PYTHONIOENCODING = 'utf-8'
$pyArgs += @('-u', (Join-Path $Tools 'daily_auto.py'))
if ($Force) { $pyArgs += '--force' }
if ($Quick) { $pyArgs += '--quick' }

$p = Start-Process -FilePath $py -ArgumentList $pyArgs `
    -WindowStyle Hidden -WorkingDirectory $Tools `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
    -PassThru -Wait
$code = $p.ExitCode

# Clean up old logs, keep latest 30
Get-ChildItem -Path $LogDir -Filter 'daily-*.log*' |
    Sort-Object LastWriteTime -Descending |
    Select-Object -Skip 30 |
    Remove-Item -Force -ErrorAction SilentlyContinue

if ($code -eq 0) {
    Write-Host "daily_auto done. Log: $log"
} else {
    Write-Host "daily_auto failed (exit $code). Log: $log"
}
exit $code
