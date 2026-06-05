$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$logPath = Join-Path $root 'dev-server-live.log'
$node = 'E:\Develop\NodeJs\node.exe'
$vite = Join-Path $root 'node_modules\vite\bin\vite.js'

Set-Location $root

"[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] starting vite dev server" | Out-File -FilePath $logPath -Encoding UTF8
& $node $vite --host 0.0.0.0 --port 5173 2>&1 | Tee-Object -FilePath $logPath -Append
