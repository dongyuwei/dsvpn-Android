cmake_minimum_required(VERSION 3.22)
project(dsvpn C)

set(DSVPN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_library(dsvpn SHARED
  dsvpn_jni.c
  ${DSVPN_ROOT}/src/vpn.c
  ${DSVPN_ROOT}/src/charm.c
  ${DSVPN_ROOT}/src/os.c
)

target_include_directories(dsvpn PRIVATE ${DSVPN_ROOT}/include)
target_compile_definitions(dsvpn PRIVATE
  _GNU_SOURCE
  # Routing, addresses and firewall rules are managed by VpnService.
  NO_DEFAULT_ROUTES
)
target_compile_options(dsvpn PRIVATE -Wall -W -Wshadow)

# charm.c uses the NEON permutation on 32-bit ARM only when NEON is enabled.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
  target_compile_options(dsvpn PRIVATE -mfpu=neon)
endif()

find_library(log-lib log)
target_link_libraries(dsvpn ${log-lib})
