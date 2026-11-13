param([Parameter(Mandatory = $true)][string]$StateDirectory)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$config = Get-Content -LiteralPath (Join-Path $StateDirectory 'config.json') -Raw | ConvertFrom-Json
$form = New-Object Windows.Forms.Form
$form.Text = 'Scrapling 連線設定'
$form.ClientSize = New-Object Drawing.Size(550, 210)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$label = New-Object Windows.Forms.Label
$label.Text = '在 X1hub 來源設定填入以下位址與金鑰。金鑰只在這台電腦讀取。'
$label.SetBounds(16, 16, 520, 40)
$form.Controls.Add($label)
$address = New-Object Windows.Forms.TextBox
$address.Text = "http://$($config.bind):$($config.port)"
$address.ReadOnly = $true
$address.SetBounds(16, 60, 390, 26)
$form.Controls.Add($address)
$copyAddress = New-Object Windows.Forms.Button
$copyAddress.Text = '複製位址'
$copyAddress.SetBounds(420, 58, 110, 30)
$copyAddress.Add_Click({ [Windows.Forms.Clipboard]::SetText($address.Text) })
$form.Controls.Add($copyAddress)
$key = New-Object Windows.Forms.TextBox
$key.Text = $config.api_key
$key.UseSystemPasswordChar = $true
$key.ReadOnly = $true
$key.SetBounds(16, 106, 390, 26)
$form.Controls.Add($key)
$copyKey = New-Object Windows.Forms.Button
$copyKey.Text = '複製金鑰'
$copyKey.SetBounds(420, 104, 110, 30)
$copyKey.Add_Click({ [Windows.Forms.Clipboard]::SetText($key.Text) })
$form.Controls.Add($copyKey)
$note = New-Object Windows.Forms.Label
$note.Text = '電腦需保持在線；行動數據需先建立可到達電腦的私人網路。'
$note.SetBounds(16, 155, 520, 40)
$form.Controls.Add($note)
[void]$form.ShowDialog()
$key.Clear()
$form.Dispose()
