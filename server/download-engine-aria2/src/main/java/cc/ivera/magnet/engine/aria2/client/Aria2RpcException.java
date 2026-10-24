package cc.ivera.magnet.engine.aria2.client;

public class Aria2RpcException extends RuntimeException {

    private final int code;

    public Aria2RpcException(int code, String message) {
        super("aria2 RPC 错误 [" + code + "]: " + message);
        this.code = code;
    }

    public Aria2RpcException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    public int getCode() {
        return code;
    }
}
