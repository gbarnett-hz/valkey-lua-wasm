/* What luajava 4.0.2's natives lack for parity with lua.wasm; called by JniValkeyLua.java. */
#include <jni.h>
#include <stdlib.h>

#include "fpconv_dtoa.h"
#include "lauxlib.h"
#include "lua.h"
#include "../lua/valkey_reply.h"

int luaopen_cjson(lua_State *L);
int luaopen_struct(lua_State *L);
int luaopen_cmsgpack(lua_State *L);
int luaopen_bit(lua_State *L);
void luaLockGlobals(lua_State *L); /* lua/valkey_globals.c */

/* Mirrors luaLoadLib in valkey src/modules/lua/script_lua.c. */
static void loadLib(lua_State *L, const char *name, lua_CFunction f) {
    lua_pushcfunction(L, f);
    lua_pushstring(L, name);
    lua_call(L, 1, 0);
}

/* luajava's luaJ_openlib only knows the stock Lua libraries. */
JNIEXPORT void JNICALL Java_valkeylua_JniValkeyLua_openValkeyLibs(JNIEnv *env, jclass cls, jlong L) {
    loadLib((lua_State *)L, "cjson", luaopen_cjson);
    loadLib((lua_State *)L, "struct", luaopen_struct);
    loadLib((lua_State *)L, "cmsgpack", luaopen_cmsgpack);
    loadLib((lua_State *)L, "bit", luaopen_bit);
}

/* luajava only pushes Java Strings (modified UTF-8, cut at NUL); this pushes raw bytes. */
JNIEXPORT void JNICALL Java_valkeylua_JniValkeyLua_pushlstring(JNIEnv *env, jclass cls, jlong L, jbyteArray s, jint off,
                                                            jint len) {
    jbyte *b = (*env)->GetByteArrayElements(env, s, NULL);
    lua_pushlstring((lua_State *)L, (const char *)b + off, len);
    (*env)->ReleaseByteArrayElements(env, s, b, JNI_ABORT);
}

/* Valkey formats non-integer call arguments with fpconv_dtoa (luaArgsToServerArgv). */
JNIEXPORT jbyteArray JNICALL Java_valkeylua_JniValkeyLua_fpconvDtoa(JNIEnv *env, jclass cls, jdouble d) {
    char buf[24];
    int n = fpconv_dtoa(d, buf);
    jbyteArray a = (*env)->NewByteArray(env, n);
    (*env)->SetByteArrayRegion(env, a, 0, n, (const jbyte *)buf);
    return a;
}

/* Valkey's globals protection; luajava doesn't know the readonly-table API of Valkey's Lua. */
JNIEXPORT void JNICALL Java_valkeylua_JniValkeyLua_lockGlobals(JNIEnv *env, jclass cls, jlong L) {
    luaLockGlobals((lua_State *)L);
}

JNIEXPORT void JNICALL Java_valkeylua_JniValkeyLua_enableReadonlyGlobals(JNIEnv *env, jclass cls, jlong L,
                                                                      jboolean enabled) {
    lua_enablereadonlytable((lua_State *)L, LUA_GLOBALSINDEX, enabled);
}

/* EVAL's error handler and reply (lua/valkey_reply.c). */
JNIEXPORT void JNICALL Java_valkeylua_JniValkeyLua_installErrorHandler(JNIEnv *env, jclass cls, jlong L) {
    luaInstallErrorHandler((lua_State *)L);
}

JNIEXPORT void JNICALL Java_valkeylua_JniValkeyLua_pushErrorHandler(JNIEnv *env, jclass cls, jlong L) {
    luaPushErrorHandler((lua_State *)L);
}

JNIEXPORT void JNICALL Java_valkeylua_JniValkeyLua_setScriptResp(JNIEnv *env, jclass cls, jlong L, jint resp) {
    luaSetScriptResp((lua_State *)L, resp);
}

/* Consumes the top of the stack; null if the conversion raised. */
JNIEXPORT jbyteArray JNICALL Java_valkeylua_JniValkeyLua_evalReply(JNIEnv *env, jclass cls, jlong L, jint kind) {
    respBuf out = {0};
    jbyteArray a = NULL;
    if (luaEvalReply((lua_State *)L, kind, &out) == 0) {
        a = (*env)->NewByteArray(env, out.len);
        (*env)->SetByteArrayRegion(env, a, 0, out.len, (const jbyte *)out.buf);
    }
    free(out.buf);
    return a;
}
