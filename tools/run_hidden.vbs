' 登录后静默跑一次 D:\Walls\tools\daily_auto.ps1。
'
' 放在启动文件夹里，所以不需要管理员权限、也不闪黑窗。
' 前面的 30 秒是等网络栈起来：登录瞬间 Wi-Fi 常常还没连上，
' 那会儿去抓源会全失败，白烧一轮（脚本只在成功后记时间，但不该白白浪费）。
'
' 真正的去重在 Python 里：距上次成功不足 6 小时会立刻退出，所以
' 开机、注销再登录、反复登录都不会重复跑。
Dim shell, fso
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

If Not fso.FileExists("D:\Walls\tools\daily_auto.ps1") Then
    WScript.Quit 0
End If

WScript.Sleep 30000

shell.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""D:\Walls\tools\daily_auto.ps1""", 0, False
