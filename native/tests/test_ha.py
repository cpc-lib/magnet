"""Real dual-Worker fencing tests. Uses only isolated, automatically removed test volumes.

Run: docker build -t magnet-libtorrent-worker:ha-test native
     python native/tests/test_ha.py
Requires grpcio/grpcio-tools; no trackers or external torrent downloads are needed.
"""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

import grpc
import grpc_tools


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, encoding="utf-8", errors="replace").strip()


class WorkerHaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="magnet-ha-")
        proto = Path(__file__).resolve().parents[1] / "proto"
        include = Path(grpc_tools.__file__).parent / "_proto"
        subprocess.check_call([sys.executable, "-m", "grpc_tools.protoc", f"-I{proto}",
                               f"-I{include}", f"--python_out={cls.temp.name}",
                               f"--grpc_python_out={cls.temp.name}", str(proto / "download_engine.proto")])
        sys.path.insert(0, cls.temp.name)
        import download_engine_pb2 as pb
        import download_engine_pb2_grpc as rpc
        cls.pb, cls.rpc = pb, rpc
        cls.names = ["magnet-ha-test-" + uuid.uuid4().hex[:12] for _ in range(2)]
        cls.volumes = []
        try:
            for _ in range(2):
                cls.volumes.append(docker("volume", "create", "--label", "purpose=magnet-ha-test"))
            for name in cls.names:
                docker("run", "-d", "--name", name, "-p", "127.0.0.1::50051",
                       "-v", cls.volumes[0] + ":/data", "-v", cls.volumes[1] + ":/locks",
                       "-e", "WORKER_ID=" + name,
                       os.getenv("HA_TEST_IMAGE", "magnet-libtorrent-worker:ha-test"))
        except Exception:
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        for name in cls.names:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True)
        for volume in cls.volumes:
            subprocess.run(["docker", "volume", "rm", volume], capture_output=True)
        cls.temp.cleanup()

    def setUp(self):
        self.channels = []
        self.stubs = []
        for name in self.names:
            docker("restart", name)
            port = docker("port", name, "50051/tcp").split(":")[-1]
            channel = grpc.insecure_channel("127.0.0.1:" + port)
            grpc.channel_ready_future(channel).result(timeout=20)
            self.channels.append(channel)
            self.stubs.append(self.rpc.DownloadEngineStub(channel))
        self.rid = str(uuid.uuid4())
        self.payload = b"A" * 32768
        piece = hashlib.sha1(self.payload[:16384]).digest()
        self.torrent = (b"d4:infod6:lengthi32768e4:name11:payload.bin12:piece lengthi16384e"
                        b"6:pieces40:" + piece * 2 + b"ee")
        payload = Path(self.temp.name) / "payload.bin"
        payload.write_bytes(self.payload)
        docker("exec", self.names[0], "mkdir", "-p", "/data/resources/" + self.rid)
        docker("cp", str(payload), self.names[0] + ":/data/resources/" + self.rid + "/payload.bin")

    def tearDown(self):
        for channel in self.channels:
            channel.close()

    def envelope(self, epoch, ttl=60, deadline=5):
        env = self.pb.CommandEnvelope(command_id=str(uuid.uuid4()), resource_id=self.rid, owner_epoch=epoch)
        env.deadline.FromMilliseconds(int((time.time() + deadline) * 1000))
        env.lease_until.FromMilliseconds(int((time.time() + ttl) * 1000))
        return env

    def add(self, index, epoch, ttl=60, resume=b""):
        result = self.stubs[index].AddTorrent(self.pb.AddTorrentRequest(
            envelope=self.envelope(epoch, ttl), torrent_file=self.torrent, resume_data=resume,
            storage_path="resources/" + self.rid), timeout=5)
        self.assertTrue(result.accepted, result.error)

    def assertDenied(self, call):
        try:
            response = call()
        except grpc.RpcError as error:
            self.assertIn(error.code(), [grpc.StatusCode.FAILED_PRECONDITION,
                                         grpc.StatusCode.DEADLINE_EXCEEDED])
        else:
            self.assertFalse(response.accepted, "unsafe command was accepted")

    def test_two_workers_cannot_write_same_resource(self):
        self.add(0, 1)
        self.assertDenied(lambda: self.stubs[1].AddTorrent(self.pb.AddTorrentRequest(
            envelope=self.envelope(2), torrent_file=self.torrent,
            storage_path="resources/" + self.rid), timeout=5))

    def test_deadline_and_old_epoch_are_rejected(self):
        self.add(0, 2)
        self.assertDenied(lambda: self.stubs[0].Pause(self.pb.CommandRequest(
            envelope=self.envelope(1)), timeout=5))
        self.assertDenied(lambda: self.stubs[0].Resume(self.pb.CommandRequest(
            envelope=self.envelope(2, deadline=-1)), timeout=5))

    def test_crash_takeover_rechecks_disk_and_persists_epoch(self):
        self.add(0, 1)
        docker("kill", self.names[0])
        self.add(1, 2)
        until = time.monotonic() + 15
        while True:
            snap = self.stubs[1].GetSnapshot(self.pb.GetSnapshotRequest(envelope=self.envelope(2)), timeout=5)
            if list(snap.file_verified_bytes) == [len(self.payload)]:
                break
            self.assertLess(time.monotonic(), until, "takeover never verified disk content")
            time.sleep(.1)
        docker("restart", self.names[1])
        self.channels[1].close()
        port = docker("port", self.names[1], "50051/tcp").split(":")[-1]
        self.channels[1] = grpc.insecure_channel("127.0.0.1:" + port)
        self.stubs[1] = self.rpc.DownloadEngineStub(self.channels[1])
        grpc.channel_ready_future(self.channels[1]).result(timeout=20)
        self.assertDenied(lambda: self.stubs[1].AddTorrent(self.pb.AddTorrentRequest(
            envelope=self.envelope(1), torrent_file=self.torrent,
            storage_path="resources/" + self.rid), timeout=5))

    def test_lease_expiry_kills_worker_without_heartbeat(self):
        self.add(0, 1, ttl=1)
        until = time.monotonic() + 8
        while docker("inspect", "-f", "{{.State.Running}}", self.names[0]) == "true":
            self.assertLess(time.monotonic(), until, "expired Worker continued running")
            time.sleep(.15)
        self.add(1, 2)

    def test_takeover_does_not_trust_resume_for_corrupted_disk(self):
        self.add(0, 1)
        until = time.monotonic() + 15
        while True:
            snapshot = self.stubs[0].GetSnapshot(self.pb.GetSnapshotRequest(envelope=self.envelope(1)), timeout=5)
            if snapshot.state == "ACTIVE" and list(snapshot.file_verified_bytes) == [32768]:
                break
            self.assertLess(time.monotonic(), until)
            time.sleep(.1)
        saved = self.stubs[0].SaveResumeData(self.pb.SaveResumeDataRequest(envelope=self.envelope(1)), timeout=5)
        self.assertTrue(saved.accepted, saved.error)
        path = "/data/resources/" + self.rid + "/payload.bin"
        mtime = docker("exec", self.names[0], "stat", "-c", "%Y", path)
        docker("kill", self.names[0])
        corrupt = Path(self.temp.name) / "corrupt.bin"
        corrupt.write_bytes(b"B" + self.payload[1:])
        docker("cp", str(corrupt), self.names[1] + ":" + path)
        docker("exec", self.names[1], "touch", "-d", "@" + mtime, path)
        self.add(1, 2, resume=saved.resume_data)
        until = time.monotonic() + 15
        while True:
            snapshot = self.stubs[1].GetSnapshot(self.pb.GetSnapshotRequest(envelope=self.envelope(2)), timeout=5)
            if snapshot.state == "ACTIVE" and list(snapshot.file_verified_bytes) == [16384]:
                break
            if time.monotonic() >= until:
                self.fail("corrupted snapshot: " + str(snapshot) + "\n" + docker("logs", self.names[1]))
            time.sleep(.1)

    def test_metadata_cannot_download_or_delete_and_releases_lock_when_resolved(self):
        response = self.stubs[0].AddTorrent(self.pb.AddTorrentRequest(
            envelope=self.envelope(0), torrent_file=self.torrent, metadata_only=True,
            storage_path="resources/" + self.rid), timeout=5)
        self.assertTrue(response.accepted, response.error)
        self.assertDenied(lambda: self.stubs[0].UpdateFilePriorities(self.pb.UpdateFilePrioritiesRequest(
            envelope=self.envelope(0), file_priorities=[1]), timeout=5))
        self.assertDenied(lambda: self.stubs[0].RemoveTorrent(self.pb.RemoveTorrentRequest(
            envelope=self.envelope(0), remove_unreferenced_data=True), timeout=5))
        metadata = self.stubs[0].ResolveMetadata(self.pb.ResolveMetadataRequest(envelope=self.envelope(0)), timeout=5)
        self.assertTrue(metadata.accepted, metadata.error)
        self.assertTrue(metadata.torrent_file)
        self.add(1, 1)

    def test_remove_acknowledges_disk_deletion_before_lock_release(self):
        self.add(0, 1)
        result = self.stubs[0].RemoveTorrent(self.pb.RemoveTorrentRequest(
            envelope=self.envelope(1), remove_unreferenced_data=True), timeout=5)
        self.assertTrue(result.accepted, result.error)
        exists = subprocess.run(["docker", "exec", self.names[0], "test", "-e",
                                 "/data/resources/" + self.rid])
        self.assertEqual(1, exists.returncode)
        self.add(1, 2)

    def test_discarded_remove_ack_does_not_strand_database_owner_epoch(self):
        self.add(0, 1)
        # Discard the ACK, as if the API crashed before committing STOPPED.
        self.stubs[0].RemoveTorrent(self.pb.RemoveTorrentRequest(
            envelope=self.envelope(1), remove_unreferenced_data=True), timeout=5)
        self.add(0, 1)
        result = self.stubs[0].RemoveTorrent(self.pb.RemoveTorrentRequest(
            envelope=self.envelope(1), remove_unreferenced_data=True), timeout=5)
        self.assertTrue(result.accepted, result.error)
        self.add(1, 2)

    def test_frozen_owner_retains_lock_until_process_exits(self):
        self.add(0, 1, ttl=1)
        docker("kill", "--signal=STOP", self.names[0])
        try:
            time.sleep(1.3)
            self.assertDenied(lambda: self.stubs[1].AddTorrent(self.pb.AddTorrentRequest(
                envelope=self.envelope(2), torrent_file=self.torrent,
                storage_path="resources/" + self.rid), timeout=5))
        finally:
            docker("kill", "--signal=CONT", self.names[0])
        until = time.monotonic() + 8
        while docker("inspect", "-f", "{{.State.Running}}", self.names[0]) == "true":
            self.assertLess(time.monotonic(), until)
            time.sleep(.1)
        self.add(1, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
