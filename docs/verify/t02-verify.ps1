# T02 verification: idempotent submit + dual stock (dish / setmeal) + stock_log restore
# Each run uses a unique idempotency key prefix so previous runs cannot interfere.
$ErrorActionPreference = 'Stop'
$mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$redisCli = "C:\Program Files\Redis\redis-cli.exe"
$env:MYSQL_PWD = "123456"
$base  = "http://127.0.0.1:8080"
$run   = Get-Date -Format "MMddHHmmss"

function Sql([string]$q) {
  $out = & $mysql -uroot -D sky_take_out -N -B -e $q 2>$null
  return ($out | Where-Object { $_ -ne $null })
}

function Login([string]$code) {
  $r = Invoke-RestMethod -Uri "$base/user/user/login" -Method Post -ContentType "application/json" -Body ('{"code":"' + $code + '"}')
  return [pscustomobject]@{ token = $r.data.token; uid = $r.data.id }
}

function Ensure-Address([int]$uid,[string]$tag) {
  $cnt = Sql "SELECT COUNT(*) FROM address_book WHERE user_id=$uid"
  if ([int]$cnt -eq 0) {
    Sql "INSERT INTO address_book (user_id,consignee,sex,phone,province_code,province_name,city_code,city_name,district_code,district_name,detail,label,is_default) VALUES ($uid,'$tag','1','13900000000','11','Beijing','1101','Beijing','110101','Dongcheng','Addr $tag','home',1)" | Out-Null
  }
  return [int](Sql "SELECT id FROM address_book WHERE user_id=$uid LIMIT 1")
}

function Reset-Data {
  Sql "DELETE od FROM order_detail od JOIN orders o ON od.order_id=o.id" | Out-Null
  Sql "DELETE FROM orders" | Out-Null
  Sql "DELETE FROM stock_log" | Out-Null
  Sql "DELETE FROM shopping_cart" | Out-Null
  Sql "UPDATE dish SET stock=100" | Out-Null
  Sql "UPDATE setmeal SET stock=100" | Out-Null
  # 清掉本测试历史幂等键（该版本 redis-cli 的 --scan 在 PowerShell 下无输出，用 KEYS）
  $keys = @(& $redisCli -n 1 keys "order:idem:*" 2>$null | Where-Object { $_ -and $_.Trim() -ne "" })
  if ($keys.Count -gt 0) { & $redisCli -n 1 del $keys 2>$null | Out-Null }
}

function Add-CartDish([int]$uid,[int]$dishId,[int]$num) {
  Sql "INSERT INTO shopping_cart (name,user_id,dish_id,number,amount,create_time) VALUES ('t',$uid,$dishId,$num,10,NOW())" | Out-Null
}
function Add-CartSetmeal([int]$uid,[int]$setmealId,[int]$num) {
  Sql "INSERT INTO shopping_cart (name,user_id,setmeal_id,number,amount,create_time) VALUES ('t',$uid,$setmealId,$num,10,NOW())" | Out-Null
}

Add-Type -AssemblyName System.Net.Http

function Submit([string]$token,[int]$addr,[string]$key) {
  $body = '{"addressBookId":' + $addr + ',"payMethod":1,"remark":"t02","deliveryStatus":1,"tablewareNumber":1,"tablewareStatus":1,"packAmount":1,"amount":10}'
  $h = @{ authentication = $token }
  if ($key) { $h['Idempotency-Key'] = $key }
  try {
    return Invoke-RestMethod -Uri "$base/user/order/submit" -Method Post -ContentType "application/json" -Body $body -Headers $h -TimeoutSec 20
  } catch {
    return [pscustomobject]@{ code = -1; msg = $_.Exception.Message; data = $null }
  }
}

function Submit-Many([object[]]$calls) {
  $tasks = @()
  foreach ($c in $calls) {
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Add("authentication", $c.token)
    $body = '{"addressBookId":' + $c.addr + ',"payMethod":1,"remark":"t02","deliveryStatus":1,"tablewareNumber":1,"tablewareStatus":1,"packAmount":1,"amount":10}'
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$base/user/order/submit")
    if ($c.key) { $req.Headers.Add("Idempotency-Key", $c.key) }
    $req.Content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, "application/json")
    $tasks += @{ client = $client; task = $client.SendAsync($req) }
  }
  $results = @()
  foreach ($t in $tasks) {
    $txt = $t.task.Result.Content.ReadAsStringAsync().Result
    try { $results += ($txt | ConvertFrom-Json) } catch { $results += [pscustomobject]@{ code = -1; msg = $txt } }
    $t.client.Dispose()
  }
  return $results
}

function Check([string]$name,$actual,$expected) {
  if ("$actual" -eq "$expected") { Write-Host ("PASS  {0}: {1}" -f $name, $actual) }
  else { Write-Host ("FAIL  {0}: actual={1} expected={2}" -f $name, $actual, $expected) }
}

$k1 = "t02-v1-$run"; $k2 = "t02-v2-$run"; $k3a = "t02-v3a-$run"; $k3b = "t02-v3b-$run"; $k4 = "t02-v4-$run"; $k5 = "t02-v5-$run"

$u1 = Login "test_code_001"
$u2 = Login "test_code_002"
$addr1 = Ensure-Address $u1.uid "U1"
$addr2 = Ensure-Address $u2.uid "U2"
Write-Host "run=$run users: u1=$($u1.uid) addr=$addr1 ; u2=$($u2.uid) addr=$addr2"

# ---------- V1 idempotency: 20 concurrent submits with the SAME key ----------
Reset-Data
Add-CartDish $u1.uid 46 2
$calls = @()
for ($i=0; $i -lt 20; $i++) { $calls += @{ token = $u1.token; addr = $addr1; key = $k1 } }
$res = Submit-Many $calls
$ok = @($res | Where-Object { $_.code -eq 1 })
$ids = @($ok | ForEach-Object { $_.data.id } | Sort-Object -Unique)
$orderCnt = (Sql "SELECT COUNT(*) FROM orders")
$stock46 = (Sql "SELECT stock FROM dish WHERE id=46")
$logCnt = (Sql "SELECT COUNT(*) FROM stock_log WHERE biz_type='PLACE'")
$busy = @($res | Where-Object { $_.code -eq 0 -and $_.msg -like "*处理中*" }).Count
Write-Host "V1  success=$($ok.Count)/20 busy=$busy distinctOrderIds=$($ids -join ',') orders=$orderCnt dish46Stock=$stock46 placeLogs=$logCnt"
Check "V1 orders created" $orderCnt 1
Check "V1 all successes share one order id" $ids.Count 1
Check "V1 stock deducted once" $stock46 98
Check "V1 stock_log rows" $logCnt 1

# V1b: retry the SAME key after commit must return the SAME order (true idempotency)
$replay = Submit $u1.token $addr1 $k1
$orderCnt = (Sql "SELECT COUNT(*) FROM orders")
Write-Host "V1b code=$($replay.code)  orderId=$($replay.data.id)  orders=$orderCnt"
Check "V1b replay returns same order" $replay.data.id $ids[0]
Check "V1b replay creates nothing" $orderCnt 1
Check "V1b stock still 98" (Sql "SELECT stock FROM dish WHERE id=46") 98

# ---------- V2 stock not enough ----------
Reset-Data
Sql "UPDATE dish SET stock=5 WHERE id=47" | Out-Null
Add-CartDish $u1.uid 47 6
$r = Submit $u1.token $addr1 $k2
$orderCnt = (Sql "SELECT COUNT(*) FROM orders")
$stock47 = (Sql "SELECT stock FROM dish WHERE id=47")
$cartCnt = (Sql "SELECT COUNT(*) FROM shopping_cart WHERE user_id=$($u1.uid)")
Write-Host "V2  code=$($r.code)  orders=$orderCnt  dish47Stock=$stock47  cartKept=$cartCnt"
Check "V2 rejected" $r.code 0
Check "V2 stock unchanged" $stock47 5
Check "V2 no order" $orderCnt 0
Check "V2 cart kept" $cartCnt 1

# ---------- V3 real race: two users, stock 5, each buys 3 -> no oversell ----------
Reset-Data
Sql "UPDATE dish SET stock=5 WHERE id=47" | Out-Null
Add-CartDish $u1.uid 47 3
Add-CartDish $u2.uid 47 3
$res = Submit-Many @(
  @{ token = $u1.token; addr = $addr1; key = $k3a },
  @{ token = $u2.token; addr = $addr2; key = $k3b }
)
$codes = ($res | ForEach-Object { $_.code }) -join ','
$orderCnt = (Sql "SELECT COUNT(*) FROM orders")
$stock47 = (Sql "SELECT stock FROM dish WHERE id=47")
Write-Host "V3  codes=$codes  orders=$orderCnt  dish47Stock=$stock47"
Check "V3 exactly one success" (@($res | Where-Object { $_.code -eq 1 }).Count) 1
Check "V3 one order" $orderCnt 1
Check "V3 stock 5-3=2, no negative" $stock47 2

# ---------- V4 setmeal dual stock: component dish sold out ----------
Reset-Data
Sql "UPDATE dish SET stock=0 WHERE id=48" | Out-Null
Add-CartSetmeal $u1.uid 32 1
$r = Submit $u1.token $addr1 $k4
$setmealStock = (Sql "SELECT stock FROM setmeal WHERE id=32")
$orderCnt = (Sql "SELECT COUNT(*) FROM orders")
Write-Host "V4  code=$($r.code)  setmeal32Stock=$setmealStock  orders=$orderCnt"
Check "V4 rejected by component dish" $r.code 0
Check "V4 setmeal stock unchanged" $setmealStock 100
Check "V4 no order" $orderCnt 0

# ---------- V5 setmeal success: setmeal + 8 component dishes deducted ----------
Reset-Data
Add-CartSetmeal $u1.uid 32 1
$r = Submit $u1.token $addr1 $k5
$setmealStock = (Sql "SELECT stock FROM setmeal WHERE id=32")
$dishLogs = (Sql "SELECT COUNT(*) FROM stock_log WHERE biz_type='PLACE' AND target_type='DISH'")
$setmealLogs = (Sql "SELECT COUNT(*) FROM stock_log WHERE biz_type='PLACE' AND target_type='SETMEAL'")
Write-Host "V5  code=$($r.code)  setmeal32Stock=$setmealStock  dishLogs=$dishLogs  setmealLogs=$setmealLogs"
Check "V5 setmeal order ok" $r.code 1
Check "V5 setmeal stock 99" $setmealStock 99
Check "V5 component dish logs" $dishLogs 8
Check "V5 setmeal log" $setmealLogs 1

# ---------- V6 cancel restores stock ----------
$orderId = $r.data.id
$beforeCancel = (Sql "SELECT stock FROM dish WHERE id=54")
Invoke-RestMethod -Uri "$base/user/order/cancel/$orderId" -Method Put -Headers @{authentication=$u1.token} | Out-Null
$afterCancel = (Sql "SELECT stock FROM dish WHERE id=54")
$setmealAfter = (Sql "SELECT stock FROM setmeal WHERE id=32")
$restoreLogs = (Sql "SELECT COUNT(*) FROM stock_log WHERE biz_type='CANCEL'")
Write-Host "V6  dish54 before=$beforeCancel after=$afterCancel  setmeal32 after=$setmealAfter  cancelLogs=$restoreLogs"
Check "V6 dish restored" $afterCancel ([int]$beforeCancel + 1)
Check "V6 setmeal restored" $setmealAfter 100
Check "V6 cancel logs" $restoreLogs 9

Write-Host "---- done ----"
