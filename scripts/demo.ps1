# Runs every assessment scenario against a running app (mvn spring-boot:run) and saves evidence to docs/runs/.
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080/api/pipelines'
$out = Join-Path $PSScriptRoot '..\docs\runs'
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Post($path, $body) {
    $json = if ($body) { $body | ConvertTo-Json -Depth 10 } else { '{}' }
    Invoke-RestMethod -Method Post -Uri "$base$path" -ContentType 'application/json' -Body $json
}
function Approve($id, $stage, $who) { Post "/$id/stages/$stage/approval" @{ decision = 'approve'; reviewerId = $who; reviewerNote = "Reviewed $stage evidence" } }
function Expect($actual, $expected, $what) {
    if ($actual -ne $expected) { throw "FAIL [$what]: expected $expected, got $actual" }
    Write-Host "  ok  $what = $actual" -ForegroundColor Green
}
function Save($name, $id) {
    foreach ($part in @('', '/events', '/artifacts', '/metrics', '/graph')) {
        $file = if ($part) { "$name-$($part.Trim('/')).json" } else { "$name-pipeline.json" }
        Invoke-RestMethod -Uri "$base/$id$part" | ConvertTo-Json -Depth 20 | Set-Content -Encoding utf8 (Join-Path $out $file)
    }
}

Write-Host "`n1) GREENFIELD"
$p = Post '' @{ requirement = 'Build a URL shortener API that creates short links, redirects users, and tracks click analytics.' }
Expect $p.status 'AWAITING_APPROVAL' 'stops at implementation gate'
$p = Approve $p.pipelineId 'IMPLEMENTATION' 'tech-lead'
Expect $p.status 'AWAITING_APPROVAL' 'stops at release gate'
$p = Approve $p.pipelineId 'RELEASE_READINESS' 'release-manager'
Expect $p.status 'COMPLETED' 'greenfield'
Save 'greenfield' $p.pipelineId

Write-Host "`n2) BROWNFIELD"
$p = Post '' @{ requirement = 'Fix the existing URL shortener so unknown short codes return 404 and add regression tests.' }
$p = Approve $p.pipelineId 'IMPLEMENTATION' 'tech-lead'
$p = Approve $p.pipelineId 'RELEASE_READINESS' 'release-manager'
Expect $p.status 'COMPLETED' 'brownfield'
Save 'brownfield' $p.pipelineId

Write-Host "`n3) AMBIGUOUS -> clarification -> re-plan"
$p = Post '' @{ requirement = 'Make the URL shortener faster and more secure for users.' }
Expect $p.status 'NEEDS_CLARIFICATION' 'ambiguous requirement held'
$p = Post "/$($p.pipelineId)/clarification" @{ requesterId = 'product-owner'; clarification = 'Fix the existing URL shortener so unknown short codes return 404 and add regression checks; defer performance and security improvements.' }
Expect $p.status 'AWAITING_APPROVAL' 'graph re-planned after clarification'
$p = Approve $p.pipelineId 'IMPLEMENTATION' 'tech-lead'
$p = Approve $p.pipelineId 'RELEASE_READINESS' 'release-manager'
Expect $p.status 'COMPLETED' 'ambiguous (clarified)'
Save 'ambiguous' $p.pipelineId

Write-Host "`n4) UNMEASURABLE QUALITY GOAL -> release blocked, no false pass"
$p = Post '' @{ requirement = 'Build a secure URL shortener that creates short links and redirects users.' }
$p = Approve $p.pipelineId 'IMPLEMENTATION' 'tech-lead'
Expect $p.status 'BLOCKED' 'release blocked by CMP-001'
Save 'blocked-nfr' $p.pipelineId

Write-Host "`n5) FAILURE -> retry, rollback, safe stop, resume, MTTR"
$p = Post '' @{ requirement = 'Build a URL shortener API that creates short links, redirects users, and tracks click analytics.' }
$id = $p.pipelineId
Post "/$id/faults" @{ stageId = 'TESTING'; mode = 'TRANSIENT_ONCE' } | Out-Null
$p = Approve $id 'IMPLEMENTATION' 'tech-lead'
Post "/$id/faults" @{ stageId = 'RELEASE_READINESS'; mode = 'PERMANENT' } | Out-Null
$p = Approve $id 'RELEASE_READINESS' 'release-manager'
Expect $p.status 'FAILED' 'permanent fault halts pipeline'
$p = Post "/$id/resume" @{ operatorId = 'on-call-engineer'; reason = 'Fault cleared' }
Expect $p.status 'COMPLETED' 'recovered'
$m = Invoke-RestMethod -Uri "$base/$id/metrics"
Write-Host "  retries=$($m.retryCount) rollbacks=$($m.rollbackCount) mttrMs=$($m.mttrMs) successRate=$($m.stageSuccessRatePercent)%"
Save 'failure-recovery' $id

Write-Host "`nAll scenarios passed. Evidence written to docs/runs/" -ForegroundColor Green
