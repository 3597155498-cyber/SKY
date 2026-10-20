# T12 种子数据脚本：可复现的订单 + 评价数据集
# 用法：
#   powershell -File seed-t12.ps1                     # 默认 600 单 / 30 天
#   powershell -File seed-t12.ps1 -Days 7             # 7 天版本（趋势点少，适合快速演示）
#   powershell -File seed-t12.ps1 -Orders 5000 -Days 30   # 压测数据量
#   powershell -File seed-t12.ps1 -Clean              # 只清理 T12 前缀的测试数据
#   powershell -File seed-t12.ps1 -NoAnalyze          # 只造数，不触发离线分析
param(
    [int]$Orders = 600,
    [int]$Days = 30,
    [switch]$Clean,
    [switch]$NoAnalyze
)
$ErrorActionPreference = 'Stop'
$mysql = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
$redisCli = "C:\Program Files\Redis\redis-cli.exe"
$env:MYSQL_PWD = "123456"
$base = "http://127.0.0.1:8080"
$tmp = Join-Path $env:TEMP "sky-seed"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Sql([string]$q) {
    $out = & $mysql -uroot -D sky_take_out --default-character-set=utf8mb4 -N -B -e $q 2>$null
    return ($out | Where-Object { $_ -ne $null })
}
function SqlFile([string]$sql, [string]$name) {
    $f = Join-Path $tmp $name
    [System.IO.File]::WriteAllText($f, $sql, (New-Object System.Text.UTF8Encoding($false)))
    cmd /c "`"$mysql`" -uroot --default-character-set=utf8mb4 sky_take_out < `"$f`"" 2>&1 |
        Where-Object { $_ -notmatch 'insecure' } | Out-Null
}

# ---------------- 清理 ----------------
function Clear-Data {
    SqlFile @'
DELETE a FROM comment_analysis a
  JOIN comment c ON c.id = a.comment_id
  JOIN orders o ON o.id = c.order_id
 WHERE o.number LIKE 'T12%';
DELETE c FROM comment c JOIN orders o ON o.id = c.order_id WHERE o.number LIKE 'T12%';
DELETE FROM orders WHERE number LIKE 'T12%';
'@ "clean.sql"
    Write-Host "已清理 T12 前缀测试数据"
}

if ($Clean) {
    Clear-Data
    Write-Host ("剩余 comment=" + (Sql "select count(*) from comment") + " orders=" + (Sql "select count(*) from orders"))
    exit 0
}

Write-Host ("开始造数: Orders={0}, Days={1}" -f $Orders, $Days)
Clear-Data

# ---------------- 订单 ----------------
$orderSql = @"
INSERT INTO orders (number,status,user_id,address_book_id,order_time,checkout_time,pay_method,pay_status,amount,phone,address,consignee,delivery_status,pack_amount,tableware_number,tableware_status)
WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n < $Orders)
SELECT CONCAT('T12',LPAD(n,6,'0')),5,11,10,
       NOW() - INTERVAL (n % $Days) DAY - INTERVAL ((n*7) % 12) HOUR,
       NOW() - INTERVAL (n % $Days) DAY,
       1,1,38.00,'13900000000','T12Addr','T12',1,1,1,1
  FROM seq;
"@
SqlFile $orderSql "orders.sql"
$orderCount = [int](Sql "select count(*) from orders where number like 'T12%'")
Write-Host ("订单写入: {0}" -f $orderCount)
if ($orderCount -eq 0) { throw "订单写入失败" }

$ids = @(Sql "select id from orders where number like 'T12%' order by id")
$ids = $ids[0..([Math]::Min($ids.Count - 1, $Orders - 1))]

# ---------------- 评价文本池 ----------------
$themeTexts = @{
    '配送' = @('送餐太慢了，等了快一个小时','骑手迟到四十分钟，菜都凉了','配送太慢，高峰期等了好久','送餐速度太慢，影响体验','骑手找不到地址，耽误很久','等了好久才送到，饭都凉了','配送严重超时，下次不敢点了','送餐太慢了，午休都结束了')
    '分量' = @('分量太少了，根本不够吃','菜量太少，性价比不高','分量比以前小了很多','一份菜只有几筷子，太少了','分量不足，两个人吃不够','量太少了，明显缩水')
    '口味' = @('菜太咸了，口味偏重','味道一般，没有想象中好吃','太淡了，没什么味道','口味不稳定，这次很难吃','有点腥，吃着不太习惯','太油腻了，吃两口就腻')
    '包装' = @('包装漏了，汤洒了一地','打包太随意，盒子都变形了','汤汁洒出来了，弄脏了袋子','包装不严实，菜都混在一起了','餐具没给，包装也很简陋')
    '服务' = @('客服态度不好，问问题半天不回','老板态度不好，说话很冲','服务一般，有问题没人处理','售后没人管，体验很差')
    '价格' = @('太贵了，性价比很低','价格涨了但分量没涨','这个价格不值，别家便宜一半','加价太多，不划算')
}
$posTexts = @('好吃，分量足，下次还来','配送很快，包装也好','味道不错，性价比高','老板很热情，推荐','很入味，满意','菜很新鲜，赞','一如既往地好吃','出餐快，服务好','味道正，回购了','包装干净，送得也快')
$neuTexts = @('还可以吧，正常水平','没有特别感觉，一般','说不上好也说不上差','中规中矩，凑合吃')

# 分布：负向 45%（配送占大头）、中性 5%、正向 50%
$negTotal = [int]($Orders * 0.45)
$neuTotal = [int]($Orders * 0.05)
$posTotal = $Orders - $negTotal - $neuTotal
$weights = @(@('配送', 0.41), @('分量', 0.24), @('包装', 0.17), @('口味', 0.12), @('价格', 0.04), @('服务', 0.02))
$plan = @()
$used = 0
for ($w = 0; $w -lt $weights.Count; $w++) {
    $cnt = if ($w -eq $weights.Count - 1) { $negTotal - $used } else { [int][Math]::Round($negTotal * $weights[$w][1]) }
    if ($cnt -lt 0) { $cnt = 0 }
    $used += $cnt
    $plan += , @($weights[$w][0], $cnt)
}

$rows = New-Object System.Collections.ArrayList
$idx = 0
foreach ($p in $plan) {
    $theme = $p[0]; $cnt = [int]$p[1]
    $texts = $themeTexts[$theme]
    for ($k = 0; $k -lt $cnt -and $idx -lt $ids.Count; $k++) {
        $oid = $ids[$idx]; $idx++
        $txt = $texts[$k % $texts.Count]
        $score = if ($k % 3 -eq 0) { 1 } else { 2 }
        $day = $k % $Days
        [void]$rows.Add("($oid,11,$score,'$txt',NOW() - INTERVAL $day DAY - INTERVAL (($k*5)%10) HOUR)")
    }
}
for ($k = 0; $k -lt $neuTotal -and $idx -lt $ids.Count; $k++) {
    $oid = $ids[$idx]; $idx++
    $txt = $neuTexts[$k % $neuTexts.Count]
    [void]$rows.Add("($oid,11,3,'$txt',NOW() - INTERVAL " + ($k % $Days) + " DAY)")
}
for ($k = 0; $k -lt $posTotal -and $idx -lt $ids.Count; $k++) {
    $oid = $ids[$idx]; $idx++
    $txt = $posTexts[$k % $posTexts.Count]
    $score = if ($k % 2 -eq 0) { 5 } else { 4 }
    [void]$rows.Add("($oid,11,$score,'$txt',NOW() - INTERVAL " + ($k % $Days) + " DAY)")
}

for ($i = 0; $i -lt $rows.Count; $i += 200) {
    $batch = $rows[$i..([Math]::Min($i + 199, $rows.Count - 1))]
    $sql = "insert ignore into comment (order_id,user_id,score,content,create_time) values " + ($batch -join ',') + ";"
    SqlFile $sql "comments-$i.sql"
}
$commentCount = [int](Sql "select count(*) from comment")
Write-Host ("评价写入: {0}（目标 {1}）" -f $commentCount, $Orders)

# ---------------- 离线分析 ----------------
if (-not $NoAnalyze) {
    try {
        $login = Invoke-RestMethod -Uri "$base/admin/employee/login" -Method Post -ContentType "application/json" `
            -Body '{"username":"admin","password":"123456"}' -TimeoutSec 10
        $token = $login.data.token
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $r = Invoke-RestMethod -Uri ("$base/admin/comment/analyze?limit=" + ($Orders + 10)) -Method Post `
            -Headers @{ token = $token } -TimeoutSec 300
        $sw.Stop()
        Write-Host ("规则分析完成: {0} 条, 耗时 {1}ms, 0 token" -f $r.data, [int]$sw.ElapsedMilliseconds)
        # 数据变了，缓存必须失效
        Invoke-RestMethod -Uri "$base/admin/comment/insight/refresh" -Method Post -Headers @{ token = $token } | Out-Null
        Write-Host "已主动失效洞察缓存"
    } catch {
        Write-Host ("跳过在线分析（服务未启动？）: " + $_.Exception.Message)
        Write-Host "可稍后调用 POST /admin/comment/analyze 手动分析"
    }
}

# ---------------- 自检 ----------------
$stat = Sql "select concat('total=',count(*),' neg=',ifnull(sum(case when a.sentiment='NEGATIVE' then 1 else 0 end),0)) from comment c left join comment_analysis a on a.comment_id=c.id"
$days = Sql "select count(distinct date_format(create_time,'%Y-%m-%d')) from comment"
Write-Host ("自检: {0}, 覆盖天数={1}（期望 {2}）" -f $stat, $days, $Days)
if ([int]$days -lt [Math]::Min($Days, 5)) { Write-Host "⚠ 覆盖天数偏少，检查 -Days 参数" }
Write-Host "完成。看板: http://127.0.0.1:8080/ai/merchant.html"
