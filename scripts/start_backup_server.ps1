param([switch]$Background)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if ($Background) {
    $process = Start-Process powershell.exe -WindowStyle Hidden -PassThru -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'))
    Write-Output "Backup server process: $($process.Id)"
    exit
}
$config = Join-Path $root '电脑账目备份/账号服务/mail-config.json'
try {
    if (Test-Path -LiteralPath $config) {
        $mail = Get-Content -LiteralPath $config -Raw -Encoding UTF8 | ConvertFrom-Json
        $secure = ConvertTo-SecureString $mail.protectedPassword
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $env:LEDGER_SMTP_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
        $env:LEDGER_SMTP_USER = $mail.email
    }
    & (Join-Path $root '.venv/Scripts/python.exe') (Join-Path $PSScriptRoot 'local_backup_server.py')
} finally {
    Remove-Item Env:LEDGER_SMTP_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:LEDGER_SMTP_USER -ErrorAction SilentlyContinue
}
