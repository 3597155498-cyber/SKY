# T07 verification: report aggregation query count + export
$ErrorActionPreference = 'Stop'
$mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$env:MYSQL_PWD = "123456"
$base = "http://127.0.0.1:8080"

function Sql([string]$q) {
  $out = & $mysql -uroot -D sky_take_out -N -B -e $q 2>$null
  return ($out | Where-Object { $_ -ne $null })
}
function Check([string]$name,$actual,$expected) {
  if ("$actual" -eq "$expected") { Write-Host ("PASS  {0}: {1}" -f $name, $actual) }
  else { Write-Host ("FAIL  {0}: actual={1} expected={2}" -f $name, $actual, $expected) }
}
function Questions {
  $line = Sql "SHOW GLOBAL STATUS LIKE 'Questions'"
  return [int](($line -split "`t")[1])
}

$admin = Invoke-RestMethod -Uri "$base/admin/employee/login" -Method Post -ContentType "application/json" -Body '{"username":"admin","password":"123456"}'
$h = @{ token = $admin.data.token }
$end = (Get-Date).ToString("yyyy-MM-dd")
$begin = (Get-Date).AddDays(-29).ToString("yyyy-MM-dd")

# ---------- V1: 30-day statistics must not fire per-day queries ----------
$q0 = Questions
$turnover = Invoke-RestMethod -Uri "$base/admin/report/turnoverStatistics?begin=$begin&end=$end" -Headers $h
$orders = Invoke-RestMethod -Uri "$base/admin/report/ordersStatistics?begin=$begin&end=$end" -Headers $h
$users = Invoke-RestMethod -Uri "$base/admin/report/userStatistics?begin=$begin&end=$end" -Headers $h
$q1 = Questions
$queries = $q1 - $q0
Write-Host "V1  3 report APIs (30 days) -> DB queries=$queries (old implementation ~90)"
Check "V1 all three APIs ok" (($turnover.code + $orders.code + $users.code)) 3
Check "V1 30 day series length" (($turnover.data.dateList -split ',').Count) 30
Check "V1 no per-day query (<= 15)" ($queries -le 15) $true

# ---------- V2: export endpoint returns a real xlsx ----------
$tmp = Join-Path $env:TEMP ("report-" + (Get-Date -Format "HHmmss") + ".xlsx")
$q0 = Questions
Invoke-WebRequest -Uri "$base/admin/report/export" -Headers $h -OutFile $tmp
$q1 = Questions
$size = (Get-Item $tmp).Length
$head = [System.IO.File]::ReadAllBytes($tmp)[0..1]
Write-Host "V2  export size=$size bytes queries=$($q1-$q0)"
Check "V2 export not empty" ($size -gt 10000) $true
Check "V2 xlsx magic bytes (PK)" (($head[0] -eq 0x50) -and ($head[1] -eq 0x4B)) $true
Remove-Item $tmp -Force

# ---------- V3: indexes used by aggregation ----------
$idx1 = Sql "SHOW INDEX FROM order_detail WHERE Key_name='idx_order_id'"
$idx2 = Sql "SHOW INDEX FROM user WHERE Key_name='idx_create_time'"
$idx3 = Sql "SHOW INDEX FROM orders WHERE Key_name='idx_status_order_time'"
Check "V3 order_detail idx exists" ([bool]$idx1) $true
Check "V3 user idx exists" ([bool]$idx2) $true
Check "V3 orders status+time idx exists" ([bool]$idx3) $true

Write-Host "---- t07 done ----"
