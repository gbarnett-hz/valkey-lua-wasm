package valkeylua;

/** Version and name values exposed to scripts as redis.REDIS_VERSION, VALKEY_VERSION and SERVER_NAME. */
public record ServerInfo(String redisVersion, String valkeyVersion, String serverName) {
    public static final ServerInfo DEFAULT = new ServerInfo("7.2.4", "255.255.255", "valkey");
}
