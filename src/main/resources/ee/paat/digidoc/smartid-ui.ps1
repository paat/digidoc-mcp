# Drives DigiDoc4's signing view with UI Automation. No personal code is read or printed.
# The only process id is the launched qdigidoc4, passed as the first argument.
# Unverified until the Windows spike:
# - Qt 6 exposes widget objectName as AutomationId (tabs, sign).
# - The container page's add-signature control is MainAction, which has no objectName
#   in DigiDoc4-Client de77bcf. The helper tries AutomationId "MainAction", then ClassName.
# - Smart-ID is tab index 2 (ID-card, Mobile-ID, Smart-ID) via SelectionItemPattern.
param(
    [Parameter(Mandatory = $true)]
    [int]$ProcessId
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Find-First($parent, $scope, $property, $value) {
    if ($null -eq $parent) { return $null }
    $condition = New-Object System.Windows.Automation.PropertyCondition($property, $value)
    return $parent.FindFirst($scope, $condition)
}

function Find-TopLevel($root, $processId) {
    return Find-First $root ([System.Windows.Automation.TreeScope]::Children) `
        ([System.Windows.Automation.AutomationElement]::ProcessIdProperty) $processId
}

function Find-InProcess($root, $processId, $property, $value) {
    $name = [System.Windows.Automation.AutomationElement]::ProcessIdProperty
    $byProcess = New-Object System.Windows.Automation.PropertyCondition($name, $processId)
    $windows = $root.FindAll([System.Windows.Automation.TreeScope]::Children, $byProcess)
    for ($i = 0; $i -lt $windows.Count; $i++) {
        $found = Find-First $windows.Item($i) ([System.Windows.Automation.TreeScope]::Descendants) $property $value
        if ($null -ne $found) { return $found }
    }
    return $null
}

function Find-AddSignature($root, $processId) {
    $id = [System.Windows.Automation.AutomationElement]::AutomationIdProperty
    $className = [System.Windows.Automation.AutomationElement]::ClassNameProperty
    $byId = Find-InProcess $root $processId $id 'MainAction'
    if ($null -ne $byId) { return $byId }
    return Find-InProcess $root $processId $className 'MainAction'
}

try {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    if ($ProcessId -le 0) { exit 1 }
    $deadline = (Get-Date).AddSeconds(30)
    $root = [System.Windows.Automation.AutomationElement]::RootElement
    $processId = $ProcessId
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        if ($null -eq (Get-Process -Id $processId -ErrorAction SilentlyContinue)) { exit 1 }
        if ($null -ne (Find-TopLevel $root $processId)) { $ready = $true; break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) { exit 1 }

    $add = $null
    while ((Get-Date) -lt $deadline -and $null -eq $add) {
        $add = Find-AddSignature $root $processId
        if ($null -eq $add) { Start-Sleep -Milliseconds 200 }
    }
    if ($null -eq $add) { exit 2 }
    $add.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()

    $idProperty = [System.Windows.Automation.AutomationElement]::AutomationIdProperty
    $tabs = $null
    while ((Get-Date) -lt $deadline -and $null -eq $tabs) {
        $tabs = Find-InProcess $root $processId $idProperty 'tabs'
        if ($null -eq $tabs) { Start-Sleep -Milliseconds 200 }
    }
    if ($null -eq $tabs) { exit 3 }
    $children = $tabs.FindAll([System.Windows.Automation.TreeScope]::Children,
        [System.Windows.Automation.Condition]::TrueCondition)
    if ($children.Count -lt 3) { exit 4 }
    $children.Item(2).GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()

    $sign = $null
    while ((Get-Date) -lt $deadline -and $null -eq $sign) {
        $sign = Find-InProcess $root $processId $idProperty 'sign'
        if ($null -eq $sign) { Start-Sleep -Milliseconds 200 }
    }
    if ($null -eq $sign) { exit 5 }
    $sign.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
    exit 0
} catch {
    exit 1
}
