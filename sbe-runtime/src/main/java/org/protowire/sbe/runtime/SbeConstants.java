package org.protowire.sbe.runtime;

import java.nio.ByteOrder;

/**
 * Wire-format constants for the SBE codec — extracted here so both the
 * wire codec in {@code :sbe-runtime} and the descriptor-driven adapter
 * in {@code :sbe} share the same numbers.
 *
 * <p>Numbers and byte order follow FIX SBE: 8-byte message header, 4-byte
 * group header, little-endian.
 */
public final class SbeConstants {
    private SbeConstants() {}

    public static final int HEADER_SIZE = 8;
    public static final int GROUP_HEADER_SIZE = 4;
    public static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;

    public static final String ENC_INT8 = "int8";
    public static final String ENC_INT16 = "int16";
    public static final String ENC_INT32 = "int32";
    public static final String ENC_INT64 = "int64";
    public static final String ENC_UINT8 = "uint8";
    public static final String ENC_UINT16 = "uint16";
    public static final String ENC_UINT32 = "uint32";
    public static final String ENC_UINT64 = "uint64";
    public static final String ENC_FLOAT = "float";
    public static final String ENC_DOUBLE = "double";
    public static final String ENC_CHAR = "char";
}
