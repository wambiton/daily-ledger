param([string]$Email = '')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$root = Split-Path $PSScriptRoot -Parent
$folder = Join-Path $root '电脑账目备份/账号服务'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
if (-not $Email -and (Test-Path -LiteralPath (Join-Path $folder 'mail-config.json'))) {
    $Email = (Get-Content -LiteralPath (Join-Path $folder 'mail-config.json') -Raw -Encoding UTF8 | ConvertFrom-Json).email
}
$form = New-Object Windows.Forms.Form
$form.Text = 'QQ邮箱 SMTP 配置'
$form.Width = 480; $form.Height = 260; $form.StartPosition = 'CenterScreen'
$form.TopMost = $true
$form.Add_Shown({ $form.Activate(); $emailBox.Focus() })
$label = New-Object Windows.Forms.Label
$label.Text = 'QQ 发信邮箱：'; $label.SetBounds(20,20,420,20)
$emailBox = New-Object Windows.Forms.TextBox
$emailBox.Text = $Email; $emailBox.SetBounds(20,45,420,25)
$hint = New-Object Windows.Forms.Label
$hint.Text = 'SMTP 授权码（不是 QQ 登录密码）：'; $hint.SetBounds(20,80,420,20)
$passwordBox = New-Object Windows.Forms.TextBox
$passwordBox.UseSystemPasswordChar = $true; $passwordBox.SetBounds(20,105,420,25)
$save = New-Object Windows.Forms.Button
$save.Text = '保存到本机'; $save.SetBounds(20,155,130,30)
$save.Add_Click({
    if ($emailBox.Text.Trim() -notmatch '^[^@\s]+@qq\.com$' -or [string]::IsNullOrWhiteSpace($passwordBox.Text)) {
        [Windows.Forms.MessageBox]::Show('请填写 QQ 邮箱和 SMTP 授权码。') | Out-Null
        return
    }
    $protected = ConvertTo-SecureString $passwordBox.Text -AsPlainText -Force | ConvertFrom-SecureString
    @{ email = $emailBox.Text.Trim().ToLowerInvariant(); protectedPassword = $protected } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder 'mail-config.json') -Encoding UTF8
    $passwordBox.Clear()
    $form.DialogResult = [Windows.Forms.DialogResult]::OK
    $form.Close()
})
$form.Controls.AddRange(@($label,$emailBox,$hint,$passwordBox,$save))
$form.AcceptButton = $save
[void]$form.ShowDialog()
$form.Dispose()
