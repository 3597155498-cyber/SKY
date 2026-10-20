# T09 verification: order state machine + conditional update under concurrency
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

Add-Type -AssemblyName System.Net.Http

$user = Invoke-RestMethod -Uri "$base/user/user/login" -Method Post -ContentType "application/json" -Body '{"code":"test_code_001"}'
$userToken = $user.data.token
$uid = $user.data.id
$addrId = [int](Sql "SELECT id FROM address_book WHERE user_id=$uid LIMIT 1")
$admin = Invoke-RestMethod -Uri "$base/admin/employee/login" -Method Post -ContentType "application/json" -Body '{"username":"admin","password":"123456"}'
$adminToken = $admin.data.token

function New-PaidOrder([int]$idx) {
  Sql "DELETE FROM shopping_cart WHERE user_id=$uid" | Out-Null
  Sql "INSERT INTO shopping_cart (name,user_id,dish_id,number,amount,create_time) VALUES ('t',$uid,46,1,10,NOW())" | Out-Null
  $body = '{"addressBookId":' + $addrId + ',"payMethod":1,"remark":"t09","deliveryStatus":1,"tablewareNumber":1,"tablewareStatus":1,"packAmount":1,"amount":10}'
  $o = Invoke-RestMethod -Uri "$base/user/order/submit" -Method Post -ContentType "application/json" -Headers @{authentication=$userToken; "Idempotency-Key"="t09-$run-$idx"} -Body $body
  Sql "UPDATE orders SET status=2, pay_status=1 WHERE id=$($o.data.id)" | Out-Null
  return [int]$o.data.id
}

$illegal = 0
$bothOk = 0
$rounds = 10
for ($i = 0; $i -lt $rounds; $i++) {
  $orderId = New-PaidOrder $i
  # 商家接单 vs 用户取消 并发
  $c1 = New-Object System.Net.Http.HttpClient
  $c1.DefaultRequestHeaders.Add("token", $adminToken)
  $t1 = $c1.PutAsync("$base/admin/order/confirm", (New-Object System.Net.Http.StringContent('{"id":' + $orderId + '}', [System.Text.Encoding]::UTF8, "application/json")))

  $c2 = New-Object System.Net.Http.HttpClient
  $c2.DefaultRequestHeaders.Add("authentication", $userToken)
  $t2 = $c2.PutAsync("$base/user/order/cancel/$orderId", (New-Object System.Net.Http.StringContent("", [System.Text.Encoding]::UTF8, "application/json")))

  [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]@($t1, $t2))
  $r1 = ($t1.Result.Content.ReadAsStringAsync().Result | ConvertFrom-Json).code
  $r2 = ($t2.Result.Content.ReadAsStringAsync().Result | ConvertFrom-Json).code
  $c1.Dispose(); $c2.Dispose()

  $status = [int](Sql "SELECT status FROM orders WHERE id=$orderId")
  if (-not ($status -eq 3 -or $status -eq 6)) { $illegal++ }
  if (($r1 -eq 1) -and ($r2 -eq 1)) { $bothOk++ }
  if ($i -eq 0) { Write-Host "round0: confirmCode=$r1 cancelCode=$r2 finalStatus=$status" }
}

Write-Host "T09 rounds=$rounds illegalFinalStatus=$illegal bothSucceeded=$bothOk"
Check "T09 final status always legal" $illegal 0
Check "T09 only one side wins per round" $bothOk 0

# 非法流转：已接单订单不能被"接单"二次处理
$orderId = New-PaidOrder 99
Invoke-RestMethod -Uri "$base/admin/order/confirm" -Method Put -ContentType "application/json" -Headers @{token=$adminToken} -Body ('{"id":' + $orderId + '}') | Out-Null
$second = Invoke-RestMethod -Uri "$base/admin/order/confirm" -Method Put -ContentType "application/json" -Headers @{token=$adminToken} -Body ('{"id":' + $orderId + '}')
Write-Host "T09 duplicate confirm code=$($second.code)"
Check "T09 duplicate confirm rejected" $second.code 0

Write-Host "---- t09 done ----"
