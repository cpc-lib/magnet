package com.magnetplatform.engine.aria2;

/**
 * aria2 JSON-RPC Client 占位（P6 里程碑：HTTP(S)/Metalink 引擎）。
 * P0+P1 不注册任何 Bean，避免被 EngineRegistry 收集；接入时实现
 * com.magnetplatform.spi.DownloadEngine 并声明 kind()=ARIA2。
 * 注意：aria2 不与 libtorrent 共享 BT Session 语义（规格 §7）。
 */
final class Aria2EnginePlaceholder {

    private Aria2EnginePlaceholder() {
    }
}
