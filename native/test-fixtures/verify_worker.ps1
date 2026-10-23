$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$FixtureDir = Join-Path $PSScriptRoot "out"
$SharedDataDir = Join-Path $FixtureDir "worker-data"
$ProtoDir = Join-Path $RepoRoot "native\proto"
$GrpcPyOut = Join-Path $FixtureDir "grpc_stubs"
$ContainerName = "magnet-worker-smoke-verify"
$HostPort = 50052

Write-Host "==> [Step 1] Generating deterministic V1 fixture..."
if (Test-Path $FixtureDir) {
    Remove-Item -Recurse -Force $FixtureDir
}
python "$PSScriptRoot\create_v1_fixture.py" "$FixtureDir"
if (!(Test-Path "$FixtureDir\payload.txt") -or !(Test-Path "$FixtureDir\payload.torrent")) {
    throw "Fixture files were not generated"
}

Write-Host "==> [Step 2] Building magnet-libtorrent-worker:test image..."
docker build -t magnet-libtorrent-worker:test -t magnet-libtorrent-worker:latest "$RepoRoot\native"

Write-Host "==> [Step 3] Generating Python gRPC stubs..."
New-Item -ItemType Directory -Force -Path $GrpcPyOut | Out-Null
python -m grpc_tools.protoc `
    "--proto_path=$ProtoDir" `
    "--python_out=$GrpcPyOut" `
    "--grpc_python_out=$GrpcPyOut" `
    "$ProtoDir\download_engine.proto"

function Start-WorkerContainer {
    docker rm -f $ContainerName 2>$null | Out-Null
    New-Item -ItemType Directory -Force -Path $SharedDataDir | Out-Null
    docker run -d --name $ContainerName `
        -p "${HostPort}:50051" `
        -v "${SharedDataDir}:/data" `
        magnet-libtorrent-worker:test | Out-Null
    Start-Sleep -Seconds 2
}

try {
    Start-WorkerContainer

    Write-Host "==> [Step 4] Running RPC lifecycle & piece verification checks..."
    $VerifyScript = Join-Path $FixtureDir "run_smoke.py"
    @"
import hashlib
import os
import pathlib
import sys
import time

sys.path.insert(0, r'$GrpcPyOut')
import grpc
import download_engine_pb2 as pb
import download_engine_pb2_grpc as pb_grpc

fixture_dir = pathlib.Path(r'$FixtureDir')
shared_data = pathlib.Path(r'$SharedDataDir')
payload_bytes = (fixture_dir / 'payload.txt').read_bytes()
torrent_bytes = (fixture_dir / 'payload.torrent').read_bytes()

channel = grpc.insecure_channel('localhost:$HostPort')
stub = pb_grpc.DownloadEngineStub(channel)

def env(rid, cid='cmd-1'):
    return pb.CommandEnvelope(command_id=cid, resource_id=rid, owner_epoch=1)

def wait_for_check(rid, expected_verified, timeout=15.0):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        snap = stub.GetSnapshot(pb.GetSnapshotRequest(envelope=env(rid)))
        last = snap
        if snap.state in ('ACTIVE', 'IDLE', 'METADATA_READY') and list(snap.file_verified_bytes) == expected_verified:
            return snap
        time.sleep(0.3)
    raise AssertionError(f'Timeout waiting for {expected_verified}, last snapshot: state={last.state} verified={last.verified_bytes} files={list(last.file_verified_bytes)}')

# 1. Health
health = stub.Health(pb.HealthRequest())
assert health.ok and health.worker_id == 'local-worker-1' and health.libtorrent_version == '2.0.8', f'Unexpected health: {health}'
print(f'[PASS] Health: ok={health.ok} worker={health.worker_id} libtorrent={health.libtorrent_version}')

# 2. Path traversal rejection
bad_add = stub.AddTorrent(pb.AddTorrentRequest(
    envelope=env('bad-path-res'),
    torrent_file=torrent_bytes,
    storage_path='/etc/escape'
))
assert not bad_add.accepted, 'Worker must reject storage_path outside /data'
print(f'[PASS] Path boundary check: rejected /etc/escape ({bad_add.error})')

# 3. Single-file V1 AddTorrent + piece-granularity verification
rid = '11111111-2222-3333-4444-555555555555'
res_dir = shared_data / 'resources' / rid
res_dir.mkdir(parents=True, exist_ok=True)
(res_dir / 'payload.txt').write_bytes(payload_bytes)

add_resp = stub.AddTorrent(pb.AddTorrentRequest(
    envelope=env(rid, 'add-1'),
    torrent_file=torrent_bytes,
    storage_path=f'/data/resources/{rid}'
))
assert add_resp.accepted, f'AddTorrent failed: {add_resp.error}'

prio_resp = stub.UpdateFilePriorities(pb.UpdateFilePrioritiesRequest(
    envelope=env(rid, 'prio-1'),
    file_priorities=[1]
))
assert prio_resp.accepted, f'UpdateFilePriorities failed: {prio_resp.error}'

snap = wait_for_check(rid, [32768])
assert snap.metadata_ready and snap.verified_bytes == 32768
print(f'[PASS] GetSnapshot verified piece_granularity: state={snap.state} verified={snap.verified_bytes} file_verified_bytes={list(snap.file_verified_bytes)}')

# 4. SaveResumeData + Pause + Resume
resume_resp = stub.SaveResumeData(pb.SaveResumeDataRequest(envelope=env(rid, 'save-1')))
assert resume_resp.accepted and len(resume_resp.resume_data) > 0 and resume_resp.resume_version >= 1
(fixture_dir / 'saved_resume.dat').write_bytes(resume_resp.resume_data)
print(f'[PASS] SaveResumeData: bytes={len(resume_resp.resume_data)} version={resume_resp.resume_version}')

pause_resp = stub.Pause(pb.CommandRequest(envelope=env(rid, 'pause-1')))
assert pause_resp.accepted
resume_cmd = stub.Resume(pb.CommandRequest(envelope=env(rid, 'resume-1')))
assert resume_cmd.accepted
print('[PASS] Pause / Resume lifecycle')
"@ | Set-Content -Path $VerifyScript -Encoding UTF8

    python $VerifyScript

    Write-Host "==> [Step 5] Restarting Worker container to verify resume recovery and corruption detection..."
    Start-WorkerContainer

    $RestartScript = Join-Path $FixtureDir "run_restart_verify.py"
    @"
import pathlib
import sys
import time

sys.path.insert(0, r'$GrpcPyOut')
import grpc
import download_engine_pb2 as pb
import download_engine_pb2_grpc as pb_grpc

fixture_dir = pathlib.Path(r'$FixtureDir')
shared_data = pathlib.Path(r'$SharedDataDir')
payload_bytes = (fixture_dir / 'payload.txt').read_bytes()
torrent_bytes = (fixture_dir / 'payload.torrent').read_bytes()
resume_bytes = (fixture_dir / 'saved_resume.dat').read_bytes()

channel = grpc.insecure_channel('localhost:$HostPort')
stub = pb_grpc.DownloadEngineStub(channel)

def env(rid, cid='cmd-1'):
    return pb.CommandEnvelope(command_id=cid, resource_id=rid, owner_epoch=1)

rid = '11111111-2222-3333-4444-555555555555'

# 5a. Recover with saved resume bytes
add_resume = stub.AddTorrent(pb.AddTorrentRequest(
    envelope=env(rid, 'add-resume'),
    torrent_file=torrent_bytes,
    resume_data=resume_bytes,
    storage_path=f'/data/resources/{rid}'
))
assert add_resume.accepted, f'Resume AddTorrent failed: {add_resume.error}'

deadline = time.time() + 15.0
while time.time() < deadline:
    snap = stub.GetSnapshot(pb.GetSnapshotRequest(envelope=env(rid)))
    if list(snap.file_verified_bytes) == [32768]:
        print(f'[PASS] Recovery with resume_data: file_verified_bytes={list(snap.file_verified_bytes)}')
        break
    time.sleep(0.3)
else:
    raise AssertionError(f'Resume recovery did not reach 32768: {list(snap.file_verified_bytes)}')

# Remove session without deleting files, corrupt piece #1 (offset 20000), and re-attach without resume_data to force piece recheck
stub.RemoveTorrent(pb.RemoveTorrentRequest(envelope=env(rid, 'rm-keep'), remove_unreferenced_data=False))
corrupted = bytearray(payload_bytes)
corrupted[20000] ^= 0xFF
(shared_data / 'resources' / rid / 'payload.txt').write_bytes(bytes(corrupted))

add_recheck = stub.AddTorrent(pb.AddTorrentRequest(
    envelope=env(rid, 'add-recheck'),
    torrent_file=torrent_bytes,
    storage_path=f'/data/resources/{rid}'
))
assert add_recheck.accepted

deadline = time.time() + 15.0
while time.time() < deadline:
    snap = stub.GetSnapshot(pb.GetSnapshotRequest(envelope=env(rid)))
    if snap.state in ('ACTIVE', 'IDLE') and list(snap.file_verified_bytes) == [16384]:
        print(f'[PASS] Corruption detected on piece #1: verified_bytes dropped to {list(snap.file_verified_bytes)} (1 of 2 pieces valid)')
        break
    time.sleep(0.3)
else:
    raise AssertionError(f'Expected [16384] after corrupting piece #1, got state={snap.state} files={list(snap.file_verified_bytes)}')

# 6. RemoveTorrent(remove_unreferenced_data=True) cleans up disk files
rm_del = stub.RemoveTorrent(pb.RemoveTorrentRequest(envelope=env(rid, 'rm-del'), remove_unreferenced_data=True))
assert rm_del.accepted
deadline = time.time() + 10.0
target_file = shared_data / 'resources' / rid / 'payload.txt'
while time.time() < deadline and target_file.exists():
    time.sleep(0.2)
assert not target_file.exists(), 'payload.txt should be deleted when remove_unreferenced_data=True'
print('[PASS] RemoveTorrent(remove_unreferenced_data=True) deleted underlying file')
"@ | Set-Content -Path $RestartScript -Encoding UTF8

    python $RestartScript
    Write-Host "==> ALL WORKER SMOKE & RECOVERY VERIFICATIONS PASSED!"
} finally {
    docker rm -f $ContainerName 2>$null | Out-Null
}
