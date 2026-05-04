/**
 * PB (compact protobuf-binary) codec on protobuf-javalite — published
 * Maven artifact under {@code org.protowire:pb-android}. The wire codec
 * is protobuf-javalite's own {@code MessageLite.parseFrom} /
 * {@code MessageLite.toByteArray}; no protowire-specific encoder/decoder
 * lives here. This module pins the javalite version downstream Android
 * consumers get, matching what {@code :pxf-android} /
 * {@code :sbe-android} / {@code :envelope-android} require.
 */
package org.protowire.pb.android;
