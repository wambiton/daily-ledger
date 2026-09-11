param([switch]$Background, [switch]$Restart)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$folder = Join-Path $root '电脑账目备份/账号服务'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$script = Join-Path $PSScriptRoot 'local_backup_server.py'
$python = Join-Path $root '.venv/Scripts/python.exe'
$config = Join-Path $folder 'mail-config.json'
try {
    if (Test-Path -LiteralPath $config) {
        $mail = Get-Content -LiteralPath $config -Raw -Encoding UTF8 | ConvertFrom-Json
        $secure = ConvertTo-SecureString $mail.protectedPassword
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $env:LEDGER_SMTP_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        $env:LEDGER_SMTP_USER = $mail.email
    }
    if ($Restart) {
        $running = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'python.exe' -and $_.CommandLine -like ('*' + $script + '*') }
        foreach ($item in $running) { Stop-Process -Id $item.ProcessId -ErrorAction SilentlyContinue }
    }
    if ($Background) {
        $process = Start-Process -FilePath $python -WindowStyle Hidden -PassThru -ArgumentList @('-u', ('"' + $script + '"')) -RedirectStandardOutput (Join-Path $folder 'server-output.log') -RedirectStandardError (Join-Path $folder 'server-error.log')
        $ready = $false
        for ($attempt = 0; $attempt -lt 20; $attempt++) {
            Start-Sleep -Milliseconds 500
            $process.Refresh()
            if ($process.HasExited) { break }
            try {
                $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/api/v1/health/' -TimeoutSec 2
                if ($health.service -eq 'personal-ledger' -and $health.database) { $ready = $true; break }
            } catch { }
        }
        if (-not $ready) { throw '服务未启动。请检查电脑账目备份/账号服务/server-error.log。' }
        Write-Output '电脑备份服务已启动：http://127.0.0.1:8765'
        Write-Output '请保持电脑开机；当前仅支持本机或USB连接。'
        Write-Output ('SMTP配置已加载：' + $health.mail_configured + '（是否有效需要实际发送验证）')
    } else {
        & $python $script
        if ($LASTEXITCODE -ne 0) { throw '备份服务已异常退出，请查看上方错误。' }
    }
} finally {
    Remove-Item Env:LEDGER_SMTP_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:LEDGER_SMTP_USER -ErrorAction SilentlyContinue
}
