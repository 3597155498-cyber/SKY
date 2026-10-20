# Phase 0 gate smoke: login -> browse address -> add cart -> submit order -> cancel -> stock restored
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

# 1. login
$login = Invoke-RestMethod -Uri "$base/user/user/login" -Method Post -ContentType "application/json" -Body '{"code":"phase0_user"}'
$token = $login.data.token
$uid = $login.data.id
Check "login ok" $login.code 1

# 2. address book
$addr = Invoke-RestMethod -Uri "$base/user/addressBook/list" -Headers @{authentication=$token}
Check "address book reachable" $addr.code 1
if (@($addr.data).Count -eq 0) {
  Sql "INSERT INTO address_book (user_id,consignee,sex,phone,province_code,province_name,city_code,city_name,district_code,district_name,detail,label,is_default) VALUES ($uid,'Phase0','1','13900000000','11','Beijing','1101','Beijing','110101','Dongcheng','Phase0Addr','home',1)" | Out-Null
  $addr = Invoke-RestMethod -Uri "$base/user/addressBook/list" -Headers @{authentication=$token}
}
$addrId = @($addr.data)[0].id
Check "address exists" (@($addr.data).Count -ge 1) $true

# 3. pick a sellable dish and add to cart
$dishId = Sql "SELECT id FROM dish WHERE status=1 AND stock>0 ORDER BY id LIMIT 1"
Sql "DELETE FROM shopping_cart WHERE user_id=$uid" | Out-Null
$stockBefore = [int](Sql "SELECT stock FROM dish WHERE id=$dishId")
$add = Invoke-RestMethod -Uri "$base/user/shoppingcart/add" -Method Post -ContentType "application/json" -Headers @{authentication=$token} -Body ('{"dishId":' + $dishId + ',"dishFlavor":"default"}')
Check "cart add" $add.code 1
$cart = Invoke-RestMethod -Uri "$base/user/shoppingcart/list" -Headers @{authentication=$token}
Check "cart list" (@($cart.data).Count) 1

# 4. submit order
$body = '{"addressBookId":' + $addrId + ',"payMethod":1,"remark":"phase0","deliveryStatus":1,"tablewareNumber":1,"tablewareStatus":1,"packAmount":1,"amount":10}'
$order = Invoke-RestMethod -Uri "$base/user/order/submit" -Method Post -ContentType "application/json" -Headers @{authentication=$token; "Idempotency-Key"="phase0-$run"} -Body $body
Check "submit ok" $order.code 1
$orderId = $order.data.id
$stockAfterSubmit = [int](Sql "SELECT stock FROM dish WHERE id=$dishId")
Check "stock -1 after submit" $stockAfterSubmit ($stockBefore - 1)

# 5. cancel order
$cancel = Invoke-RestMethod -Uri "$base/user/order/cancel/$orderId" -Method Put -Headers @{authentication=$token}
Check "cancel ok" $cancel.code 1
$stockAfterCancel = [int](Sql "SELECT stock FROM dish WHERE id=$dishId")
Check "stock restored after cancel" $stockAfterCancel $stockBefore
$status = Sql "SELECT status FROM orders WHERE id=$orderId"
Check "order status cancelled" $status 6
$cartCnt = Sql "SELECT COUNT(*) FROM shopping_cart WHERE user_id=$uid"
Check "cart cleared" $cartCnt 0

Write-Host "---- phase0 smoke done ----"
