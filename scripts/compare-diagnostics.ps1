param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$First,

    [Parameter(Mandatory = $true, Position = 1)]
    [string]$Second,

    [string]$FirstLabel = "",

    [string]$SecondLabel = "",

    [ValidateRange(1, 50)]
    [int]$RecentEventLimit = 12,

    [string]$OutFile = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Read-DiagnosticsReport {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        Write-Error "Diagnostics file not found: $Path"
    }

    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    $lines = Get-Content -LiteralPath $resolvedPath
    $fields = [ordered]@{}
    $transports = [ordered]@{}
    $events = New-Object System.Collections.Generic.List[string]
    $section = "fields"

    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if (-not $trimmed) {
            continue
        }

        if ($trimmed -eq "Transports:") {
            $section = "transports"
            continue
        }

        if ($trimmed -eq "Recent events:") {
            $section = "events"
            continue
        }

        if ($trimmed.StartsWith("- ")) {
            $item = $trimmed.Substring(2).Trim()
            if ($item -eq "none") {
                continue
            }

            if ($section -eq "transports") {
                $separator = $item.IndexOf(":")
                if ($separator -gt 0) {
                    $name = $item.Substring(0, $separator).Trim()
                    $value = $item.Substring($separator + 1).Trim()
                    $transports[$name] = $value
                }
                continue
            }

            if ($section -eq "events") {
                [void]$events.Add($item)
                continue
            }
        }

        if ($section -eq "fields") {
            $separator = $trimmed.IndexOf(":")
            if ($separator -gt 0) {
                $key = $trimmed.Substring(0, $separator).Trim()
                $value = $trimmed.Substring($separator + 1).Trim()
                $fields[$key] = $value
            }
        }
    }

    [pscustomobject]@{
        Path = $resolvedPath
        Fields = $fields
        Transports = $transports
        Events = $events.ToArray()
    }
}

function Get-ReportLabel {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [string]$Label
    )

    if ($Label) {
        return $Label
    }
    return [System.IO.Path]::GetFileName($Path)
}

function Get-FieldValue {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Report,

        [Parameter(Mandatory = $true)]
        [string]$Key
    )

    if ($Report.Fields.Contains($Key)) {
        return [string]$Report.Fields[$Key]
    }
    return "(missing)"
}

function Get-TransportValue {
    param(
        [Parameter(Mandatory = $true)]
        [pscustomobject]$Report,

        [Parameter(Mandatory = $true)]
        [string]$Key
    )

    if ($Report.Transports.Contains($Key)) {
        return [string]$Report.Transports[$Key]
    }
    return "(missing)"
}

function Escape-MarkdownCell {
    param(
        [AllowNull()]
        [string]$Value
    )

    if ($null -eq $Value -or $Value -eq "") {
        return "(missing)"
    }

    return $Value.Replace("|", "\|").Replace("`r", " ").Replace("`n", " ")
}

function Get-EventCategories {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Events
    )

    $categories = New-Object System.Collections.Generic.List[string]
    foreach ($event in $Events) {
        if ($event -match '^\d{2}:\d{2}:\d{2}\s+([^:]+):') {
            [void]$categories.Add($Matches[1].Trim())
        }
    }

    return @($categories | Sort-Object -Unique)
}

function Compare-Field {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,

        [Parameter(Mandatory = $true)]
        [string]$Left,

        [Parameter(Mandatory = $true)]
        [string]$Right
    )

    $status = if ($Left -eq $Right) { "same" } else { "diff" }
    $safeLabel = Escape-MarkdownCell $Label
    $safeLeft = Escape-MarkdownCell $Left
    $safeRight = Escape-MarkdownCell $Right
    return "| $safeLabel | $safeLeft | $safeRight | $status |"
}

function New-RecentEventLines {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,

        [Parameter(Mandatory = $true)]
        [string[]]$Events,

        [Parameter(Mandatory = $true)]
        [int]$Limit
    )

    $lines = New-Object System.Collections.Generic.List[string]
    [void]$lines.Add("### $Label")
    [void]$lines.Add("")
    [void]$lines.Add('```text')
    if ($Events.Count -eq 0) {
        [void]$lines.Add("- none")
    } else {
        $start = [Math]::Max(0, $Events.Count - $Limit)
        for ($i = $start; $i -lt $Events.Count; $i++) {
            [void]$lines.Add("- $($Events[$i])")
        }
    }
    [void]$lines.Add('```')
    [void]$lines.Add("")

    return $lines.ToArray()
}

$a = Read-DiagnosticsReport $First
$b = Read-DiagnosticsReport $Second

$aName = Get-ReportLabel $a.Path $FirstLabel
$bName = Get-ReportLabel $b.Path $SecondLabel
$transportNames = @()
$transportNames += @($a.Transports.Keys)
$transportNames += @($b.Transports.Keys)
$allTransportNames = @($transportNames | Where-Object { $_ } | Sort-Object -Unique)
$aCategories = @(Get-EventCategories $a.Events)
$bCategories = @(Get-EventCategories $b.Events)
$sharedEventCategories = @($aCategories | Where-Object { $_ -in $bCategories })
$summaryFields = @(
    "App"
    "Protocol"
    "Device"
    "Android"
    "Peer"
    "Identity key"
    "Conversation"
    "Private room"
    "Background mesh"
    "Power mode"
    "Battery"
    "Peers visible"
    "Rooms visible"
    "Rooms unread"
    "Rooms pinned"
    "Peers blocked"
    "Visible messages"
    "Visible files"
    "Courier queue"
    "Courier relay"
    "Courier retention"
    "Courier quota"
)

$out = New-Object System.Collections.Generic.List[string]
$safeAName = Escape-MarkdownCell $aName
$safeBName = Escape-MarkdownCell $bName
[void]$out.Add("# AirChat Diagnostics Compare")
[void]$out.Add("")
$firstInput = $a.Path
$secondInput = $b.Path
[void]$out.Add("Inputs: ``$firstInput`` and ``$secondInput``")
[void]$out.Add("")
[void]$out.Add("## Summary")
[void]$out.Add("")
[void]$out.Add("| Field | $safeAName | $safeBName | Status |")
[void]$out.Add("| --- | --- | --- | --- |")
foreach ($field in $summaryFields) {
    [void]$out.Add((Compare-Field $field (Get-FieldValue $a $field) (Get-FieldValue $b $field)))
}

[void]$out.Add("")
[void]$out.Add("## Transports")
[void]$out.Add("")
[void]$out.Add("| Transport | $safeAName | $safeBName | Status |")
[void]$out.Add("| --- | --- | --- | --- |")
if ($allTransportNames.Count -eq 0) {
    [void]$out.Add("| none | (missing) | (missing) | same |")
} else {
    foreach ($name in $allTransportNames) {
        $left = Get-TransportValue $a $name
        $right = Get-TransportValue $b $name
        $status = if ($left -eq $right) { "same" } else { "diff" }
        $safeName = Escape-MarkdownCell $name
        $safeLeft = Escape-MarkdownCell $left
        $safeRight = Escape-MarkdownCell $right
        [void]$out.Add("| $safeName | $safeLeft | $safeRight | $status |")
    }
}

[void]$out.Add("")
[void]$out.Add("## Recent Event Categories")
[void]$out.Add("")
$aCategoryText = if ($aCategories.Count) { $aCategories -join ", " } else { "none" }
$bCategoryText = if ($bCategories.Count) { $bCategories -join ", " } else { "none" }
$sharedCategoryText = if ($sharedEventCategories.Count) { $sharedEventCategories -join ", " } else { "none" }
[void]$out.Add("- ${safeAName}: $aCategoryText")
[void]$out.Add("- ${safeBName}: $bCategoryText")
[void]$out.Add("- Shared: $sharedCategoryText")
[void]$out.Add("")
[void]$out.Add("## Recent Events")
[void]$out.Add("")
foreach ($line in @(New-RecentEventLines -Label $aName -Events $a.Events -Limit $RecentEventLimit)) {
    [void]$out.Add($line)
}
foreach ($line in @(New-RecentEventLines -Label $bName -Events $b.Events -Limit $RecentEventLimit)) {
    [void]$out.Add($line)
}

$suggestions = New-Object System.Collections.Generic.List[string]
if ((Get-FieldValue $a "App") -ne (Get-FieldValue $b "App")) {
    [void]$suggestions.Add("App versions differ. Install the same APK on both devices before comparing radio behavior.")
}
if ((Get-FieldValue $a "Protocol") -ne (Get-FieldValue $b "Protocol")) {
    [void]$suggestions.Add("Protocol versions differ. Install the same APK on both devices.")
}
if ((Get-FieldValue $a "Peer") -eq (Get-FieldValue $b "Peer") -and (Get-FieldValue $a "Peer") -ne "(missing)") {
    [void]$suggestions.Add("Both reports advertise the same peer identity. Check whether app data was cloned between devices.")
}
if ((Get-FieldValue $a "Conversation") -ne (Get-FieldValue $b "Conversation")) {
    [void]$suggestions.Add("Active conversations differ. Switch both devices to the same room when debugging room delivery.")
}
if ((Get-FieldValue $a "Private room") -ne (Get-FieldValue $b "Private room")) {
    [void]$suggestions.Add('Private-room state differs. Compare `/code` output and re-enter the passphrase on both devices.')
}
if ((Get-FieldValue $a "Background mesh") -ne (Get-FieldValue $b "Background mesh")) {
    [void]$suggestions.Add("Background mesh differs. Align the setting before testing background delivery.")
}
if ((Get-FieldValue $a "Power mode") -ne (Get-FieldValue $b "Power mode")) {
    [void]$suggestions.Add("Power modes differ. Check battery saver, charging state, and battery level before comparing relay behavior.")
}
foreach ($name in $allTransportNames) {
    $left = Get-TransportValue $a $name
    $right = Get-TransportValue $b $name
    if ($left -ne $right) {
        [void]$suggestions.Add(('Transport `{0}` differs. Check Wi-Fi, permissions, hotspot/router isolation, and battery saver on both devices.' -f $name))
    }
}
if ((Get-FieldValue $a "Courier queue") -ne "0" -or (Get-FieldValue $b "Courier queue") -ne "0") {
    [void]$suggestions.Add("Courier queue is non-zero on at least one device. Bring peers back onto the same local mesh within the courier window.")
}
if ($a.Events.Count -eq 0 -or $b.Events.Count -eq 0) {
    [void]$suggestions.Add("One report has no recent events. Reproduce the issue, reopen diagnostics, and share a fresh report.")
}

[void]$out.Add("## Suggested Checks")
[void]$out.Add("")
if ($suggestions.Count -eq 0) {
    [void]$out.Add("- No obvious diagnostics mismatches found. Continue with logcat or packet-level reproduction notes.")
} else {
    foreach ($suggestion in $suggestions) {
        [void]$out.Add("- $suggestion")
    }
}

if ($OutFile) {
    $target = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutFile)
    Set-Content -LiteralPath $target -Value $out -Encoding UTF8
}

$out
