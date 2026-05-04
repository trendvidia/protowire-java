/**
 * SBE codec on protobuf-javalite — published Maven artifact under
 * {@code org.protowire:sbe-android}. Thin facade over
 * {@code :sbe-runtime}; downstream code uses
 * {@link org.protowire.sbe.runtime.SbeWireCodec},
 * {@link org.protowire.sbe.runtime.MessageTemplate},
 * {@link org.protowire.sbe.runtime.SbeFieldReader} and
 * {@link org.protowire.sbe.runtime.SbeFieldWriter} directly.
 *
 * <p>The {@code <Message>SbeMeta} / {@code <Message>SbeCodec} companions
 * a consumer relies on are emitted by {@code protoc-gen-pxf-java-meta}
 * in lite mode against their own {@code .proto} sources — see the
 * canonical {@code cmd/protoc-gen-pxf-java-meta/} for the plugin
 * source. This module's only job is to be the published artifact +
 * dependency-set the codegen-emitted classes link against.
 *
 * <p>Wire-equivalent with the full-runtime {@code :sbe} module on the
 * same {@code .proto} input. Divergence is a CI-blocking regression
 * via {@code scripts/cross_sbe_bench.sh}.
 */
package org.protowire.sbe.android;
