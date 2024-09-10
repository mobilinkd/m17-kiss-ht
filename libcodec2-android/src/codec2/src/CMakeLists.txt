#
# Generated sources
#

set(D ${CMAKE_CURRENT_SOURCE_DIR}/codebook)
set(CMAKE_REQUIRED_LIBRARIES m)
# lsp quantisers (2400/1400/1300 modes)
set(CODEBOOKS
    ${D}/lsp1.txt
    ${D}/lsp2.txt
    ${D}/lsp3.txt
    ${D}/lsp4.txt
    ${D}/lsp5.txt
    ${D}/lsp6.txt
    ${D}/lsp7.txt
    ${D}/lsp8.txt
    ${D}/lsp9.txt
    ${D}/lsp10.txt
)

# lspd quantisers (3200 mode)
set(CODEBOOKSD
    ${D}/dlsp1.txt
    ${D}/dlsp2.txt
    ${D}/dlsp3.txt
    ${D}/dlsp4.txt
    ${D}/dlsp5.txt
    ${D}/dlsp6.txt
    ${D}/dlsp7.txt
    ${D}/dlsp8.txt
    ${D}/dlsp9.txt
    ${D}/dlsp10.txt
)

# LSP VQ (1200 mode)
set(CODEBOOKSJVM
    ${D}/lspjvm1.txt
    ${D}/lspjvm2.txt
    ${D}/lspjvm3.txt
)

# Joint Wo-energy VQ (1200 mode)
set(CODEBOOKSGE ${D}/gecb.txt)

# 700C two stage VQ
set(CODEBOOKSNEWAMP1
    ${D}/train_120_1.txt
    ${D}/train_120_2.txt
)

set(CODEBOOKSNEWAMP1_ENERGY
    ${D}/newamp1_energy_q.txt
)

# 450 mode two stage VQ
set(CODEBOOKSNEWAMP2
    ${D}/codes_450.txt
)

set(CODEBOOKSNEWAMP2_ENERGY
    ${D}/newamp2_energy_q.txt
)

# when crosscompiling we need a native executable
if(CMAKE_CROSSCOMPILING)
    include(ExternalProject)
    ExternalProject_Add(codec2_native
       SOURCE_DIR ${CMAKE_SOURCE_DIR}
       BUILD_COMMAND $(MAKE) generate_codebook
       INSTALL_COMMAND ${CMAKE_COMMAND} -E copy src/generate_codebook ${CMAKE_CURRENT_BINARY_DIR}
    )
    add_executable(generate_codebook IMPORTED)
    set_target_properties(generate_codebook PROPERTIES
        IMPORTED_LOCATION ${CMAKE_CURRENT_BINARY_DIR}/generate_codebook)
    add_dependencies(generate_codebook codec2_native)
else(CMAKE_CROSSCOMPILING)
# Build code generator binaries. These do not get installed.
    # generate_codebook
    add_executable(generate_codebook generate_codebook.c)
    target_link_libraries(generate_codebook m ${CMAKE_REQUIRED_LIBRARIES})
    # Make native builds available for cross-compiling.
    export(TARGETS generate_codebook
        FILE ${CMAKE_BINARY_DIR}/ImportExecutables.cmake)
endif(CMAKE_CROSSCOMPILING)


# codebook.c
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/codebook.c
    COMMAND generate_codebook lsp_cb ${CODEBOOKS} > ${CMAKE_CURRENT_BINARY_DIR}/codebook.c
    DEPENDS generate_codebook ${CODEBOOKS}
)

# codebookd.c
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/codebookd.c
    COMMAND generate_codebook lsp_cbd ${CODEBOOKSD} > ${CMAKE_CURRENT_BINARY_DIR}/codebookd.c
    DEPENDS generate_codebook ${CODEBOOKSD}
)

# codebookjvm.c
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/codebookjvm.c
    COMMAND generate_codebook lsp_cbjvm ${CODEBOOKSJVM} > ${CMAKE_CURRENT_BINARY_DIR}/codebookjvm.c
    DEPENDS generate_codebook ${CODEBOOKSJVM}
)

# codebookge.c
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/codebookge.c
    COMMAND generate_codebook ge_cb ${CODEBOOKSGE} > ${CMAKE_CURRENT_BINARY_DIR}/codebookge.c
    DEPENDS generate_codebook ${CODEBOOKSGE}
)

# codebooknewamp1.c
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/codebooknewamp1.c
    COMMAND generate_codebook newamp1vq_cb ${CODEBOOKSNEWAMP1} > ${CMAKE_CURRENT_BINARY_DIR}/codebooknewamp1.c
    DEPENDS generate_codebook ${CODEBOOKSNEWAMP1}
)

# codebooknewamp1_energy.c
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/codebooknewamp1_energy.c
    COMMAND generate_codebook newamp1_energy_cb ${CODEBOOKSNEWAMP1_ENERGY} > ${CMAKE_CURRENT_BINARY_DIR}/codebooknewamp1_energy.c
    DEPENDS generate_codebook ${CODEBOOKSNEWAMP1_ENERGY}
)

# codebooknewamp2.c
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/codebooknewamp2.c
    COMMAND generate_codebook newamp2vq_cb ${CODEBOOKSNEWAMP2} > ${CMAKE_CURRENT_BINARY_DIR}/codebooknewamp2.c
    DEPENDS generate_codebook ${CODEBOOKSNEWAMP2}
)

# codebooknewamp2_energy.c
add_custom_command(
    OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/codebooknewamp2_energy.c
    COMMAND generate_codebook newamp2_energy_cb ${CODEBOOKSNEWAMP2_ENERGY} > ${CMAKE_CURRENT_BINARY_DIR}/codebooknewamp2_energy.c
    DEPENDS generate_codebook ${CODEBOOKSNEWAMP2_ENERGY}
)

#
# codec2 library sources
#

set(CODEC2_SRCS
    dump.c
    lpc.c
    nlp.c
    postfilter.c
    sine.c
    codec2.c
    codec2_fft.c
    cohpsk.c
    codec2_fifo.c
    fdmdv.c
    fm.c
    fsk.c
    fmfsk.c
    kiss_fft.c
    kiss_fftr.c
    linreg.c
    interp.c
    lsp.c
    mbest.c
    newamp1.c
    newamp2.c
    ofdm.c
    ofdm_mode.c
    phase.c
    quantise.c
    pack.c
    codebook.c
    codebookd.c
    codebookjvm.c
    codebookge.c
    codebooknewamp1.c
    codebooknewamp1_energy.c
    codebooknewamp2.c
    codebooknewamp2_energy.c
    golay23.c
    freedv_api.c
    freedv_1600.c
    freedv_700.c
    freedv_2020.c
    freedv_fsk.c
    freedv_vhf_framing.c
    freedv_data_channel.c
    varicode.c
    modem_stats.c
    ofdm.c
    mpdecode_core.c
    phi0.c
    gp_interleaver.c
    interldpc.c
    filter.c
    HRA_112_112.c
    HRAb_396_504.c
    H_256_768_22.c
    H_256_512_4.c
    HRAa_1536_512.c
    H_128_256_5.c
    ldpc_codes.c
    lpcnet_freq.c
)

set(CODEC2_PUBLIC_HEADERS
    codec2.h
    codec2_fdmdv.h
    codec2_cohpsk.h
    codec2_fm.h
    codec2_ofdm.h
    fsk.h
    codec2_fifo.h
    comp.h
    modem_stats.h
    freedv_api.h
    ${CODEC2_VERSION_PATH}/version.h
)

#
# Debug options
#
#add_definitions(-DDEBUG_ALLOC)

#
# Setup the codec2 library
#
add_library(codec2 ${CODEC2_SRCS})
if(UNIX)
    target_link_libraries(codec2 m)
endif(UNIX)
if(LPCNET AND lpcnetfreedv_FOUND)
    target_link_libraries(codec2 lpcnetfreedv)
endif()
include_directories(${CMAKE_CURRENT_SOURCE_DIR})
set_target_properties(codec2 PROPERTIES
    SOVERSION ${SOVERSION}
    PUBLIC_HEADER "${CODEC2_PUBLIC_HEADERS}"
)

# Required include directories for export
target_include_directories(codec2 INTERFACE
    $<INSTALL_INTERFACE:include/codec2>
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}>
    $<BUILD_INTERFACE:${CMAKE_BINARY_DIR}>
)

# Export config for import from full install
install(EXPORT codec2-config
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/codec2
)

# Export config for import from build tree.
export(TARGETS codec2
    FILE ${CMAKE_BINARY_DIR}/codec2.cmake
)

install(TARGETS codec2 EXPORT codec2-config
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
    PUBLIC_HEADER DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/codec2
)
