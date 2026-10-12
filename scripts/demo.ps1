$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$reviewer = 'demo-reviewer'

Write-Host 'Checking application health...'
Invoke-RestMethod -Method Get -Uri "$base/api/health" | ConvertTo-Json -Depth 5

$requirement = 'Build a URL shortener API that creates short links, redirects users, and tracks click analytics.'
$created = Invoke-RestMethod -Method Post -Uri "$base/api/runs" -ContentType 'application/json' -Body (@{ requirement = $requirement } | ConvertTo-Json)
$runId = $created.runId
Write-Host "Created workflow: $runId"

Write-Host 'Running low-risk planning (parallel design + acceptance planning)...'
$planned = Invoke-RestMethod -Method Post -Uri "$base/api/runs/$runId/execute"
if ($planned.status -ne 'PENDING_APPROVAL') { throw "Expected PENDING_APPROVAL after planning, got $($planned.status)" }

Write-Host 'Approving the explicit, bounded execution scope...'
$approval = @{ decision = 'approve'; reviewerId = $reviewer; reviewerNote = 'Approved allowlisted local URL-shortener checks only' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/api/runs/$runId/approval" -ContentType 'application/json' -Body $approval | Out-Null

Write-Host 'Executing approved requirement-linked checks...'
Invoke-RestMethod -Method Post -Uri "$base/api/runs/$runId/execute" | Out-Null

$finalRun = Invoke-RestMethod -Method Get -Uri "$base/api/runs/$runId"
$artifacts = Invoke-RestMethod -Method Get -Uri "$base/api/runs/$runId/artifacts"
$events = Invoke-RestMethod -Method Get -Uri "$base/api/runs/$runId/events"

Write-Host "Final status: $($finalRun.status)"
Write-Host "Validation: $($finalRun.validation.status)"
Write-Host "Artifacts: $($artifacts.Count)"
Write-Host "Events: $($events.Count)"
$finalRun | ConvertTo-Json -Depth 10
$artifacts | ConvertTo-Json -Depth 10

if ($finalRun.status -ne 'COMPLETED' -or $finalRun.validation.status -ne 'PASSED' -or $artifacts.Count -ne 5) {
    throw 'Workflow acceptance checks failed. Inspect the run events and application logs.'
}
Write-Host 'PASS: planning joined, reviewer approval was recorded, checks ran, and evidence artifacts were produced.' -ForegroundColor Green
