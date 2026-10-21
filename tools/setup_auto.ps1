# 安装「自动更新节点」的启动项 / 计划任务。
#
# 默认（不需要管理员）：往启动文件夹放一个 run_hidden.vbs，
#   登录后等 30 秒、静默跑一次 daily_auto.ps1。
#   脚本内部有 6 小时闸门，所以反复登录也不会重复跑。
#
# 可选升级（需要管理员，会弹一次 UAC）：注册计划任务，额外获得
#   · 用电池也跑（笔记本没插电时不会被跳过）
#   · 跑到一半拔电源不会被杀
#   · 每天 08:00 再试一次，八点没开机的话开机后补跑（StartWhenAvailable）
#   · 一直不关机也每 8 小时刷一次
#   注册成功后会把启动文件夹里那份删掉，避免两套机制并存。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File D:\Walls\tools\setup_auto.ps1            # 只装启动项
#   （右键「以管理员身份运行」后再执行同一条命令）                                     # 升级为计划任务

$ErrorActionPreference = 'Stop'

$Tools = 'D:\Walls\tools'
$Ps1 = Join-Path $Tools 'daily_auto.ps1'
$Vbs = Join-Path $Tools 'run_hidden.vbs'
$TaskName = 'AutoFreeNodes'
$StartupDir = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Startup'
$StartupVbs = Join-Path $StartupDir 'AutoFreeNodes.vbs'

if (-not (Test-Path $Ps1)) {
    Write-Host "找不到脚本: $Ps1" -ForegroundColor Red
    exit 2
}
if (-not (Test-Path $Vbs)) {
    Write-Host "找不到 $Vbs" -ForegroundColor Red
    exit 2
}

$isAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

# ---------------------------------------------------------------- 1) 启动项
function Install-Startup {
    New-Item -ItemType Directory -Path $StartupDir -Force | Out-Null
    $content = 'CreateObject("WScript.Shell").Run "wscript.exe ""' + $Vbs + '""", 0, False'
    Set-Content -Path $StartupVbs -Value $content -Encoding ASCII
    Write-Host "已安装登录自启动: $StartupVbs" -ForegroundColor Green
    Write-Host "  · 登录后等 30 秒静默跑一次（不需要管理员，不闪窗）"
    Write-Host "  · 距上次成功不足 6 小时会自动跳过"
}

# ------------------------------------------------------------ 2) 计划任务
function Install-Task {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $xml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Author>szech</Author>
    <Description>自动抓取免费节点、测速、排序并同步进 Clash Verge。登录后 45 秒跑一次、之后每 8 小时一次；每天 08:00 也试一次，错过会在开机后补跑。6 小时内不重复跑。</Description>
    <URI>\$TaskName</URI>
  </RegistrationInfo>
  <Principals>
    <Principal id="Author">
      <UserId>$sid</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <StartWhenAvailable>true</StartWhenAvailable>
    <ExecutionTimeLimit>PT45M</ExecutionTimeLimit>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
  </Settings>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <Delay>PT45S</Delay>
      <Repetition>
        <Interval>PT8H</Interval>
        <Duration>P3650D</Duration>
        <StopAtDurationEnd>false</StopAtDurationEnd>
      </Repetition>
    </LogonTrigger>
    <CalendarTrigger>
      <Enabled>true</Enabled>
      <StartBoundary>2026-10-22T08:00:00</StartBoundary>
      <ScheduleByDay>
        <DaysInterval>1</DaysInterval>
      </ScheduleByDay>
    </CalendarTrigger>
  </Triggers>
  <Actions Context="Author">
    <Exec>
      <Command>wscript.exe</Command>
      <Arguments>"$Vbs"</Arguments>
    </Exec>
  </Actions>
</Task>
"@
    $tmp = Join-Path $env:TEMP 'autofreenodes-task.xml'
    [IO.File]::WriteAllText($tmp, $xml, [Text.Encoding]::Unicode)
    & schtasks /create /tn $TaskName /xml $tmp /f | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "注册计划任务失败（exit $LASTEXITCODE）" -ForegroundColor Red
        exit 3
    }
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    Write-Host "已注册计划任务: $TaskName" -ForegroundColor Green
    Write-Host "  · 登录后 45 秒一次，之后每 8 小时一次"
    Write-Host "  · 每天 08:00 也试一次，错过会在开机后补跑"
    Write-Host "  · 用电池也跑，跑到一半拔电源不会被杀"
}

# ---------------------------------------------------------------- 主流程

if ($isAdmin) {
    Install-Task
    # 两套机制并存只会互相触发（虽然闸门会挡住重复跑），删掉启动项更干净
    if (Test-Path $StartupVbs) {
        Remove-Item $StartupVbs -Force
        Write-Host "已移除启动文件夹里那份（改由计划任务负责）" -ForegroundColor Yellow
    }
} else {
    Install-Startup
    Write-Host ""
    Write-Host "想更省心（用电池也跑 / 08:00 错过补跑 / 一直不关机也每 8 小时刷）：" -ForegroundColor Cyan
    Write-Host "  右键「以管理员身份运行」PowerShell，再执行同一条命令："
    Write-Host "  powershell -ExecutionPolicy Bypass -File D:\Walls\tools\setup_auto.ps1"
}

Write-Host ""
Write-Host "立刻跑一次（不等下次登录）：powershell -ExecutionPolicy Bypass -File $Ps1 -Force"
Write-Host "查看任务状态（若已注册）：schtasks /query /tn $TaskName /v /fo list"
Write-Host "卸载：删掉 $StartupVbs ；若注册过计划任务再加 schtasks /delete /tn $TaskName /f"
