# T04 verification: timeout orders via Redis ZSet delay queue
$ErrorActionPreference = 'Stop'
$mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$redisCli = "C:\Program Files\Redis\redis-cli.exe"
$env:MYSQL_PWD = "123456"
$base = "http://127.0.0.1:8080"
$run  = Get-Date -Format "MMddHHmmss"

function Sql([string]$q) {
  $out = & $mysql -uroot -D sky_take_out -N -B -e $q 2>$null
  return ($out | Where-Object { $_ -ne $null })
}
function Redis([string]$cmd) {
  $parts = $cmd -split '\s+'
  return (& $redisCli -n 1 @parts 2>$null)
}
function Check([string]$name,$actual,$expected) {
  if ("$actual" -eq "$expected") { Write-Host ("PASS  {0}: {1}" -f $name, $actual) }
  else { Write-Host ("FAIL  {0}: actual={1} expected={2}" -f $name, $actual, $expected) }
}

# reset
Sql "DELETE od FROM order_detail od JOIN orders o ON od.order_id=o.id" | Out-Null
Sql "DELETE FROM orders" | Out-Null
Sql "DELETE FROM stock_log" | Out-Null
Sql "DELETE FROM shopping_cart" | Out-Null
Sql "UPDATE dish SET stock=100" | Out-Null
& $redisCli -n 1 del "order:timeout" 2>$null | Out-Null

$login = Invoke-RestMethod -Uri "$base/user/user/login" -Method Post -ContentType "application/json" -Body '{"code":"t04_user"}'
$token = $login.data.token
$uid = $login.data.id
Sql "INSERT INTO address_book (user_id,consignee,sex,phone,province_code,province_name,city_code,city_name,district_code,district_name,detail,label,is_default) SELECT $uid,'T04','1','13900000000','11','Beijing','1101','Beijing','110101','Dongcheng','T04Addr','home',1 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM address_book WHERE user_id=$uid)" | Out-Null
$addrId = [int](Sql "SELECT id FROM address_book WHERE user_id=$uid LIMIT 1")

function New-Order([string]$key) {
  Sql "DELETE FROM shopping_cart WHERE user_id=$uid" | Out-Null
  Sql "INSERT INTO shopping_cart (name,user_id,dish_id,number,amount,create_time) VALUES ('t',$uid,46,2,10,NOW())" | Out-Null
  $body = '{"addressBookId":' + $addrId + ',"payMethod":1,"remark":"t04","deliveryStatus":1,"tablewareNumber":1,"tablewareStatus":1,"packAmount":1,"amount":10}'
  return Invoke-RestMethod -Uri "$base/user/order/submit" -Method Post -ContentType "application/json" -Headers @{authentication=$token; "Idempotency-Key"=$key} -Body $body
}

# ---------- V1: order enters ZSet with future score ----------
$o = New-Order "t04-v1-$run"
$orderId = $o.data.id
$score = [double](Redis "zscore order:timeout $orderId")
$expectScore = [DateTimeOffset]::Now.ToUnixTimeMilliseconds() + 15*60*1000
$delta = [math]::Abs($score - $expectScore)
Write-Host "V1  orderId=$orderId zsetScore=$score deltaMs=$delta stockAfterSubmit=$((Sql "SELECT stock FROM dish WHERE id=46"))"
Check "V1 order created" $o.code 1
Check "V1 in timeout zset" ($score -gt 0) $true
Check "V1 score ~ now+15min" ($delta -lt 60000) $true
Check "V1 stock deducted" (Sql "SELECT stock FROM dish WHERE id=46") 98

# ---------- V2: simulate expiry -> cancelled + stock restored + TIMEOUT log ----------
$past = [DateTimeOffset]::Now.ToUnixTimeMilliseconds() - 5000
& $redisCli -n 1 zadd "order:timeout" $past $orderId 2>$null | Out-Null
Start-Sleep -Seconds 3
$status = Sql "SELECT status FROM orders WHERE id=$orderId"
$reasonCnt = Sql "SELECT COUNT(*) FROM orders WHERE id=$orderId AND cancel_reason IS NOT NULL"
$stock = Sql "SELECT stock FROM dish WHERE id=46"
$timeoutLogs = Sql "SELECT COUNT(*) FROM stock_log WHERE order_id=$orderId AND biz_type='TIMEOUT'"
$zremCnt = Sql "SELECT 1"
Write-Host "V2  status=$status reasonSet=$reasonCnt stock=$stock timeoutLogs=$timeoutLogs remainingInZset=$((Redis "zcard order:timeout"))"
Check "V2 order cancelled" $status 6
Check "V2 cancel reason set" $reasonCnt 1
Check "V2 stock restored" $stock 100
Check "V2 TIMEOUT stock_log" $timeoutLogs 1
Check "V2 removed from zset" (Redis "zcard order:timeout") 0

# ---------- V3: replay the same member must not double-restore ----------
& $redisCli -n 1 zadd "order:timeout" $past $orderId 2>$null | Out-Null
Start-Sleep -Seconds 3
$timeoutLogs2 = Sql "SELECT COUNT(*) FROM stock_log WHERE order_id=$orderId AND biz_type='TIMEOUT'"
$stock2 = Sql "SELECT stock FROM dish WHERE id=46"
Write-Host "V3  timeoutLogs=$timeoutLogs2 stock=$stock2"
Check "V3 no duplicate restore" $timeoutLogs2 1
Check "V3 stock unchanged" $stock2 100

# ---------- V4: paid order must NOT be cancelled by timeout ----------
$o2 = New-Order "t04-v4-$run"
$orderId2 = $o2.data.id
Sql "UPDATE orders SET status=2, pay_status=1 WHERE id=$orderId2" | Out-Null
$stockBeforePaidCase = Sql "SELECT stock FROM dish WHERE id=46"
& $redisCli -n 1 zadd "order:timeout" $past $orderId2 2>$null | Out-Null
Start-Sleep -Seconds 3
$status2 = Sql "SELECT status FROM orders WHERE id=$orderId2"
$stock3 = Sql "SELECT stock FROM dish WHERE id=46"
$logs2 = Sql "SELECT COUNT(*) FROM stock_log WHERE order_id=$orderId2 AND biz_type='TIMEOUT'"
Write-Host "V4  paidOrderStatus=$status2 stock=$stock3 timeoutLogs=$logs2"
Check "V4 paid order not cancelled" $status2 2
Check "V4 paid order not restored" $stock3 $stockBeforePaidCase
Check "V4 no TIMEOUT log for paid order" $logs2 0

Write-Host "---- t04 done ----"
