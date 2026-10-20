# T05 verification: WebSocket concurrency safety + reminder message
$ErrorActionPreference = 'Stop'
$mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$env:MYSQL_PWD = "123456"
$base = "http://127.0.0.1:8080"
$run  = Get-Date -Format "MMddHHmmss"

function Sql([string]$q) {
  $out = & $mysql -uroot -D sky_take_out -N -B -e $q 2>$null
  return ($out | Where-Object { $_ -ne $null })
}
function Check([string]$name,$actual,$expected) {
  if ("$actual" -eq "$expected") { Write-Host ("PASS  {0}: {1}" -f $name, $actual) }
  else { Write-Host ("FAIL  {0}: actual={1} expected={2}" -f $name, $actual, $expected) }
}
function Connect-Ws([string]$sid) {
  $ws = New-Object System.Net.WebSockets.ClientWebSocket
  [void]$ws.ConnectAsync([Uri]("ws://127.0.0.1:8080/ws/" + $sid), [System.Threading.CancellationToken]::None).Wait(5000)
  if ($ws.State -ne [System.Net.WebSockets.WebSocketState]::Open) { throw ("ws connect failed: " + $ws.State) }
  return $ws
}
function Receive-Ws($ws,[int]$timeoutMs) {
  $buf = New-Object byte[] 8192
  $seg = New-Object System.ArraySegment[byte] -ArgumentList @(,$buf)
  $cts = New-Object System.Threading.CancellationTokenSource
  $cts.CancelAfter($timeoutMs)
  try {
    $task = $ws.ReceiveAsync($seg, $cts.Token)
    if ($task.Wait($timeoutMs + 1000)) {
      return [System.Text.Encoding]::UTF8.GetString($buf, 0, $task.Result.Count)
    }
    return ""
  } catch { return "" }
}

# ---------- V1: single client receives reminder message ----------
$uid = [int](Sql "SELECT user_id FROM orders ORDER BY id DESC LIMIT 1")
$orderId = [int](Sql "SELECT id FROM orders ORDER BY id DESC LIMIT 1")
if (-not $orderId) {
  # 没有订单时临时插一条，仅用于催单消息投递
  Sql "INSERT INTO orders (number,status,user_id,address_book_id,order_time,pay_method,pay_status,amount,phone,address,consignee) VALUES ('t05-$run',2,11,10,NOW(),1,1,10,'13900000000','Addr','T05')" | Out-Null
  $orderId = [int](Sql "SELECT id FROM orders ORDER BY id DESC LIMIT 1")
}
$login = Invoke-RestMethod -Uri "$base/user/user/login" -Method Post -ContentType "application/json" -Body '{"code":"t05_user"}'
$token = $login.data.token

$ws1 = Connect-Ws ("t05-" + $run)
Start-Sleep -Milliseconds 300
Invoke-RestMethod -Uri "$base/user/order/reminder/$orderId" -Headers @{authentication=$token} | Out-Null
$msg = Receive-Ws $ws1 6000
Write-Host ("V1  received=" + $msg)
Check "V1 message received" ([string]::IsNullOrEmpty($msg)) $false
Check "V1 reminder type=2" ($msg -like '*"type":2*') $true

# ---------- V2: 50 concurrent connections, then close, broadcast still works ----------
$clients = @()
for ($i=0; $i -lt 50; $i++) { $clients += (Connect-Ws ("t05-bulk-$run-$i")) }
Start-Sleep -Milliseconds 500
foreach ($c in $clients) { try { $c.Dispose() } catch {} }
Start-Sleep -Seconds 2
Write-Host "V2  connected and closed 50 clients"

Invoke-RestMethod -Uri "$base/user/order/reminder/$orderId" -Headers @{authentication=$token} | Out-Null
$msg2 = Receive-Ws $ws1 6000
Write-Host ("V2  control client still receives=" + $msg2)
Check "V2 broadcast after mass disconnect" ($msg2 -like '*"type":2*') $true

# ---------- V3: send to a closed session should be cleaned up, no exception ----------
try {
  $ws1.Dispose()
  Start-Sleep -Seconds 1
  $r = Invoke-RestMethod -Uri "$base/user/order/reminder/$orderId" -Headers @{authentication=$token}
  Check "V3 reminder after client gone" $r.code 1
} catch {
  Check "V3 reminder after client gone" $_.Exception.Message 1
}

Write-Host "---- t05 done ----"
