// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads the {@code required} and {@code default} annotations, and {@code (pxf.key)}.
 *
 * <p>PXF spells {@code required} and {@code default} two ways. The bracket
 * form lowers to the extensions 1314 and 1315. The annotation form,
 * {@code @required} and {@code @default(v)}, lowers to one {@code
 * AnnotationList} riding extension 1327 on the field's options (RFC-001
 * §8.1). A compiler emits the one the author wrote and synthesizes neither
 * from the other, so reading only 1314 and 1315 binds a migrated schema
 * without error and without enforcement (#111). This reads both, and {@code
 * @default}'s argument reduces to the same literal string the bracket form
 * carries, so everything downstream is shared. The carrier's shape is frozen
 * by STABILITY.md promise 3, and this walks its bytes rather than binding
 * {@code protowire/schema/v1/descriptor.proto}. Mirrors protowire-go's
 * {@code carrier.go}.
 *
 * <p>The field options bytes are accessed via {@code FieldDescriptor#getOptions().toByteString()}.
 * We scan that buffer directly so the same code works whether the descriptor was produced by
 * protoc with linked extensions, or from a raw FileDescriptorSet.
 */
final class Annotations {
    private Annotations() {}

    static final int EXT_REQUIRED = 1314;
    static final int EXT_DEFAULT  = 1315;
    static final int EXT_KEY      = 1316;

    /** The annotation carrier's extension number, shared by all eight kind-specific carriers. */
    static final int EXT_ANNOTATIONS = 1327;

    private static final String ANNOTATION_REQUIRED = "protowire.schema.v1.required";
    private static final String ANNOTATION_DEFAULT = "protowire.schema.v1.default";
    private static final String DEFAULT_PARAM = "value";

    /** Field numbers inside the carrier, from protowire/proto/schema/v1/descriptor.proto. */
    private static final int LIST_ENTRIES = 1;
    private static final int ANNOTATION_NAME = 1;
    private static final int ANNOTATION_ARGS = 2;
    private static final int ARG_NAME = 1;
    private static final int ARG_STRING = 10;
    private static final int ARG_INT = 11;
    private static final int ARG_DOUBLE = 12;
    private static final int ARG_BOOL = 13;
    private static final int ARG_BYTES = 14;
    private static final int ARG_LITERAL = 15;
    private static final int ARG_BIG_INT = 16;
    private static final int ARG_DECIMAL = 17;
    private static final int ARG_BIG_FLOAT = 18;
    private static final int LITERAL_ENUM_VALUE = 1;
    private static final int ENUM_VALUE_NAME = 2;

    /**
     * What the carrier says about one field.
     *
     * @param def        {@code @default}'s argument as a literal, or null
     * @param defProblem why an {@code @default} the field does carry reduces to
     *                   no literal, or null
     */
    private record Carrier(boolean required, String def, String defProblem) {
        static final Carrier NONE = new Carrier(false, null, null);
    }

    /** Whether the field is required, in either spelling. */
    static boolean isRequired(FieldDescriptor fd) {
        return readBool(fd, EXT_REQUIRED) || carrier(fd).required();
    }

    /**
     * The field's default, in either spelling, or null. The literal is
     * returned wherever the author placed it; {@link SchemaValidator} judges
     * the placement.
     */
    static String getDefault(FieldDescriptor fd) {
        String bracket = readString(fd, EXT_DEFAULT);
        return bracket != null ? bracket : carrier(fd).def();
    }

    /**
     * Why the field's default cannot be applied, or null: an {@code @default}
     * that reduces to no single literal, or two spellings that disagree. The
     * two surfaces are not reconciled (RFC-001 §8.5), so a disagreement is a
     * bind-time rejection, not a precedence. It is never silently dropped.
     */
    static String defaultProblem(FieldDescriptor fd) {
        Carrier c = carrier(fd);
        if (c.defProblem() != null) return c.defProblem();
        String bracket = readString(fd, EXT_DEFAULT);
        if (bracket != null && c.def() != null && !bracket.equals(c.def())) {
            return "(pxf.default) = \"" + bracket + "\" and @default = \"" + c.def()
                    + "\" carry different values, and the two surfaces must not be reconciled (RFC-001 §8.5)";
        }
        return null;
    }

    /** The {@code (pxf.key)} field name, or null when the option is not set. */
    static String getKey(FieldDescriptor fd) {
        return readString(fd, EXT_KEY);
    }

    /**
     * The key field of a keyed repeated field: the singular string field
     * of {@code fd}'s element message that its {@code (pxf.key)} names
     * (draft -01 §3.13). Null when {@code fd} carries no {@code (pxf.key)}
     * or its placement is invalid — {@code fd} is not a repeated
     * message-typed field, the named field does not exist, or it is not a
     * singular string field; {@link SchemaValidator} reports those as
     * violations.
     */
    static FieldDescriptor keyField(FieldDescriptor fd) {
        if (fd == null || !fd.isRepeated() || fd.isMapField() || fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) return null;
        String name = getKey(fd);
        if (name == null) return null;
        FieldDescriptor kf = fd.getMessageType().findFieldByName(name);
        if (kf == null || kf.isRepeated() || kf.getType() != FieldDescriptor.Type.STRING) return null;
        return kf;
    }

    // -- the 1327 carrier ----------------------------------------------------

    /**
     * The carrier on {@code fd}. Occurrences concatenate, because a
     * message-typed field split across several merges under protobuf's rules
     * and AnnotationList holds nothing but a repeated field.
     */
    private static Carrier carrier(FieldDescriptor fd) {
        ByteString opts = fd.getOptions().toByteString();
        if (opts.isEmpty()) return Carrier.NONE;
        ByteString list = ByteString.EMPTY;
        try {
            CodedInputStream in = opts.newCodedInput();
            while (true) {
                int tag = in.readTag();
                if (tag == 0) break;
                if (WireFormat.getTagFieldNumber(tag) == EXT_ANNOTATIONS
                        && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    list = list.concat(in.readBytes());
                } else {
                    in.skipField(tag);
                }
            }
        } catch (IOException e) {
            // Keep what was read: reporting a schema as unannotated because a
            // later byte was garbled is the silent non-enforcement this path
            // exists to end.
        }
        return list.isEmpty() ? Carrier.NONE : parseList(list, fd);
    }

    private static Carrier parseList(ByteString list, FieldDescriptor fd) {
        boolean required = false;
        String def = null;
        String problem = null;
        for (Wire e : fields(list)) {
            if (e.num != LIST_ENTRIES || e.bytes == null) continue;
            String name = null;
            List<ByteString> args = new ArrayList<>();
            for (Wire f : fields(e.bytes)) {
                if (f.num == ANNOTATION_NAME && f.bytes != null) name = f.bytes.toStringUtf8();
                else if (f.num == ANNOTATION_ARGS && f.bytes != null) args.add(f.bytes);
            }
            if (ANNOTATION_REQUIRED.equals(name)) {
                required = true;
            } else if (ANNOTATION_DEFAULT.equals(name) && def == null && problem == null) {
                // The first @default wins; a second is the compiler's to reject.
                ByteString arg = defaultArg(args);
                if (arg == null) {
                    problem = "@default carries no value argument";
                } else {
                    try {
                        def = argLiteral(arg, fd);
                    } catch (IllegalArgumentException ex) {
                        problem = ex.getMessage();
                    }
                }
            }
        }
        return new Carrier(required, def, problem);
    }

    /** The first positional argument, or the first named {@code value}. */
    private static ByteString defaultArg(List<ByteString> args) {
        for (ByteString a : args) {
            String name = "";
            for (Wire f : fields(a)) {
                if (f.num == ARG_NAME && f.bytes != null) { name = f.bytes.toStringUtf8(); break; }
            }
            if (name.isEmpty() || DEFAULT_PARAM.equals(name)) return a;
        }
        return null;
    }

    /**
     * Reduces one AnnotationArg to the literal the bracket form would have
     * carried. {@code fd} decides one case the bytes cannot: AnnotationArg has
     * no unsigned member, so a large unsigned literal arrives reinterpreted
     * through int64.
     */
    private static String argLiteral(ByteString arg, FieldDescriptor fd) {
        for (Wire f : fields(arg)) {
            switch (f.num) {
                case ARG_STRING -> { if (f.bytes != null) return f.bytes.toStringUtf8(); }
                case ARG_INT -> {
                    if (f.isVarint) return unsignedTarget(fd) ? Long.toUnsignedString(f.varint) : Long.toString(f.varint);
                }
                case ARG_DOUBLE -> {
                    if (f.isFixed64) {
                        double d = Double.longBitsToDouble(f.varint);
                        return exactDecimalTarget(fd) ? new BigDecimal(Double.toString(d)).toPlainString() : Double.toString(d);
                    }
                }
                case ARG_BOOL -> { if (f.isVarint) return Boolean.toString(f.varint != 0); }
                case ARG_BYTES -> { if (f.bytes != null) return Base64.getEncoder().encodeToString(f.bytes.toByteArray()); }
                case ARG_BIG_INT -> { if (f.bytes != null) return bigIntLiteral(f.bytes); }
                case ARG_DECIMAL -> { if (f.bytes != null) return decimalLiteral(f.bytes); }
                case ARG_BIG_FLOAT -> { if (f.bytes != null) return bigFloatLiteral(f.bytes); }
                case ARG_LITERAL -> { if (f.bytes != null) return literalValue(f.bytes); }
                default -> { }
            }
        }
        throw new IllegalArgumentException("@default's value is empty");
    }

    private static String bigIntLiteral(ByteString b) {
        BigInteger abs = BigInteger.ZERO;
        boolean negative = false;
        for (Wire f : fields(b)) {
            if (f.num == 1 && f.bytes != null) abs = new BigInteger(1, f.bytes.toByteArray());
            else if (f.num == 2) negative = f.varint != 0;
        }
        return (negative ? abs.negate() : abs).toString();
    }

    private static String decimalLiteral(ByteString b) {
        BigInteger unscaled = BigInteger.ZERO;
        int scale = 0;
        boolean negative = false;
        for (Wire f : fields(b)) {
            if (f.num == 1 && f.bytes != null) unscaled = new BigInteger(1, f.bytes.toByteArray());
            else if (f.num == 2 && f.isVarint) scale = (int) f.varint; // a plain varint, not zigzag
            else if (f.num == 3) negative = f.varint != 0;
        }
        // The bound comes before the value: both arms materialise 10^|scale|.
        if (scale > Limits.MAX_NUMERIC_LITERAL_DIGITS || scale < -Limits.MAX_NUMERIC_LITERAL_DIGITS) {
            throw new IllegalArgumentException("@default's decimal_value has scale " + scale
                    + ", and MaxNumericLiteralDigits=" + Limits.MAX_NUMERIC_LITERAL_DIGITS + " bounds its magnitude");
        }
        BigDecimal v = new BigDecimal(negative ? unscaled.negate() : unscaled, scale);
        return v.toPlainString();
    }

    private static String bigFloatLiteral(ByteString b) {
        BigInteger mantissa = BigInteger.ZERO;
        int exponent = 0;
        int prec = 0;
        boolean negative = false;
        for (Wire f : fields(b)) {
            if (f.num == 1 && f.bytes != null) mantissa = new BigInteger(1, f.bytes.toByteArray());
            else if (f.num == 2 && f.isVarint) exponent = (int) f.varint; // a plain varint
            else if (f.num == 3) prec = (int) f.varint;
            else if (f.num == 4) negative = f.varint != 0;
        }
        if (prec == 0) throw new IllegalArgumentException("@default's big_float_value declares no precision");
        return BigFloats.format(mantissa, exponent, prec, negative, Limits.MAX_NUMERIC_LITERAL_DIGITS, Limits.MAX_BIG_FLOAT_EXPONENT);
    }

    /** A Literal has a value of its own only as a resolved enum reference. */
    private static String literalValue(ByteString b) {
        for (Wire f : fields(b)) {
            if (f.num == LITERAL_ENUM_VALUE && f.bytes != null) {
                for (Wire e : fields(f.bytes)) {
                    if (e.num == ENUM_VALUE_NAME && e.bytes != null) return e.bytes.toStringUtf8();
                }
                throw new IllegalArgumentException("@default names an enum value the lowering pass left unresolved");
            }
        }
        throw new IllegalArgumentException("@default carries a message or list literal, and a default is exactly one literal");
    }

    /** A pxf.BigInt or pxf.Decimal default is positional: such a literal has no exponent. */
    private static boolean exactDecimalTarget(FieldDescriptor fd) {
        return fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE
                && (WellKnown.isBigInt(fd.getMessageType()) || WellKnown.isDecimal(fd.getMessageType()));
    }

    private static boolean unsignedTarget(FieldDescriptor fd) {
        return switch (fd.getType()) {
            case UINT32, FIXED32, UINT64, FIXED64 -> true;
            case MESSAGE -> {
                String n = fd.getMessageType().getFullName();
                yield "google.protobuf.UInt32Value".equals(n) || "google.protobuf.UInt64Value".equals(n);
            }
            default -> false;
        };
    }

    /** One decoded field of a nested carrier message. */
    private static final class Wire {
        int num;
        boolean isVarint;
        boolean isFixed64;
        long varint;
        ByteString bytes;
    }

    /** The well-formed fields of {@code b}, stopping at the first malformed one and keeping the rest. */
    private static List<Wire> fields(ByteString b) {
        List<Wire> out = new ArrayList<>();
        try {
            CodedInputStream in = b.newCodedInput();
            while (true) {
                int tag = in.readTag();
                if (tag == 0) return out;
                Wire w = new Wire();
                w.num = WireFormat.getTagFieldNumber(tag);
                switch (WireFormat.getTagWireType(tag)) {
                    case WireFormat.WIRETYPE_VARINT -> { w.isVarint = true; w.varint = in.readRawVarint64(); }
                    case WireFormat.WIRETYPE_FIXED64 -> { w.isFixed64 = true; w.varint = in.readRawLittleEndian64(); }
                    case WireFormat.WIRETYPE_FIXED32 -> w.varint = Integer.toUnsignedLong(in.readRawLittleEndian32());
                    case WireFormat.WIRETYPE_LENGTH_DELIMITED -> w.bytes = in.readBytes();
                    default -> { return out; }
                }
                out.add(w);
            }
        } catch (IOException e) {
            return out;
        }
    }

    private static boolean readBool(FieldDescriptor fd, int target) {
        ByteString opts = fd.getOptions().toByteString();
        if (opts.isEmpty()) return false;
        try {
            CodedInputStream in = opts.newCodedInput();
            while (true) {
                int tag = in.readTag();
                if (tag == 0) return false;
                int num = WireFormat.getTagFieldNumber(tag);
                int wire = WireFormat.getTagWireType(tag);
                if (num == target && wire == WireFormat.WIRETYPE_VARINT) {
                    return in.readBool();
                }
                in.skipField(tag);
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static String readString(FieldDescriptor fd, int target) {
        ByteString opts = fd.getOptions().toByteString();
        if (opts.isEmpty()) return null;
        try {
            CodedInputStream in = opts.newCodedInput();
            while (true) {
                int tag = in.readTag();
                if (tag == 0) return null;
                int num = WireFormat.getTagFieldNumber(tag);
                int wire = WireFormat.getTagWireType(tag);
                if (num == target && wire == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    return in.readStringRequireUtf8();
                }
                in.skipField(tag);
            }
        } catch (IOException e) {
            return null;
        }
    }
}
