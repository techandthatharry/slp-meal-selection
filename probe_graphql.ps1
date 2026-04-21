$u = 'deven@techandthat.com'
$p = 'V3DbrM!m3swruY!'
$b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${u}:${p}"))
$headers = @{ Authorization = "Basic $b64" }
$endpoint = "https://api-sandbox2.uk.arbor.sc/graphql/query"

function GQL($query) {
    $body = [System.Text.Encoding]::UTF8.GetBytes(
        (ConvertTo-Json @{ query = $query } -Compress)
    )
    return Invoke-RestMethod -Uri $endpoint -Headers $headers -Method Post -Body $body -ContentType "application/json"
}

function IntrospectType($typeName) {
    $q = "{ __type(name: `"$typeName`") { fields { name type { name kind ofType { name kind } } } } }"
    $r = GQL $q
    $r.data.__type.fields | ForEach-Object {
        $t = if ($_.type.name) { $_.type.name } else { "$($_.type.kind)($($_.type.ofType.name))" }
        Write-Output "  $($_.name): $t"
    }
}

Write-Output "=== MealSessionRegisterRecord ==="
IntrospectType "MealSessionRegisterRecord"

Start-Sleep -Seconds 1

Write-Output "`n=== MealRotationMenuChoice ==="
IntrospectType "MealRotationMenuChoice"

Start-Sleep -Seconds 1

Write-Output "`n=== MealChoice ==="
IntrospectType "MealChoice"

Start-Sleep -Seconds 1

Write-Output "`n=== MealScheduleItem ==="
IntrospectType "MealScheduleItem"
