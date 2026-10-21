/* Globals protection from valkey src/modules/lua/script_lua.c and engine_lua.c, shared by lua.wasm and the JNI
 * natives. Valkey filters globals through an allow list while the libraries load; here the same lists are applied
 * once they have loaded, which leaves the same globals (luajava has already opened base by then). */
#include <string.h>

#include "lauxlib.h"
#include "lua.h"

/* Globals that are added by the Lua libraries */
static char *libraries_allow_list[] = {
    "string",
    "cjson",
    "bit",
    "cmsgpack",
    "math",
    "table",
    "struct",
    "os",
    NULL,
};

/* Lua API globals */
static char *server_api_allow_list[] = {
    "server",
    "redis",
    "__redis__err__handler",  /* Backwards compatible error handler */
    "__server__err__handler", /* error handler for eval, currently located on globals.
                                Should move to registry. */
    NULL,
};

/* Lua builtins */
static char *lua_builtins_allow_list[] = {
    "xpcall",
    "tostring",
    "setmetatable",
    "next",
    "assert",
    "tonumber",
    "rawequal",
    "collectgarbage",
    "getmetatable",
    "rawset",
    "pcall",
    "coroutine",
    "type",
    "_G",
    "select",
    "unpack",
    "gcinfo",
    "pairs",
    "rawget",
    "loadstring",
    "ipairs",
    "_VERSION",
    "load",
    "error",
    NULL,
};

/* lua_builtins_removed_after_initialization_allow_list ("debug") is left out: Valkey's error handler sets debug to
 * nil before the lock, and lua_builtins_deprecated (newproxy, setfenv, getfenv) is only allowed with
 * lua-enable-insecure-api, which defaults to off. */
static char **allow_lists[] = {
    libraries_allow_list,
    server_api_allow_list,
    lua_builtins_allow_list,
    NULL,
};

static int isAllowed(const char *name) {
    for (char ***allow_l = allow_lists; *allow_l; ++allow_l) {
        for (char **c = *allow_l; *c; ++c) {
            if (strcmp(*c, name) == 0) return 1;
        }
    }
    return 0;
}

static int luaProtectedTableError(lua_State *lua) {
    int argc = lua_gettop(lua);
    if (argc != 2) {
        luaL_error(lua, "Wrong number of arguments to luaProtectedTableError");
    }
    if (!lua_isstring(lua, -1) && !lua_isnumber(lua, -1)) {
        luaL_error(lua, "Second argument to luaProtectedTableError must be a string or number");
    }
    const char *variable_name = lua_tostring(lua, -1);
    luaL_error(lua, "Script attempted to access nonexistent global variable '%s'", variable_name);
    return 0;
}

/* Set a special metatable on the table on the top of the stack.
 * The metatable will raise an error if the user tries to fetch
 * an un-existing value. */
static void luaSetErrorMetatable(lua_State *lua) {
    lua_newtable(lua);                              /* push metatable */
    lua_pushcfunction(lua, luaProtectedTableError); /* push get error handler */
    lua_setfield(lua, -2, "__index");
    lua_setmetatable(lua, -2);
}

/* Set the readonly flag on the table located on the top of the stack
 * and recursively call this function on each table located on the original
 * table.  Also, recursively call this function on the metatables.*/
static void luaSetTableProtectionRecursively(lua_State *lua) {
    /* This protect us from a loop in case we already visited the table
     * For example, globals has '_G' key which is pointing back to globals. */
    if (lua_isreadonlytable(lua, -1)) {
        return;
    }

    /* protect the current table */
    lua_enablereadonlytable(lua, -1, 1);

    lua_checkstack(lua, 2);
    lua_pushnil(lua); /* Use nil to start iteration. */
    while (lua_next(lua, -2)) {
        /* Stack now: table, key, value */
        if (lua_istable(lua, -1)) {
            luaSetTableProtectionRecursively(lua);
        }
        lua_pop(lua, 1);
    }

    /* protect the metatable if exists */
    if (lua_getmetatable(lua, -1)) {
        luaSetTableProtectionRecursively(lua);
        lua_pop(lua, 1); /* pop the metatable */
    }
}

/* Set the readonly flag on the metatable of basic types (string, nil etc.) */
static void luaSetTableProtectionForBasicTypes(lua_State *lua) {
    static const int types[] = {
        LUA_TSTRING,
        LUA_TNUMBER,
        LUA_TBOOLEAN,
        LUA_TNIL,
        LUA_TFUNCTION,
        LUA_TTHREAD,
        LUA_TLIGHTUSERDATA};

    for (size_t i = 0; i < sizeof(types) / sizeof(types[0]); i++) {
        /* Push a dummy value of the type to get its metatable */
        switch (types[i]) {
        case LUA_TSTRING: lua_pushstring(lua, ""); break;
        case LUA_TNUMBER: lua_pushnumber(lua, 0); break;
        case LUA_TBOOLEAN: lua_pushboolean(lua, 0); break;
        case LUA_TNIL: lua_pushnil(lua); break;
        case LUA_TFUNCTION: lua_pushcfunction(lua, NULL); break;
        case LUA_TTHREAD: lua_newthread(lua); break;
        case LUA_TLIGHTUSERDATA: lua_pushlightuserdata(lua, (void *)lua); break;
        }
        if (lua_getmetatable(lua, -1)) {
            luaSetTableProtectionRecursively(lua);
            lua_pop(lua, 1); /* pop metatable */
        }
        lua_pop(lua, 1); /* pop dummy value */
    }
}

/* Drops every global not on the allow lists, then luaStateLockGlobalTable. */
void luaLockGlobals(lua_State *lua) {
    lua_pushvalue(lua, LUA_GLOBALSINDEX);
    lua_pushnil(lua);
    while (lua_next(lua, -2)) {
        lua_pop(lua, 1);
        if (lua_type(lua, -1) != LUA_TSTRING || !isAllowed(lua_tostring(lua, -1))) {
            lua_pushvalue(lua, -1);
            lua_pushnil(lua);
            lua_rawset(lua, -4); /* clearing an existing field during traversal is allowed */
        }
    }
    /* Lock the global table from any changes */
    luaSetErrorMetatable(lua);
    /* Recursively lock all tables that can be reached from the global table */
    luaSetTableProtectionRecursively(lua);
    lua_pop(lua, 1);
    /* Set metatables of basic types (string, number, nil etc.) readonly. */
    luaSetTableProtectionForBasicTypes(lua);
}
