# Builds lua.wasm and the JNI natives and runs the suite on Linux, for whatever --platform the image is built for.
# x86_64 (the deployment architecture) from an arm64 Mac:
#   docker build --platform linux/amd64 -t valkey-lua . && docker run --rm --platform linux/amd64 valkey-lua

# lua.wasm is the same on every platform, so it's built natively rather than under emulation.
FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS wasm
RUN apt-get update && apt-get install -y --no-install-recommends make curl ca-certificates patch \
    && rm -rf /var/lib/apt/lists/*
COPY lua /src/lua
RUN make -C /src/lua

# Jammy, not Noble: Noble's tar 1.35 fails to mkdir under Docker Desktop's amd64 emulation on Apple silicon.
FROM eclipse-temurin:17-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends make g++ curl unzip perl \
    && rm -rf /var/lib/apt/lists/*
# Jammy's apt Maven (3.6) defaults to a compiler plugin that ignores maven.compiler.release.
COPY --from=maven:3-eclipse-temurin-17 /usr/share/maven /usr/share/maven
RUN ln -s /usr/share/maven/bin/mvn /usr/bin/mvn
WORKDIR /src
COPY . .
COPY --from=wasm /src/lua/lua.wasm lua/
RUN make -C native
# Both are built above, so skip the pom's make steps (lua's would rebuild from scratch under emulation).
CMD ["mvn", "-f", "java", "-Dexec.skip", "test"]
