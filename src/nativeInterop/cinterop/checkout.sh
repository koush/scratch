git clone --branch v1.33.1 https://github.com/libuv/libuv.git
git clone --branch OpenSSL_1_1_1d https://github.com/openssl/openssl.git
pushd libuv
./autogen.sh && ./configure && make -j16
popd
pushd openssl
./config && make -j16
popd
