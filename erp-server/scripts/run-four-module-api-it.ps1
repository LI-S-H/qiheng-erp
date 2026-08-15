param(
    [string]$ApiBase = 'http://127.0.0.1:8081',
    [string]$Prefix,
    [string]$Username = $env:ERP_IT_USERNAME,
    [string]$Password = $env:ERP_IT_PASSWORD,
    [switch]$AllowNonLoopback
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:history = [System.Collections.Generic.List[object]]::new()

function Assert-Equal {
    param(
        [Parameter(Mandatory)] $Actual,
        [Parameter(Mandatory)] $Expected,
        [Parameter(Mandatory)] [string]$Message
    )
    if ([decimal]$Actual -ne [decimal]$Expected) {
        throw "$Message：实际值=$Actual，期望值=$Expected"
    }
}

function Assert-True {
    param([Parameter(Mandatory)] [bool]$Condition, [Parameter(Mandatory)] [string]$Message)
    if (-not $Condition) { throw $Message }
}

if ([string]::IsNullOrWhiteSpace($Prefix)) {
    $Prefix = 'IT{0}_{1}' -f (Get-Date).ToString('yyyyMMdd_HHmmss'), [guid]::NewGuid().ToString('N').Substring(0, 8)
}

try {
    $apiUri = [uri]$ApiBase
}
catch {
    throw "ApiBase 不是合法 URL：$ApiBase"
}
Assert-True ($apiUri.Scheme -in @('http', 'https')) 'ApiBase 仅支持 HTTP 或 HTTPS'
if (-not $AllowNonLoopback) {
    Assert-True ($apiUri.Host -in @('127.0.0.1', 'localhost', '::1')) '默认仅允许环回地址；非本机隔离环境请显式传入 -AllowNonLoopback'
}
Assert-True (-not [string]::IsNullOrWhiteSpace($Username)) '请通过 ERP_IT_USERNAME 提供测试账号'
Assert-True (-not [string]::IsNullOrWhiteSpace($Password)) '请通过 ERP_IT_PASSWORD 提供测试密码'

function Invoke-ApiRaw {
    param(
        [Parameter(Mandatory)] [string]$Method,
        [Parameter(Mandatory)] [string]$Path,
        $Body
    )
    $params = @{
        Method = $Method
        Uri = "$ApiBase$Path"
        Headers = $script:headers
        ContentType = 'application/json'
    }
    if ($null -ne $Body) {
        $params.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    $response = Invoke-RestMethod @params
    $script:history.Add([pscustomobject]@{ Method = $Method; Path = $Path; Code = $response.code; Message = $response.message })
    return $response
}

function Invoke-Api {
    param(
        [Parameter(Mandatory)] [string]$Method,
        [Parameter(Mandatory)] [string]$Path,
        $Body
    )
    $response = Invoke-ApiRaw -Method $Method -Path $Path -Body $Body
    if ($response.code -ne 0) {
        throw "$Method $Path 失败：code=$($response.code)，message=$($response.message)"
    }
    return $response.data
}

function Invoke-ConcurrentPost {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] $Body,
        [int]$Count = 2
    )
    Add-Type -AssemblyName System.Net.Http
    $client = [System.Net.Http.HttpClient]::new()
    try {
        foreach ($headerName in $script:headers.Keys) {
            $null = $client.DefaultRequestHeaders.TryAddWithoutValidation($headerName, [string]$script:headers[$headerName])
        }
        $json = $Body | ConvertTo-Json -Depth 20 -Compress
        $tasks = @(1..$Count | ForEach-Object {
            $content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')
            $client.PostAsync("$ApiBase$Path", $content)
        })
        [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks)
        return @($tasks | ForEach-Object {
            $_.Result.Content.ReadAsStringAsync().Result | ConvertFrom-Json
        })
    }
    finally {
        $client.Dispose()
    }
}

function Get-PurchaseDetail([string]$Id) { Invoke-Api GET "/purchase/orders/$Id" $null }
function Get-SalesDetail([string]$Id) { Invoke-Api GET "/sales/orders/$Id" $null }
function Get-ReturnDetail([string]$Id) { Invoke-Api GET "/returns/$Id" $null }

function Find-InboundBill([string]$SourceNo) {
    $encoded = [uri]::EscapeDataString($SourceNo)
    $page = Invoke-Api GET "/warehouse/inbound-bills?sourceNo=$encoded&pageNum=1&pageSize=20" $null
    $bill = @($page.records | Where-Object { $_.sourceNo -eq $SourceNo }) | Select-Object -First 1
    Assert-True ($null -ne $bill) "未找到来源单 $SourceNo 的入库单"
    return Invoke-Api GET "/warehouse/inbound-bills/$($bill.workBillId)" $null
}

function Find-OutboundBill([string]$SourceNo) {
    $encoded = [uri]::EscapeDataString($SourceNo)
    $page = Invoke-Api GET "/warehouse/outbound-bills?sourceNo=$encoded&pageNum=1&pageSize=20" $null
    $bill = @($page.records | Where-Object { $_.sourceNo -eq $SourceNo }) | Select-Object -First 1
    Assert-True ($null -ne $bill) "未找到来源单 $SourceNo 的出库单"
    return Invoke-Api GET "/warehouse/outbound-bills/$($bill.workBillId)" $null
}

function Confirm-Inbound([object]$Bill, [decimal]$Qty) {
    $item = @($Bill.items)[0]
    $update = Invoke-Api PUT "/warehouse/inbound-bills/$($Bill.workBillId)" @{
        version = $Bill.version
        items = @(@{
            sourceItemId = $item.sourceItemId
            productId = $item.productId
            planQty = $item.planQty
            currentQty = $Qty
            qualifiedQty = $Qty
            defectiveQty = 0
            remark = "$Prefix 入库确认"
        })
    }
    return Invoke-Api POST "/warehouse/inbound-bills/$($Bill.workBillId)/confirm" @{ version = $update.version }
}

function Confirm-Outbound([object]$Bill, [decimal]$Qty) {
    $item = @($Bill.items)[0]
    # 销售出库不质检；采购退货出库需要质检，合格数量与本次数量相等。
    $qualifiedQty = if ($Bill.billType -eq 'PURCHASE_RETURN') { $Qty } else { 0 }
    $update = Invoke-Api PUT "/warehouse/outbound-bills/$($Bill.workBillId)" @{
        version = $Bill.version
        items = @(@{
            sourceItemId = $item.sourceItemId
            productId = $item.productId
            planQty = $item.planQty
            currentQty = $Qty
            qualifiedQty = $qualifiedQty
            defectiveQty = 0
            remark = "$Prefix 出库确认"
        })
    }
    return Invoke-Api POST "/warehouse/outbound-bills/$($Bill.workBillId)/confirm" @{ version = $update.version }
}

function Find-Stock([string]$WarehouseId, [string]$ProductCode) {
    $encoded = [uri]::EscapeDataString($ProductCode)
    $page = Invoke-Api GET "/warehouse/stocks?warehouseId=$WarehouseId&productCode=$encoded&pageNum=1&pageSize=20" $null
    return @($page.records | Where-Object { $_.productCode -eq $ProductCode }) | Select-Object -First 1
}

function Get-Stock([string]$WarehouseId, [string]$ProductCode) {
    $stock = Find-Stock $WarehouseId $ProductCode
    Assert-True ($null -ne $stock) "未找到产品 $ProductCode 的库存记录"
    return $stock
}

function Create-PurchaseAndApprove([string]$SupplierId, [string]$SupplierProductId, [string]$ProductId, [int]$Qty) {
    $order = Invoke-Api POST '/purchase/orders' @{
        supplierId = $SupplierId
        warehouseId = $script:warehouseId
        expectedArrivalDate = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
        remark = "$Prefix 采购订单"
        items = @(@{
            supplierProductId = $SupplierProductId
            productId = $ProductId
            quantityPrecision = $script:productQuantityPrecision
            quantity = $Qty
            unitPrice = 12.34
            selectedSupplierScore = 95
            remark = "$Prefix 采购明细"
        })
    }
    Invoke-Api POST "/purchase/orders/$($order.purchaseOrderId)/submit" @{ version = $order.version } | Out-Null
    $submitted = Get-PurchaseDetail $order.purchaseOrderId
    Invoke-Api POST "/purchase/orders/$($order.purchaseOrderId)/approve" @{ version = $submitted.version } | Out-Null
    return Get-PurchaseDetail $order.purchaseOrderId
}

function Create-SalesAndApprove([string]$CustomerId, [string]$ProductId, [int]$Qty) {
    $order = Invoke-Api POST '/sales/orders' @{
        customerId = $CustomerId
        warehouseId = $script:warehouseId
        expectedDeliveryDate = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
        remark = "$Prefix 销售订单"
        items = @(@{
            productId = $ProductId
            quantity = $Qty
            unitPrice = 20.00
            remark = "$Prefix 销售明细"
        })
    }
    Invoke-Api POST "/sales/orders/$($order.salesOrderId)/submit" @{ version = $order.version } | Out-Null
    $submitted = Get-SalesDetail $order.salesOrderId
    Invoke-Api POST "/sales/orders/$($order.salesOrderId)/approve" @{ version = $submitted.version } | Out-Null
    return Get-SalesDetail $order.salesOrderId
}

function CreateReturnAndApprove([string]$ReturnType, [string]$SourceOrderId, [string]$SourceOrderItemId, [decimal]$Qty) {
    $order = Invoke-Api POST '/returns' @{
        returnType = $ReturnType
        sourceOrderId = $SourceOrderId
        warehouseId = $script:warehouseId
        expectedExecutionDate = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
        handlingType = 'REFUND'
        reasonCode = 'QUALITY_ISSUE'
        returnReason = "$Prefix 质量测试"
        remark = "$Prefix $ReturnType"
        items = @(@{
            sourceOrderItemId = $SourceOrderItemId
            requestedQty = $Qty
            remark = "$Prefix 退货明细"
        })
    }
    Invoke-Api POST "/returns/$($order.returnOrderId)/submit" @{ version = $order.version } | Out-Null
    $submitted = Get-ReturnDetail $order.returnOrderId
    $returnItem = @($submitted.items)[0]
    Invoke-Api POST "/returns/$($order.returnOrderId)/approve" @{
        version = $submitted.version
        items = @(@{ returnOrderItemId = $returnItem.returnOrderItemId; approvedQty = $Qty })
    } | Out-Null
    return Get-ReturnDetail $order.returnOrderId
}

try {
    $login = Invoke-RestMethod -Method Post -Uri "$ApiBase/auth/login" -ContentType 'application/json' -Body (@{ username = $Username; password = $Password } | ConvertTo-Json -Compress)
    Assert-Equal $login.code 0 '管理员登录'
    $script:headers = @{ $login.data.tokenName = $login.data.token }

    $category = Invoke-Api POST '/product/categories' @{
        parentId = 0
        categoryName = "$Prefix 集成测试分类"
        status = 1
    }
    $warehouse = Invoke-Api POST '/warehouse/warehouses' @{
        warehouseName = "$Prefix 集成测试仓库"
        contactName = '测试联系人'
        contactPhone = '13800000000'
        address = '集成测试地址'
        status = 1
        remark = "$Prefix 测试夹具"
    }

    $product = Invoke-Api POST '/products' @{
        productName = "$Prefix 集成测试产品"
        categoryId = $category.categoryId
        brandName = 'IT'
        unitName = '件'
        quantityPrecision = 0
        specification = 'IT-SPEC'
        barcode = "$Prefix-BARCODE"
        referencePurchasePrice = 12.34
        referenceSalePrice = 20.00
        safetyStockQty = 0
        status = 1
        remark = "$Prefix 测试夹具"
    }
    $script:productProvisioning = '通过产品新增接口创建'
    $script:warehouseId = "$($warehouse.warehouseId)"
    $script:productQuantityPrecision = $product.quantityPrecision
    $baselineStock = Find-Stock $script:warehouseId $product.productCode
    $script:baselineStockQty = if ($null -eq $baselineStock) { [decimal]0 } else { [decimal]$baselineStock.stockQty }
    $script:baselineLockedQty = if ($null -eq $baselineStock) { [decimal]0 } else { [decimal]$baselineStock.lockedQty }
    $supplier = Invoke-Api POST '/purchase/suppliers' @{
        supplierName = "$Prefix 供应商"
        contactName = '测试联系人'
        contactPhone = '13800000000'
        address = '集成测试地址'
        paymentTerms = '现结'
        overallScore = 95; deliveryScore = 95; qualityScore = 95; priceScore = 95; serviceScore = 95
        avgDeliveryDays = 1; onTimeRate = 95; qualifiedRate = 95
        status = 1
        remark = "$Prefix 测试夹具"
    }
    $supplierProduct = Invoke-Api POST '/purchase/supplier-products' @{
        supplierId = "$($supplier.supplierId)"
        productId = "$($product.productId)"
        supplierProductCode = "$Prefix-SUP"
        latestPurchasePrice = 12.34
        minOrderQty = 1
        leadTimeDays = 1
        deliveryScore = 95; qualityScore = 95; priceScore = 95; aiScore = 95
        status = 1
        remark = "$Prefix 测试夹具"
        version = 0
    }
    $script:supplierProvisioning = '通过供应商新增接口创建'
    $customer = Invoke-Api POST '/sales/customers' @{
        customerName = "$Prefix 客户"
        contactName = '测试联系人'
        contactPhone = '13900000000'
        address = '集成测试地址'
        creditLimit = 100000
        status = 1
        remark = "$Prefix 测试夹具"
    }
    $script:customerProvisioning = '通过客户新增接口创建'

    # 采购入库：20 件入库，库存从 0 增至 20。
    $purchase = Create-PurchaseAndApprove "$($supplier.supplierId)" "$($supplierProduct.supplierProductId)" "$($product.productId)" 20
    $purchaseInbound = Find-InboundBill $purchase.purchaseNo
    $purchaseInboundConfirmed = Confirm-Inbound $purchaseInbound 20
    $purchaseInboundReplay = Invoke-ApiRaw POST "/warehouse/inbound-bills/$($purchaseInbound.workBillId)/confirm" @{ version = $purchaseInboundConfirmed.version }
    Assert-True ($purchaseInboundReplay.code -ne 0) '已确认采购入库单不允许重复确认'
    $purchaseAfter = Get-PurchaseDetail $purchase.purchaseOrderId
    Assert-Equal @($purchaseAfter.items)[0].inboundQty 20 '采购入库累计数量'
    $stock = Get-Stock $script:warehouseId $product.productCode
    Assert-Equal $stock.stockQty ($script:baselineStockQty + 20) '采购入库后的库存'
    Assert-Equal $stock.lockedQty $script:baselineLockedQty '采购入库后的锁定库存'

    # 销售出库：8 件锁库、出库，库存 20→12，锁定量归零。
    $sales = Create-SalesAndApprove "$($customer.customerId)" "$($product.productId)" 8
    $stock = Get-Stock $script:warehouseId $product.productCode
    Assert-Equal $stock.lockedQty ($script:baselineLockedQty + 8) '销售审核后的锁定库存'
    $salesOutbound = Find-OutboundBill $sales.salesNo
    $salesOutboundConfirmed = Confirm-Outbound $salesOutbound 8
    $salesOutboundReplay = Invoke-ApiRaw POST "/warehouse/outbound-bills/$($salesOutbound.workBillId)/confirm" @{ version = $salesOutboundConfirmed.version }
    Assert-True ($salesOutboundReplay.code -ne 0) '已确认销售出库单不允许重复确认'
    $salesAfter = Get-SalesDetail $sales.salesOrderId
    Assert-Equal @($salesAfter.items)[0].outboundQty 8 '销售出库累计数量'
    $stock = Get-Stock $script:warehouseId $product.productCode
    Assert-Equal $stock.stockQty ($script:baselineStockQty + 12) '销售出库后的库存'
    Assert-Equal $stock.lockedQty $script:baselineLockedQty '销售出库后的锁定库存'

    # 采购退货：4 件出库，原采购累计已入库量必须保持 20。
    $purchaseSourceItem = @($purchaseAfter.items)[0]
    $purchaseReturn = CreateReturnAndApprove 'PURCHASE_RETURN' "$($purchaseAfter.purchaseOrderId)" "$($purchaseSourceItem.purchaseOrderItemId)" 4
    $purchaseReturnBill = Find-OutboundBill $purchaseReturn.returnNo
    Confirm-Outbound $purchaseReturnBill 4 | Out-Null
    $purchaseReturnAfter = Get-ReturnDetail $purchaseReturn.returnOrderId
    Assert-Equal @($purchaseReturnAfter.items)[0].processedQty 4 '采购退货处理数量'
    Assert-True ($purchaseReturnAfter.status -eq 'COMPLETED') '采购退货应完成'
    $purchaseAfterReturn = Get-PurchaseDetail $purchase.purchaseOrderId
    Assert-Equal @($purchaseAfterReturn.items)[0].inboundQty 20 '采购退货后原采购累计入库量'
    $stock = Get-Stock $script:warehouseId $product.productCode
    Assert-Equal $stock.stockQty ($script:baselineStockQty + 8) '采购退货后的库存'
    Assert-Equal $stock.lockedQty $script:baselineLockedQty '采购退货后的锁定库存'

    # 销售退货：3 件入库，stockAvailableQty=0 不影响可退资格；原销售累计已出库量保持 8。
    $salesSourceItems = Invoke-Api GET "/returns/source-orders/$($sales.salesOrderId)/items?returnType=SALES_RETURN" $null
    $salesSourceItem = @($salesSourceItems)[0]
    Assert-Equal $salesSourceItem.stockAvailableQty 0 '销售退货来源库存展示值'
    Assert-Equal $salesSourceItem.availableReturnQty 8 '销售退货来源可退数量'
    $salesSourceOrderItem = @($salesAfter.items)[0]
    $salesReturn = CreateReturnAndApprove 'SALES_RETURN' "$($salesAfter.salesOrderId)" "$($salesSourceOrderItem.salesOrderItemId)" 3
    $salesReturnBill = Find-InboundBill $salesReturn.returnNo
    Confirm-Inbound $salesReturnBill 3 | Out-Null
    $salesReturnAfter = Get-ReturnDetail $salesReturn.returnOrderId
    Assert-Equal @($salesReturnAfter.items)[0].processedQty 3 '销售退货处理数量'
    Assert-True ($salesReturnAfter.status -eq 'COMPLETED') '销售退货应完成'
    $salesAfterReturn = Get-SalesDetail $sales.salesOrderId
    Assert-Equal @($salesAfterReturn.items)[0].outboundQty 8 '销售退货后原销售累计出库量'
    $stock = Get-Stock $script:warehouseId $product.productCode
    Assert-Equal $stock.stockQty ($script:baselineStockQty + 11) '销售退货后的库存'
    Assert-Equal $stock.lockedQty $script:baselineLockedQty '销售退货后的锁定库存'
    $salesSourceItemsAfterReturn = Invoke-Api GET "/returns/source-orders/$($sales.salesOrderId)/items?returnType=SALES_RETURN" $null
    Assert-Equal @($salesSourceItemsAfterReturn)[0].availableReturnQty 5 '销售退货后的剩余可退数量'
    $overReturn = Invoke-ApiRaw POST '/returns' @{
        returnType = 'SALES_RETURN'; sourceOrderId = "$($sales.salesOrderId)"; warehouseId = $script:warehouseId
        expectedExecutionDate = (Get-Date).AddDays(1).ToString('yyyy-MM-dd'); handlingType = 'REFUND'; reasonCode = 'OTHER'
        returnReason = "$Prefix 超量退货测试"; items = @(@{ sourceOrderItemId = "$($salesSourceOrderItem.salesOrderItemId)"; requestedQty = 6 })
    }
    Assert-True ($overReturn.code -ne 0) '超过剩余可退数量的销售退货必须被拒绝'

    # 重复取消必须被拒绝且不改变已取消退货单状态。
    $cancelSourceItem = @($purchaseAfterReturn.items)[0]
    $cancelReturn = Invoke-Api POST '/returns' @{
        returnType = 'PURCHASE_RETURN'; sourceOrderId = "$($purchase.purchaseOrderId)"; warehouseId = $script:warehouseId
        expectedExecutionDate = (Get-Date).AddDays(1).ToString('yyyy-MM-dd'); handlingType = 'REFUND'; reasonCode = 'OTHER'
        returnReason = "$Prefix 重复取消测试"; items = @(@{ sourceOrderItemId = "$($cancelSourceItem.purchaseOrderItemId)"; requestedQty = 1 })
    }
    Invoke-Api POST "/returns/$($cancelReturn.returnOrderId)/submit" @{ version = $cancelReturn.version } | Out-Null
    $cancelSubmitted = Get-ReturnDetail $cancelReturn.returnOrderId
    Invoke-Api POST "/returns/$($cancelReturn.returnOrderId)/cancel" @{ version = $cancelSubmitted.version; reason = "$Prefix 取消" } | Out-Null
    $duplicateRejected = $false
    try {
        $duplicateResponse = Invoke-ApiRaw POST "/returns/$($cancelReturn.returnOrderId)/cancel" @{ version = ($cancelSubmitted.version + 1); reason = "$Prefix 重复取消" }
        $duplicateRejected = $duplicateResponse.code -ne 0
    } catch {
        # 网络或 HTTP 层异常同样代表重复请求未被成功执行。
        $duplicateRejected = $true
    }
    Assert-True $duplicateRejected '重复取消必须被后端拒绝'
    Assert-True ((Get-ReturnDetail $cancelReturn.returnOrderId).status -eq 'CANCELLED') '重复取消后状态必须保持已取消'

    # 部分确认：采购 7 件只入 3 件；销售 5 件只出 2 件，核验来源状态、累计量和余量锁库。
    $partialPurchase = Create-PurchaseAndApprove "$($supplier.supplierId)" "$($supplierProduct.supplierProductId)" "$($product.productId)" 7
    $partialInbound = Find-InboundBill $partialPurchase.purchaseNo
    Confirm-Inbound $partialInbound 3 | Out-Null
    $partialPurchaseAfter = Get-PurchaseDetail $partialPurchase.purchaseOrderId
    Assert-Equal @($partialPurchaseAfter.items)[0].inboundQty 3 '部分采购入库累计数量'
    Assert-True ($partialPurchaseAfter.status -eq 'PARTIAL_INBOUND') '部分采购入库后订单应为 PARTIAL_INBOUND'
    $stock = Get-Stock $script:warehouseId $product.productCode
    Assert-Equal $stock.stockQty ($script:baselineStockQty + 14) '部分采购入库后的库存'

    $partialSales = Create-SalesAndApprove "$($customer.customerId)" "$($product.productId)" 5
    $partialSalesOutbound = Find-OutboundBill $partialSales.salesNo
    Confirm-Outbound $partialSalesOutbound 2 | Out-Null
    $partialSalesAfter = Get-SalesDetail $partialSales.salesOrderId
    Assert-Equal @($partialSalesAfter.items)[0].outboundQty 2 '部分销售出库累计数量'
    Assert-True ($partialSalesAfter.status -eq 'PARTIAL_OUTBOUND') '部分销售出库后订单应为 PARTIAL_OUTBOUND'
    $stock = Get-Stock $script:warehouseId $product.productCode
    Assert-Equal $stock.stockQty ($script:baselineStockQty + 12) '部分销售出库后的库存'
    Assert-Equal $stock.lockedQty ($script:baselineLockedQty + 3) '部分销售出库后的剩余锁定库存'

    # 并发确认同一入库单：来源单锁与状态机应保证只有一个请求成功，且累计入库只能写回一次。
    $concurrentPurchase = Create-PurchaseAndApprove "$($supplier.supplierId)" "$($supplierProduct.supplierProductId)" "$($product.productId)" 2
    $concurrentInbound = Find-InboundBill $concurrentPurchase.purchaseNo
    $concurrentInboundItem = @($concurrentInbound.items)[0]
    $concurrentInboundUpdated = Invoke-Api PUT "/warehouse/inbound-bills/$($concurrentInbound.workBillId)" @{
        version = $concurrentInbound.version
        items = @(@{
            sourceItemId = $concurrentInboundItem.sourceItemId; productId = $concurrentInboundItem.productId
            planQty = $concurrentInboundItem.planQty; currentQty = 2; qualifiedQty = 2; defectiveQty = 0
            remark = "$Prefix 并发确认"
        })
    }
    $concurrentConfirmResults = @(Invoke-ConcurrentPost "/warehouse/inbound-bills/$($concurrentInbound.workBillId)/confirm" @{ version = $concurrentInboundUpdated.version })
    Assert-Equal @($concurrentConfirmResults | Where-Object { $_.code -eq 0 }).Count 1 '并发确认必须恰有一个请求成功'
    Assert-Equal @($concurrentConfirmResults | Where-Object { $_.code -ne 0 }).Count 1 '并发确认必须恰有一个请求被拒绝'
    $concurrentPurchaseAfter = Get-PurchaseDetail $concurrentPurchase.purchaseOrderId
    Assert-Equal @($concurrentPurchaseAfter.items)[0].inboundQty 2 '并发确认后的采购累计入库量只能写回一次'

    [pscustomobject]@{
        prefix = $Prefix
        productId = $product.productId
        purchaseOrderId = $purchase.purchaseOrderId
        salesOrderId = $sales.salesOrderId
        purchaseReturnOrderId = $purchaseReturn.returnOrderId
        salesReturnOrderId = $salesReturn.returnOrderId
        partialPurchaseOrderId = $partialPurchase.purchaseOrderId
        partialSalesOrderId = $partialSales.salesOrderId
        concurrentPurchaseOrderId = $concurrentPurchase.purchaseOrderId
        productProvisioning = $script:productProvisioning
        supplierProvisioning = $script:supplierProvisioning
        customerProvisioning = $script:customerProvisioning
        checks = @('采购入库', '销售出库', '采购退货', '销售退货', '原单累计履约量', '库存与锁定量', '重复确认', '超量销售退货拦截', '重复取消', '部分入库', '部分出库', '并发确认')
        requests = $script:history
    } | ConvertTo-Json -Depth 10
}
catch {
    [pscustomobject]@{
        prefix = $Prefix
        error = $_.Exception.Message
        requests = $script:history
    } | ConvertTo-Json -Depth 10
    exit 1
}
