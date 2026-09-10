param([string]$Email = '')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
$root = Split-Path $PSScriptRoot -Parent
$folder = Join-Path $root '电脑账目备份/账号服务'
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$form = New-Object Windows.Forms.Form
$form.Text = 'QQ SMTP - local configuration'
$form.Width = 480; $form.Height = 260; $form.StartPosition = 'CenterScreen'
$label = New-Object Windows.Forms.Label
$label.Text = 'QQ email:'; $label.SetBounds(20,20,420,20)
$emailBox = New-Object Windows.Forms.TextBox
$emailBox.Text = $Email; $emailBox.SetBounds(20,45,420,25)
$hint = New-Object Windows.Forms.Label
$hint.Text = 'SMTP authorization code (not your QQ password):'; $hint.SetBounds(20,80,420,20)
$passwordBox = New-Object Windows.Forms.TextBox
$passwordBox.UseSystemPasswordChar = $true; $passwordBox.SetBounds(20,105,420,25)
$save = New-Object Windows.Forms.Button
$save.Text = 'Save locally'; $save.SetBounds(20,155,130,30)
$save.Add_Click({
    if ($emailBox.Text.Trim() -notmatch '^[^@\s]+@qq\.com$' -or [string]::IsNullOrWhiteSpace($passwordBox.Text)) {
        [Windows.Forms.MessageBox]::Show('Enter a QQ email and SMTP authorization code.') | Out-Null
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
