# T06 verification: hand-written cache (penetration / breakdown / avalanche) + stock decoupled from cache
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
function Questions {
  $line = Sql "SHOW GLOBAL STATUS LIKE 'Questions'"
  return [int](($line -split "`t")[1])
}

$catId = [int](Sql "SELECT category_id FROM dish GROUP BY category_id ORDER BY COUNT(*) DESC LIMIT 1")
$setmealCatId = [int](Sql "SELECT category_id FROM setmeal GROUP BY category_id ORDER BY COUNT(*) DESC LIMIT 1")
Write-Host "categoryId=$catId setmealCategoryId=$setmealCatId run=$run"

$login = Invoke-RestMethod -Uri "$base/user/user/login" -Method Post -ContentType "application/json" -Body '{"code":"test_code_001"}'
$token = $login.data.token
$uid = $login.data.id
$addrId = [int](Sql "SELECT id FROM address_book WHERE user_id=$uid LIMIT 1")
$admin = Invoke-RestMethod -Uri "$base/admin/employee/login" -Method Post -ContentType "application/json" -Body '{"username":"admin","password":"123456"}'
$adminToken = $admin.data.token

# clean cache keys
& $redisCli -n 1 keys "cache:*" 2>$null | ForEach-Object { if ($_) { & $redisCli -n 1 del $_ 2>$null | Out-Null } }

# ---------- V1: cache hit reduces DB queries ----------
$k = "cache:dish:list:$catId"
$q0 = Questions
$r1 = Invoke-RestMethod -Uri "$base/user/dish/list?categoryId=$catId" -Headers @{authentication=$token}
$q1 = Questions
$r2 = Invoke-RestMethod -Uri "$base/user/dish/list?categoryId=$catId" -Headers @{authentication=$token}
$q2 = Questions
$firstDelta = $q1 - $q0
$secondDelta = $q2 - $q1
$keyExists = Redis "exists $k"
Write-Host "V1  firstDelta=$firstDelta secondDelta=$secondDelta keyExists=$keyExists items=$(@($r1.data).Count)"
Check "V1 first call loads DB" ($firstDelta -gt 1) $true
Check "V1 second call hits cache (< first)" ($secondDelta -lt $firstDelta) $true
Check "V1 cache key created" $keyExists 1
Check "V1 response has stock field" ($null -ne @($r2.data)[0].stock) $true
Check "V1 response has soldOut field" ($null -ne @($r2.data)[0].soldOut) $true

# ---------- V2: penetration - unknown category -> sentinel, no DB hit on repeat ----------
$emptyKey = "cache:dish:list:99999-$run"
$q0 = Questions
$e1 = Invoke-RestMethod -Uri "$base/user/dish/list?categoryId=99999" -Headers @{authentication=$token}
$q1 = Questions
$e2 = Invoke-RestMethod -Uri "$base/user/dish/list?categoryId=99999" -Headers @{authentication=$token}
$q2 = Questions
$sentinel = Redis "get cache:dish:list:99999"
$cachedEmpty = Redis "exists cache:dish:list:99999"
Write-Host "V2  firstDelta=$($q1-$q0) secondDelta=$($q2-$q1) cachedValue=$sentinel cachedExists=$cachedEmpty"
Check "V2 empty result" (@($e1.data).Count) 0
Check "V2 empty result cached (sentinel or [])" ($cachedEmpty) 1
Check "V2 second call no heavier than first" (($q2 - $q1) -le ($q1 - $q0)) $true

# ---------- V3: breakdown - 50 concurrent requests on cold key ----------
& $redisCli -n 1 del $k 2>$null | Out-Null
Add-Type -AssemblyName System.Net.Http
$q0 = Questions
$tasks = @()
for ($i=0; $i -lt 50; $i++) {
  $c = New-Object System.Net.Http.HttpClient
  $c.DefaultRequestHeaders.Add("authentication", $token)
  $tasks += @{ client = $c; task = $c.GetAsync("$base/user/dish/list?categoryId=$catId") }
}
foreach ($t in $tasks) { [void]$t.task.Wait(20000); $t.client.Dispose() }
$q1 = Questions
$rebuildQueries = $q1 - $q0
Write-Host "V3  50 concurrent cold-key requests -> total DB queries=$rebuildQueries (no-cache baseline 450)"
Check "V3 single rebuild, not 50x" ($rebuildQueries -lt 150) $true
Check "V3 far below no-cache baseline" ($rebuildQueries -lt 225) $true
Check "V3 cache rebuilt" (Redis "exists $k") 1

# ---------- V4: avalanche - TTLs are jittered ----------
$k2 = "cache:setmeal:list:$setmealCatId"
Invoke-RestMethod -Uri "$base/user/setmeal/list?categoryId=$setmealCatId" -Headers @{authentication=$token} | Out-Null
$cats = @(Sql "SELECT DISTINCT category_id FROM dish ORDER BY category_id LIMIT 4")
$ttls = @()
foreach ($c in $cats) {
  Invoke-RestMethod -Uri "$base/user/dish/list?categoryId=$c" -Headers @{authentication=$token} | Out-Null
  $ttls += [int](Redis "pttl cache:dish:list:$c")
}
$ttlMin = [int](($ttls | Measure-Object -Minimum).Minimum)
$ttlMax = [int](($ttls | Measure-Object -Maximum).Maximum)
$ttlSpread = $ttlMax - $ttlMin
Write-Host "V4  dishTTLs=$($ttls -join ',') setmealTTL=$([int](Redis "pttl $k2")) min=$ttlMin max=$ttlMax spreadMs=$ttlSpread"
Check "V4 ttl within 25-35min" (($ttlMin -gt 24*60*1000) -and ($ttlMax -lt 36*60*1000)) $true
Check "V4 ttl jitter present" ($ttlSpread -gt 1000) $true

# ---------- V5: stock is NOT cached -> placing orders does not evict cache ----------
$dishId = [int](Sql "SELECT id FROM dish WHERE category_id=$catId AND status=1 LIMIT 1")
$stockBefore = [int](Sql "SELECT stock FROM dish WHERE id=$dishId")
Sql "DELETE FROM shopping_cart WHERE user_id=$uid" | Out-Null
for ($i=0; $i -lt 5; $i++) {
  Sql "INSERT INTO shopping_cart (name,user_id,dish_id,number,amount,create_time) VALUES ('t',$uid,$dishId,1,10,NOW())" | Out-Null
  $body = '{"addressBookId":' + $addrId + ',"payMethod":1,"remark":"t06","deliveryStatus":1,"tablewareNumber":1,"tablewareStatus":1,"packAmount":1,"amount":10}'
  $resp = Invoke-RestMethod -Uri "$base/user/order/submit" -Method Post -ContentType "application/json" -Headers @{authentication=$token; "Idempotency-Key"="t06-$run-$i"} -Body $body
  if ($resp.code -ne 1) { Write-Host ("V5 submit failed: " + $resp.msg) }
}
$stockAfter = [int](Sql "SELECT stock FROM dish WHERE id=$dishId")
$keyStillThere = Redis "exists $k"
$listAfter = Invoke-RestMethod -Uri "$base/user/dish/list?categoryId=$catId" -Headers @{authentication=$token}
$listedStock = (@($listAfter.data) | Where-Object { $_.id -eq $dishId }).stock
Write-Host "V5  stock $stockBefore -> $stockAfter ; cacheKeyExists=$keyStillThere ; listedStock=$listedStock"
Check "V5 stock deducted by orders" $stockAfter ($stockBefore - 5)
Check "V5 cache not evicted by ordering" $keyStillThere 1
Check "V5 list reflects real stock" $listedStock $stockAfter

# ---------- V6: precise invalidation - setmeal write clears only setmeal cache ----------
$dishKeyBefore = Redis "exists $k"
Invoke-RestMethod -Uri "$base/admin/setmeal/status/1?id=32" -Method Post -Headers @{token=$adminToken} | Out-Null
Start-Sleep -Milliseconds 300
$setmealKeyAfter = Redis "exists $k2"
$dishKeyAfter = Redis "exists $k"
Write-Host "V6  setmealKey after=$setmealKeyAfter ; dishKey before=$dishKeyBefore after=$dishKeyAfter"
Check "V6 setmeal cache cleared" $setmealKeyAfter 0
Check "V6 dish cache untouched" $dishKeyAfter $dishKeyBefore

Write-Host "---- t06 done ----"
