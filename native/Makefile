VALKEY_SHA := 7db2df1f79f45e2fb0f0d4e16514f2010b8c15b9
# luajava 4.0.2's JNI glue, including the jnigen-generated Lua51Natives.cpp, as Infinispan publishes it
# (byte-identical to running jnigen on luajava v4.0.2). Its bundled Redis Lua is ignored; Valkey's is used.
ISPN_SOURCES := https://repo1.maven.org/maven2/org/infinispan/infinispan-lua51-platform/1.0.0/infinispan-lua51-platform-1.0.0-sources.jar

DEPS := .deps
LJ := $(DEPS)/luajava
VK := $(DEPS)/valkey
LUA_SRC := $(VK)/deps/lua/src

# Name and resource path gdx-jnigen-loader looks for on the classpath.
OS := $(shell uname -s)
ARCH := $(shell uname -m)
ifeq ($(OS),Darwin)
JNI_PLATFORM := mac
LIB := liblua51$(if $(filter arm64,$(ARCH)),arm64,64).dylib
else
JNI_PLATFORM := linux
LIB := liblua51$(if $(filter aarch64 arm64,$(ARCH)),arm64,64).so
endif

# Valkey's LUA_CFLAGS (deps/Makefile). Lua and the JNI glue are one C++ unit, as in luajava and Infinispan, so Lua
# errors are C++ exceptions; -fexceptions lets them unwind through the C libraries.
FLAGS := -O2 -fPIC -DLUA_ANSI -DENABLE_CJSON_GLOBAL -DLUA_USE_MKSTEMP -fexceptions \
	-I$(LJ)/lua51/jni/jni-headers -I$(LJ)/lua51/jni/jni-headers/$(JNI_PLATFORM) -I$(LJ)/lua51/jni \
	-I$(LJ)/jni/luajava -I$(LJ)/lua51/jni/mod -I$(LUA_SRC) -I$(VK)/deps/lua/etc -I$(VK)/deps/fpconv -I.

C_SRCS := $(addprefix $(LUA_SRC)/,lua_cjson.c lua_cmsgpack.c lua_struct.c lua_bit.c strbuf.c fpconv.c) \
	$(VK)/deps/fpconv/fpconv_dtoa.c valkey_jni.c

build/$(LIB): $(LJ) $(VK) all.cc valkey_jni.c
	mkdir -p build/obj
	$(CXX) $(FLAGS) $(CXXFLAGS) -c $(LJ)/lua51/jni/party_iroiro_luajava_lua51_Lua51Natives.cpp -o build/obj/natives.o
	for f in $(C_SRCS); do $(CC) $(FLAGS) $(CFLAGS) -c $$f -o build/obj/$$(basename $$f .c).o || exit 1; done
	$(CXX) -shared -o $@ build/obj/*.o

$(LJ):
	mkdir -p $@
	curl -sSfL -o $(DEPS)/sources.jar $(ISPN_SOURCES)
	cd $@ && unzip -q ../sources.jar 'jni/luajava/*' 'lua51/jni/*' -x 'lua51/jni/lua51/*'
	# Its luacustomamalg.h includes "all.cc"; ours (in this directory) amalgamates Valkey's Lua instead.
	rm $(DEPS)/sources.jar

$(VK):
	mkdir -p $@
	curl -sSfL https://github.com/valkey-io/valkey/archive/$(VALKEY_SHA).tar.gz \
		| tar xz -C $@ --strip-components=1 valkey-$(VALKEY_SHA)/deps/lua/src valkey-$(VALKEY_SHA)/deps/lua/etc/lua.hpp valkey-$(VALKEY_SHA)/deps/fpconv valkey-$(VALKEY_SHA)/src/solarisfixes.h

clean:
	rm -rf build

.PHONY: clean
