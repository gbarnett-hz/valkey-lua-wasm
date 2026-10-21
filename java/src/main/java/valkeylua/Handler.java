package valkeylua;

import java.util.List;

/** Host side of the redis.* / server.* Lua API. Every method returns a RESP2 reply. */
public interface Handler {

    /**
     * redis.call/redis.pcall: receives the command arguments, returns the command's RESP2 reply. Anything thrown
     * (including an {@code Error}) becomes an {@code -ERR <message>} reply.
     */
    byte[] call(List<byte[]> args);

    /** redis.setresp(version): default accepts 2 (+OK) and rejects anything else with an error reply. */
    default byte[] setResp(int version) {
        return (version == 2 ? "+OK\r\n" : "-ERR RESP3 is not supported\r\n").getBytes();
    }

    /** redis.set_repl(flags): default ignores the flags and replies +OK. */
    default byte[] setRepl(int flags) {
        return "+OK\r\n".getBytes();
    }

    /** redis.log(level, message): default discards the message and replies +OK. */
    default byte[] log(int level, String message) {
        return "+OK\r\n".getBytes();
    }

    /** redis.acl_check_cmd(argv...): default allows everything, replying :1. */
    default byte[] aclCheckCmd(List<byte[]> args) {
        return ":1\r\n".getBytes();
    }
}
