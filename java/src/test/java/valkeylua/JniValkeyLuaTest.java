package valkeylua;

class JniValkeyLuaTest extends ValkeyLuaTest {

    @Override
    ValkeyLua create(Handler handler, ServerInfo info) {
        return new JniValkeyLua(handler, info);
    }
}
