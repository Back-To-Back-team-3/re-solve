# syntax=docker/dockerfile:1
FROM judge0/judge0:1.13.1 AS judge0-base

FROM judge0-base AS jdk
USER root
ADD --checksum=sha256:a0b1b9dd809d51a438f5fa08918f9aca7b2135721097f0858cf29f77a35d4289 https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.6%2B10/OpenJDK17U-jdk_x64_linux_hotspot_17.0.6_10.tar.gz /tmp/temurin17.tar.gz
RUN mkdir -p /usr/local/resolve-jdk17 \
    && tar -xzf /tmp/temurin17.tar.gz --strip-components=1 -C /usr/local/resolve-jdk17

# Judge0 기반 이미지와 같은 시스템 라이브러리를 사용해 실행 환경을 빌드한다.
FROM judge0-base AS python
USER root
ARG BUILD_JOBS=2
ADD --checksum=sha256:2299dae542d395ce3883aca00d3c910307cd68e0b2f7336098c8e7b7eee9f3e9 https://www.python.org/ftp/python/3.14.0/Python-3.14.0.tar.xz /tmp/python.tar.xz
RUN mkdir /tmp/python-source \
    && tar -xJf /tmp/python.tar.xz --strip-components=1 -C /tmp/python-source \
    && cd /tmp/python-source \
    && CC=/usr/local/gcc-9.2.0/bin/gcc ./configure --prefix=/usr/local/resolve-python --without-ensurepip \
    && make -j"$BUILD_JOBS" \
    && make install

FROM judge0-base AS gcc
USER root
ARG BUILD_JOBS=2
ADD https://gcc.gnu.org/pub/gcc/releases/gcc-14.1.0/gcc-14.1.0.tar.xz /tmp/gcc-14.1.0.tar.xz
RUN cd /tmp \
    && printf '%s  %s\n' \
        'e9e224f2b26646fcf038d28dfa08b94c623bc57941f99894a321d01c600f7c68aff6b8837fd25e73e540de1f8de5606e98694a62cdcdfb525ce768b3ef6879ea' \
        gcc-14.1.0.tar.xz | sha512sum -c - \
    && tar -xJf gcc-14.1.0.tar.xz \
    && cd gcc-14.1.0 \
    && sed -i 's|http://gcc.gnu.org/|https://gcc.gnu.org/|' contrib/download_prerequisites \
    && ./contrib/download_prerequisites

# 같은 소스와 옵션의 중간 컴파일 결과는 실패 후 재시도에도 재사용한다.
RUN --mount=type=cache,id=resolve-gcc-14.1.0-amd64-o2-nosan-v1,target=/tmp/gcc-build,sharing=locked \
    cd /tmp/gcc-build \
    && if [ ! -f config.status ]; then \
        /tmp/gcc-14.1.0/configure --prefix=/usr/local/resolve-gcc \
            --enable-languages=c,c++ --disable-bootstrap --disable-multilib --disable-nls \
            --disable-libsanitizer CFLAGS="-O2" CXXFLAGS="-O2"; \
    fi \
    && make -j"$BUILD_JOBS" \
    && make install-strip

FROM judge0-base
USER root
COPY --from=jdk /usr/local/resolve-jdk17 /usr/local/resolve-jdk17
COPY --from=python /usr/local/resolve-python /usr/local/resolve-python
COPY --from=gcc /usr/local/resolve-gcc /usr/local/resolve-gcc
COPY runtimes.rb /tmp/resolve-runtimes.rb
RUN printf '\n' >> /api/db/languages/active.rb \
    && cat /tmp/resolve-runtimes.rb >> /api/db/languages/active.rb \
    && rm /tmp/resolve-runtimes.rb
USER judge0
RUN ruby -c /api/db/languages/active.rb \
    && /usr/local/resolve-jdk17/bin/javac -version \
    && /usr/local/resolve-python/bin/python3.14 --version \
    && /usr/local/resolve-gcc/bin/g++ --version

# 버전 출력뿐 아니라 설치된 런타임의 컴파일·실행과 필수 모듈을 확인한다.
RUN <<'SH'
set -eu
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
/usr/local/resolve-python/bin/python3.14 -c 'import json, math, struct, decimal, base64; assert json.loads("[1,2]") == [1,2]'
printf '%s\n' '#include <iostream>' '#include <vector>' 'int main() { std::vector<int> values{1, 2}; std::cout << values[0] + values[1]; }' > main.cpp
/usr/local/resolve-gcc/bin/g++ -std=c++17 main.cpp -o main
test "$(LD_LIBRARY_PATH=/usr/local/resolve-gcc/lib64 ./main)" = 3
printf '%s\n' 'public class Main { public static void main(String[] args) { System.out.print(java.util.List.of(1, 2).stream().mapToInt(Integer::intValue).sum()); } }' > Main.java
/usr/local/resolve-jdk17/bin/javac Main.java
test "$(/usr/local/resolve-jdk17/bin/java Main)" = 3
SH
