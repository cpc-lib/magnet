// libtorrent gRPC Sidecar Worker（规格 §7.1）。
// 职责：持有 libtorrent session + torrent_handle，实现 DownloadEngine 服务 RPC。
// 本文件实现 Health / AddTorrent / GetSnapshot / UpdateFilePriorities / Pause / Resume /
// RemoveTorrent / ResolveMetadata / SaveResumeData；StreamAlerts 明确留在 Task 3 范围之外。
//
// torrent_handle 不是线程安全的；每个 SessionEntry 的 operation_mutex 串行化该资源的 handle 访问。
// 会话按 resource_id（CommandEnvelope.resource_id）持久到进程内 map；进程 crash 丢失，
// 属规格 §12.1 允许（重启时 Java 控制面按 owner_epoch + DB 会话重新 AddTorrent）。

#include <grpcpp/grpcpp.h>

#include "download_engine.grpc.pb.h"
#include "download_engine.pb.h"

#include <libtorrent/session.hpp>
#include <libtorrent/torrent_handle.hpp>
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/torrent_status.hpp>
#include <libtorrent/info_hash.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/create_torrent.hpp>
#include <libtorrent/bencode.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/read_resume_data.hpp>
#include <libtorrent/write_resume_data.hpp>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <system_error>
#include <vector>

#include "resource_fences.h"

namespace {

using magnetplatform::engine::v1::DownloadEngine;
using magnetplatform::engine::v1::CommandRequest;
using magnetplatform::engine::v1::CommandResponse;
using magnetplatform::engine::v1::AddTorrentRequest;
using magnetplatform::engine::v1::AddTorrentResponse;
using magnetplatform::engine::v1::UpdateFilePrioritiesRequest;
using magnetplatform::engine::v1::UpdateFilePrioritiesResponse;
using magnetplatform::engine::v1::GetSnapshotRequest;
using magnetplatform::engine::v1::GetSnapshotResponse;
using magnetplatform::engine::v1::SaveResumeDataRequest;
using magnetplatform::engine::v1::SaveResumeDataResponse;
using magnetplatform::engine::v1::RemoveTorrentRequest;
using magnetplatform::engine::v1::HealthRequest;
using magnetplatform::engine::v1::HealthResponse;

// ---- Worker 内部状态 ----

struct SessionEntry {
    lt::torrent_handle handle;
    std::mutex operation_mutex;
    bool ready = false;
    bool removed = false;
    bool removing = false;
};

std::once_flag g_session_init;
std::unique_ptr<lt::session> g_session;
std::mutex g_handles_mutex;
std::map<std::string, std::shared_ptr<SessionEntry>> g_handles;
std::mutex g_resume_mutex;
std::map<std::string, std::int64_t> g_resume_versions;
std::mutex g_alert_mutex;
std::filesystem::path g_download_root;
std::unique_ptr<ResourceFences> g_fences;
std::mutex g_command_mutex;

std::shared_ptr<SessionEntry> find_session_entry(const std::string& rid) {
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto it = g_handles.find(rid);
    return it == g_handles.end() ? nullptr : it->second;
}

void erase_session_entry_if_current(const std::string& rid,
                                    const std::shared_ptr<SessionEntry>& entry) {
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto it = g_handles.find(rid);
    if (it != g_handles.end() && it->second == entry) {
        g_handles.erase(it);
    }
}

std::int64_t next_resume_version(const std::string& rid) {
    std::lock_guard<std::mutex> lock(g_resume_mutex);
    return ++g_resume_versions[rid];
}

template <typename Handle>
concept HasSynchronousResumeData = requires(Handle& handle) {
    lt::write_resume_data_buf(handle.get_resume_data(Handle::save_info_dict));
};

template <typename Handle>
std::optional<std::vector<char>> try_synchronous_resume_data(Handle& handle) {
    if constexpr (HasSynchronousResumeData<Handle>) {
        return lt::write_resume_data_buf(handle.get_resume_data(Handle::save_info_dict));
    } else {
        return std::nullopt;
    }
}

bool is_within_root(const std::filesystem::path& candidate,
                    const std::filesystem::path& root) {
    const std::filesystem::path relative = candidate.lexically_relative(root);
    if (relative.empty() || relative.is_absolute()) {
        return false;
    }
    const auto first = relative.begin();
    return first != relative.end() && *first != std::filesystem::path("..");
}

bool resolve_path_following_symlinks(const std::filesystem::path& input,
                                     int& symlink_hops,
                                     std::filesystem::path& out,
                                     std::string& error) {
    constexpr int kMaxSymlinkHops = 40;
    std::filesystem::path current = input.root_path();
    bool entered_root = is_within_root(current, g_download_root);

    for (const auto& part : input.relative_path()) {
        if (part.empty() || part == ".") {
            continue;
        }
        if (part == "..") {
            current = current.parent_path();
            if (entered_root && !is_within_root(current, g_download_root)) {
                error = "storage_path must stay under the Worker download root";
                return false;
            }
            continue;
        }

        current /= part;

        std::error_code ec;
        const std::filesystem::file_status st = std::filesystem::symlink_status(current, ec);
        if (ec && ec != std::errc::no_such_file_or_directory) {
            error = "storage_path 无法解析: " + ec.message();
            return false;
        }

        if (!ec && std::filesystem::is_symlink(st)) {
            if (++symlink_hops > kMaxSymlinkHops) {
                error = "storage_path 软链接层级过深";
                return false;
            }
            const std::filesystem::path target = std::filesystem::read_symlink(current, ec);
            if (ec) {
                error = "storage_path 无法读取软链接: " + ec.message();
                return false;
            }
            const std::filesystem::path combined =
                    target.is_absolute() ? target : (current.parent_path() / target);
            if (!resolve_path_following_symlinks(combined, symlink_hops, current, error)) {
                return false;
            }
        }

        if (is_within_root(current, g_download_root)) {
            entered_root = true;
        } else if (entered_root) {
            error = "storage_path must stay under the Worker download root";
            return false;
        }
    }

    out = current.lexically_normal();
    return true;
}

bool resolve_storage_path(const std::string& requested,
                          std::filesystem::path& resolved,
                          std::string& error) {
    if (requested.empty()) {
        error = "storage_path is required";
        return false;
    }

    std::filesystem::path candidate(requested);
    if (!candidate.is_absolute()) {
        candidate = g_download_root / candidate;
    }

    int symlink_hops = 0;
    std::filesystem::path canonical_candidate;
    if (!resolve_path_following_symlinks(candidate, symlink_hops, canonical_candidate, error)) {
        return false;
    }

    if (!is_within_root(canonical_candidate, g_download_root)) {
        error = "storage_path must stay under the Worker download root";
        return false;
    }

    resolved = canonical_candidate;
    return true;
}

void init_session() {
    std::call_once(g_session_init, []() {
        lt::session_params p;
        // libtorrent 2.0.8 保留的 setting
        p.settings.set_bool(lt::settings_pack::enable_dht, true);
        p.settings.set_bool(lt::settings_pack::enable_lsd, true);
        p.settings.set_int(lt::settings_pack::alert_mask, static_cast<int>(
                lt::alert_category::error | lt::alert_category::storage | lt::alert_category::status));
        p.settings.set_str(lt::settings_pack::listen_interfaces, "0.0.0.0:6881");
        g_session = std::make_unique<lt::session>(std::move(p));
        std::cout << "[worker] libtorrent session 初始化" << std::endl;
    });
}

bool await_removal(const lt::torrent_handle& handle, bool deleting) {
    std::lock_guard<std::mutex> alerts_guard(g_alert_mutex);
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < until) {
        g_session->wait_for_alert(lt::milliseconds(50));
        std::vector<lt::alert*> alerts;
        g_session->pop_alerts(&alerts);
        for (const auto* alert : alerts) {
            if (deleting) {
                if (const auto* done = lt::alert_cast<lt::torrent_deleted_alert>(alert);
                        done != nullptr && done->handle == handle) return true;
            } else {
                if (const auto* done = lt::alert_cast<lt::torrent_removed_alert>(alert);
                        done != nullptr && done->handle == handle) return true;
            }
        }
    }
    return false;
}

// ---- gRPC 服务实现 ----

class DownloadEngineImpl final : public DownloadEngine::Service {
public:
    grpc::Status Health(grpc::ServerContext* /*context*/, const HealthRequest* /*req*/,
                       HealthResponse* resp) override {
        resp->set_ok(true);
        resp->set_worker_id(std::getenv("WORKER_ID") ? std::getenv("WORKER_ID") : "local-worker-1");
        resp->set_libtorrent_version("2.0.8");
        return grpc::Status::OK;
    }

    grpc::Status AddTorrent(grpc::ServerContext* /*context*/, const AddTorrentRequest* req,
                            AddTorrentResponse* resp) override {
        const std::string& rid = req->envelope().resource_id();
        std::filesystem::path storage_path;
        std::string storage_error;
        if (!resolve_storage_path(req->storage_path(), storage_path, storage_error)) {
            resp->set_accepted(false);
            resp->set_error(storage_error);
            return grpc::Status::OK;
        }

        const auto expected_path = (g_download_root / "resources" / rid).lexically_normal();
        if (storage_path != expected_path) {
            return {grpc::StatusCode::FAILED_PRECONDITION, "storage_path must match resources/resource_id"};
        }
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), true, !req->metadata_only());
        if (!fenced.ok()) return fenced;
        init_session();

        std::shared_ptr<SessionEntry> entry;
        std::unique_lock<std::mutex> entry_lock;
        for (;;) {
            auto candidate = std::make_shared<SessionEntry>();
            std::unique_lock<std::mutex> candidate_lock(candidate->operation_mutex);
            bool inserted = false;
            {
                std::lock_guard<std::mutex> lock(g_handles_mutex);
                auto it = g_handles.find(rid);
                if (it == g_handles.end()) {
                    entry = candidate;
                    g_handles.emplace(rid, entry);
                    inserted = true;
                } else {
                    entry = it->second;
                }
            }
            if (inserted) {
                entry_lock = std::move(candidate_lock);
                break;
            }

            candidate_lock.unlock();
            std::unique_lock<std::mutex> existing_lock(entry->operation_mutex);
            if (entry->removing) {
                return {grpc::StatusCode::FAILED_PRECONDITION, "previous removal has not drained"};
            }
            if (!entry->removed && entry->ready && entry->handle.is_valid()) {
                const bool upgrading_with_metadata =
                        !entry->handle.status().has_metadata &&
                        (!req->torrent_file().empty() || !req->resume_data().empty());
                if (!upgrading_with_metadata) {
                    if (!req->metadata_only()) {
                        entry->handle.resume();
                    }
                    resp->set_accepted(true);
                    return grpc::Status::OK;
                }
                g_session->remove_torrent(entry->handle);
                if (!await_removal(entry->handle, false)) {
                    entry->removing = true;
                    std::_Exit(75); // No safe drain confirmation: restart before reusing storage.
                }
                entry->ready = false;
                entry->handle = lt::torrent_handle();
                entry_lock = std::move(existing_lock);
                break;
            }
            erase_session_entry_if_current(rid, entry);
        }

        auto fail_add = [&](const std::string& message) {
            entry->removed = true;
            erase_session_entry_if_current(rid, entry);
            resp->set_accepted(false);
            resp->set_error(message);
            return grpc::Status::OK;
        };

        lt::add_torrent_params p;

        if (!req->resume_data().empty()) {
            const std::string& bytes = req->resume_data();
            lt::error_code ec;
            p = lt::read_resume_data(
                    lt::span<const char>(bytes.data(), bytes.size()), ec);
            if (ec) {
                return fail_add("resume_data 解析失败: " + ec.message());
            }
        } else if (!req->torrent_file().empty()) {
            // 优先使用显式提供的 torrent_file 字节
            const std::string& bytes = req->torrent_file();
            // libtorrent 2.0.8 bdecode 签名：(const char*, const char*, error_code&)
            // torrent_info(bdecode_node const&) 或 (bdecode_node const&, error_code&)
            // libtorrent 2.0.8: bdecode(span<const char>, error_code&, ...)
            // torrent_info(bdecode_node const&, error_code&)
            lt::error_code ec;
            lt::bdecode_node root = lt::bdecode(lt::span<const char>(bytes.data(), bytes.size()), ec);
            if (!ec) {
                lt::torrent_info ti(root, ec);
                if (!ec) {
                    p.ti = std::make_shared<lt::torrent_info>(std::move(ti));
                } else {
                    return fail_add("torrent_info 构造失败: " + ec.message());
                }
            } else {
                return fail_add("torrent_file 解析失败: " + ec.message());
            }
        } else if (!req->btih().empty() || !req->btmh().empty()) {
            // 空 torrent_file 但有哈希 → 按 info_hash 挂会话，DHT 抓元数据（metadata_only 场景）
            if (req->btih().size() == 20) {
                lt::sha1_hash v1;
                std::memcpy(v1.data(), req->btih().data(), 20);
                p.info_hashes.v1 = v1;
            }
            if (req->btmh().size() == 32) {
                lt::sha256_hash v2;
                std::memcpy(v2.data(), req->btmh().data(), 32);
                p.info_hashes.v2 = v2;
            }
            for (const auto& tr : req->trackers()) {
                p.trackers.push_back(tr);
            }
            if (p.trackers.empty()) {
                // magnet 未携带 tr= 时的公共 tracker 兜底：纯 DHT 冷启动过慢
                for (const char* tr : {
                        "udp://tracker.openbittorrent.com:6969/announce",
                        "udp://tracker.opentrackr.org:1337/announce",
                        "udp://open.stealth.si:80/announce",
                        "udp://exodus.desync.com:6969/announce"}) {
                    p.trackers.emplace_back(tr);
                }
            }
            if (req->metadata_only()) {
                // 文件默认优先级 0：只抓元数据，不下负载
                p.flags |= lt::torrent_flags::default_dont_download;
            }
            std::cout << "[worker] AddTorrent by-hash resource=" << rid
                      << " v1=" << (req->btih().size() == 20) << " v2=" << (req->btmh().size() == 32)
                      << " trackers=" << req->trackers_size() << std::endl;
        } else {
            std::cout << "[worker] AddTorrent 跳过（无 torrent_file 且无哈希）resource=" << rid << std::endl;
            return fail_add("no torrent_file and no info hash");
        }

        // The request path is the only storage location trusted by the Worker,
        // including when the add_torrent_params came from resume data.
        p.save_path = storage_path.string();
        // Resume is a hint, never proof that disk bytes are still valid after takeover.
        p.flags &= ~lt::torrent_flags::seed_mode;
        p.have_pieces.clear();
        p.verified_pieces.clear();
        p.unfinished_pieces.clear();
        if (req->metadata_only()) {
            p.flags |= lt::torrent_flags::default_dont_download;
        }

        lt::error_code ec;
        lt::torrent_handle h = g_session->add_torrent(std::move(p), ec);
        if (ec) {
            return fail_add("add_torrent 失败: " + ec.message());
        }
        entry->handle = h;
        if (!req->metadata_only()) h.force_recheck();
        entry->ready = true;
        std::cout << "[worker] AddTorrent resource=" << rid << " state=" << (int)h.status().state << std::endl;
        resp->set_accepted(true);
        return grpc::Status::OK;
    }

    grpc::Status UpdateFilePriorities(grpc::ServerContext* /*context*/,
                                      const UpdateFilePrioritiesRequest* req,
                                      UpdateFilePrioritiesResponse* resp) override {
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, true);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        const auto entry = find_session_entry(rid);
        if (!entry) {
            resp->set_accepted(false);
            resp->set_error("resource_id handle not found");
            return grpc::Status::OK;
        }
        std::lock_guard<std::mutex> lock(entry->operation_mutex);
        if (entry->removed || !entry->ready || !entry->handle.is_valid()) {
            resp->set_accepted(false);
            resp->set_error("resource_id handle not found");
            return grpc::Status::OK;
        }
        std::vector<int> priorities;
        priorities.reserve(req->file_priorities_size());
        for (int i = 0; i < req->file_priorities_size(); ++i) {
            priorities.push_back(req->file_priorities(i));
        }
        entry->handle.prioritize_files(priorities);
        resp->set_accepted(true);
        return grpc::Status::OK;
    }

    grpc::Status GetSnapshot(grpc::ServerContext* /*context*/, const GetSnapshotRequest* req,
                             GetSnapshotResponse* resp) override {
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, false);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        const auto entry = find_session_entry(rid);
        if (!entry) {
            // handle 还没创建 —— 正常：Java 端可能先 snapshot 后 createOrAttach
            resp->set_state("DISCOVERING");
            resp->set_metadata_ready(false);
            return grpc::Status::OK;
        }
        std::lock_guard<std::mutex> lock(entry->operation_mutex);
        if (entry->removed || !entry->ready || !entry->handle.is_valid()) {
            resp->set_state("DISCOVERING");
            resp->set_metadata_ready(false);
            return grpc::Status::OK;
        }
        lt::torrent_status st = entry->handle.status();
        const char* state_str = "IDLE";
        // libtorrent 2.0.x torrent_status::state（queued_for_checking 被 deprecated 但仍存在）
        switch (st.state) {
            case lt::torrent_status::queued_for_checking:
            case lt::torrent_status::checking_files:
            case lt::torrent_status::checking_resume_data:
                state_str = "RECOVERING"; break;
            case lt::torrent_status::downloading_metadata:
                state_str = "DISCOVERING"; break;
            case lt::torrent_status::downloading:
            case lt::torrent_status::finished:
            case lt::torrent_status::seeding:
                state_str = st.has_metadata ? "ACTIVE" : "METADATA_READY"; break;
            default:
                state_str = "IDLE"; break;
        }
        resp->set_state(state_str);
        resp->set_metadata_ready(st.has_metadata);
        resp->set_verified_bytes(st.total_done);
        resp->set_download_rate_bytes_per_sec(st.download_rate);
        resp->set_upload_rate_bytes_per_sec(st.upload_rate);
        resp->set_peer_count(st.num_peers);
        const std::vector<std::int64_t> file_progress =
                entry->handle.file_progress(lt::torrent_handle::piece_granularity);
        for (const std::int64_t bytes : file_progress) {
            resp->add_file_verified_bytes(bytes);
        }
        return grpc::Status::OK;
    }

    // ---- 生命周期与恢复 RPC ----

    grpc::Status Pause(grpc::ServerContext*, const CommandRequest* req, CommandResponse* resp) override {
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, false);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        if (const auto entry = find_session_entry(rid)) {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (!entry->removed && entry->ready && entry->handle.is_valid()) {
                entry->handle.pause();
            }
        }
        resp->set_accepted(true);
        return grpc::Status::OK;
    }
    grpc::Status Resume(grpc::ServerContext*, const CommandRequest* req, CommandResponse* resp) override {
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, true);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        if (const auto entry = find_session_entry(rid)) {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (!entry->removed && entry->ready && entry->handle.is_valid()) {
                entry->handle.resume();
            }
        }
        resp->set_accepted(true);
        return grpc::Status::OK;
    }
    grpc::Status SaveResumeData(grpc::ServerContext*, const SaveResumeDataRequest* req,
                                SaveResumeDataResponse* resp) override {
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, true);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        const auto entry = find_session_entry(rid);
        if (!entry) {
            resp->set_accepted(false);
            resp->set_error("resource_id handle not found");
            return grpc::Status::OK;
        }

        lt::torrent_handle handle;
        {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (entry->removed || !entry->ready) {
                resp->set_accepted(false);
                resp->set_error("resource_id handle not found");
                return grpc::Status::OK;
            }
            if (!entry->handle.is_valid()) {
                resp->set_accepted(false);
                resp->set_error("resource_id handle is invalid");
                return grpc::Status::OK;
            }
            handle = entry->handle;

            // Prefer a synchronous get_resume_data API when available. Debian bookworm's
            // libtorrent-rasterbar 2.0.8 has no such API, so it uses the alert fallback below.
            if (const auto synchronous = try_synchronous_resume_data(handle);
                synchronous.has_value()) {
                if (synchronous->empty()) {
                    resp->set_accepted(false);
                    resp->set_error("resume data 为空");
                    return grpc::Status::OK;
                }
                resp->set_resume_data(synchronous->data(), synchronous->size());
                resp->set_resume_version(next_resume_version(rid));
                resp->set_accepted(true);
                return grpc::Status::OK;
            }
        }

        std::lock_guard<std::mutex> alert_lock(g_alert_mutex);
        {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (entry->removed || !entry->ready) {
                resp->set_accepted(false);
                resp->set_error("resource_id handle not found");
                return grpc::Status::OK;
            }
            if (!entry->handle.is_valid()) {
                resp->set_accepted(false);
                resp->set_error("resource_id handle is invalid");
                return grpc::Status::OK;
            }
            handle = entry->handle;
            handle.save_resume_data(lt::torrent_handle::save_info_dict);
        }

        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (std::chrono::steady_clock::now() < deadline) {
            g_session->wait_for_alert(lt::milliseconds(100));
            std::vector<lt::alert*> alerts;
            g_session->pop_alerts(&alerts);
            for (lt::alert* alert : alerts) {
                if (const auto* failed = lt::alert_cast<lt::save_resume_data_failed_alert>(alert);
                    failed != nullptr && failed->handle == handle) {
                    resp->set_accepted(false);
                    resp->set_error("保存 resume data 失败: " + failed->error.message());
                    return grpc::Status::OK;
                }
                if (const auto* saved = lt::alert_cast<lt::save_resume_data_alert>(alert);
                    saved != nullptr && saved->handle == handle) {
                    const std::vector<char> bytes = lt::write_resume_data_buf(saved->params);
                    if (bytes.empty()) {
                        resp->set_accepted(false);
                        resp->set_error("resume data 为空");
                        return grpc::Status::OK;
                    }
                    resp->set_resume_data(bytes.data(), bytes.size());
                    resp->set_resume_version(next_resume_version(rid));
                    resp->set_accepted(true);
                    return grpc::Status::OK;
                }
            }
        }

        resp->set_accepted(false);
        resp->set_error("等待 resume data 超时");
        return grpc::Status::OK;
    }
    grpc::Status RemoveTorrent(grpc::ServerContext*, const RemoveTorrentRequest* req,
                               CommandResponse* resp) override {
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, req->remove_unreferenced_data());
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        if (const auto entry = find_session_entry(rid)) {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (!entry->removing && entry->ready && entry->handle.is_valid()) {
                entry->removing = true;
                g_session->remove_torrent(entry->handle, req->remove_unreferenced_data()
                        ? lt::session_handle::delete_files : lt::remove_flags_t{});
            }
            if (entry->removing && !await_removal(entry->handle, req->remove_unreferenced_data())) {
                std::_Exit(75); // Keep GC fail-closed if disk jobs cannot be confirmed drained.
            }
            if (req->remove_unreferenced_data()) {
                // Delete the whole resource tree while the OS fence is still held.
                // Java must never race a delayed Add by deleting this tree after ACK.
                std::error_code cleanup_error;
                std::filesystem::remove_all(g_download_root / "resources" / rid, cleanup_error);
                if (cleanup_error) std::_Exit(75);
            }
            entry->removed = true;
            entry->ready = false;
            erase_session_entry_if_current(rid, entry);
            g_fences->release_drained(rid);
        }
        resp->set_accepted(true);
        return grpc::Status::OK;
    }
    // 轮询式：会话已由 AddTorrent 挂上；元数据就绪→返回 .torrent 字节，未就绪→PENDING
    grpc::Status ResolveMetadata(grpc::ServerContext*, const magnetplatform::engine::v1::ResolveMetadataRequest* req,
                                 magnetplatform::engine::v1::ResolveMetadataResponse* resp) override {
        std::lock_guard<std::mutex> command_lock(g_command_mutex);
        const auto fenced = g_fences->validate(req->envelope(), false, false);
        if (!fenced.ok()) return fenced;
        const std::string& rid = req->envelope().resource_id();
        const auto entry = find_session_entry(rid);
        if (!entry) {
            resp->set_accepted(false);
            resp->set_error("会话不存在（需先 AddTorrent）");
            return grpc::Status::OK;
        }
        std::shared_ptr<const lt::torrent_info> ti;
        {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            if (entry->removed || !entry->ready || !entry->handle.is_valid()) {
                resp->set_accepted(false);
                resp->set_error("会话不存在（需先 AddTorrent）");
                return grpc::Status::OK;
            }
            if (!entry->handle.status().has_metadata) {
                resp->set_accepted(true);
                resp->set_error("PENDING");
                return grpc::Status::OK;
            }
            ti = entry->handle.torrent_file();
        }
        if (!ti) {
            resp->set_accepted(true);
            resp->set_error("PENDING");
            return grpc::Status::OK;
        }
        // 从 torrent_info 重建 .torrent 字节（保留 trackers）
        lt::create_torrent ct(*ti);
        for (const auto& tr : ti->trackers()) {
            ct.add_tracker(tr.url, tr.tier);
        }
        std::vector<char> buf;
        lt::bencode(std::back_inserter(buf), ct.generate());
        resp->set_accepted(true);
        resp->set_torrent_file(buf.data(), buf.size());
        if (req->envelope().owner_epoch() == 0) {
            std::lock_guard<std::mutex> lock(entry->operation_mutex);
            g_session->remove_torrent(entry->handle);
            entry->removing = true;
            if (!await_removal(entry->handle, false)) {
                std::_Exit(75);
            }
            entry->removed = true;
            erase_session_entry_if_current(rid, entry);
            g_fences->release_drained(rid);
        }
        std::cout << "[worker] ResolveMetadata 就绪 resource=" << rid
                  << " bytes=" << buf.size() << " files=" << ti->num_files() << std::endl;
        return grpc::Status::OK;
    }
    grpc::Status StreamAlerts(grpc::ServerContext* context,
                             const magnetplatform::engine::v1::AlertStreamRequest* /*req*/,
                             grpc::ServerWriter<magnetplatform::engine::v1::Alert>* /*writer*/) override {
        context->TryCancel();
        return grpc::Status(grpc::StatusCode::UNIMPLEMENTED, "StreamAlerts 尚未实现");
    }
};

}  // namespace

int main(int argc, char* argv[]) {
    std::string listen_addr = "0.0.0.0:50051";
    std::string download_root = "/data";
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        if (a.rfind("--port=", 0) == 0) {
            listen_addr = "0.0.0.0:" + a.substr(7);
        } else if (a.rfind("--download-root=", 0) == 0) {
            download_root = a.substr(std::string("--download-root=").size());
        } else if (a == "--download-root" && i + 1 < argc) {
            download_root = argv[++i];
        }
    }
    std::error_code path_ec;
    const std::filesystem::path abs_root = std::filesystem::absolute(download_root, path_ec);
    if (path_ec) {
        std::cerr << "[worker] 无法规范化 --download-root: " << path_ec.message() << std::endl;
        return 1;
    }
    g_download_root = std::filesystem::weakly_canonical(abs_root, path_ec);
    if (path_ec) {
        std::cerr << "[worker] 无法解析 --download-root: " << path_ec.message() << std::endl;
        return 1;
    }
    std::cout << "[worker] 启动 gRPC 服务监听 " << listen_addr
              << " download-root=" << g_download_root << std::endl;

    g_fences = std::make_unique<ResourceFences>(std::getenv("LOCK_ROOT") ? std::getenv("LOCK_ROOT") : "/locks");
    std::thread([] { g_fences->watchdog(); }).detach();
    DownloadEngineImpl service;
    grpc::ServerBuilder builder;
    builder.AddListeningPort(listen_addr, grpc::InsecureServerCredentials());
    builder.RegisterService(&service);
    std::unique_ptr<grpc::Server> server(builder.BuildAndStart());
    std::cout << "[worker] gRPC 服务已就绪，等待 Java 控制面连接" << std::endl;
    server->Wait();
    return 0;
}
