// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pb;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Label;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DiscardUnknownFieldsParser;
import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A tag's wire type, against protobuf itself (#257, trendvidia/protowire#614):
 * protowire-go's {@code encoding/pb/wire_type_test.go}, ported.
 *
 * <p>A PB decoder does what protobuf does with a tag's wire type: it rejects
 * wire type 6 or 7 on any field, and handles a known field whose wire type
 * does not match its type as an unknown field, skipped by its wire type with
 * the field left as it was. {@link Pb#unmarshal} used to read a known field by
 * its Java type whatever the wire type said, so {@code 0a01} on a long read
 * the length byte as the value 1, and {@code 0e01} read wire type 6 as a
 * varint.
 *
 * <p>The oracle is protobuf-java's {@link DynamicMessage} over {@code conf.All}
 * below, the message protowire-go's test uses. Every field is sent one record
 * of every wire type, well formed and cut short, at the top level and inside
 * each message and map-entry field. Each input must get protobuf-java's
 * verdict, and on accept the same values: the oracle's message, read without
 * its unknown fields and re-encoded, must decode here to what the input
 * decoded to. The values are compared through {@link Pb#marshal}, which writes
 * every set field.
 */
class PbWireTypeTest {

    public static final class Inner {
        @ProtoField(1) String name;
        @ProtoField(2) long n;
        public Inner() {}
    }

    // The oneof's members are plain fields here: each input sets at most one.
    public static final class All {
        @ProtoField(1) String s;
        @ProtoField(2) long i64;
        @ProtoField(3) int i32;
        @ProtoField(4) long u64;
        @ProtoField(5) int u32;
        @ProtoField(value = 6, zigzag = true) long z64;
        @ProtoField(value = 7, zigzag = true) int z32;
        @ProtoField(8) boolean b;
        @ProtoField(9) double f64;
        @ProtoField(10) float f32;
        @ProtoField(11) byte[] buf;
        @ProtoField(12) Inner inner;
        @ProtoField(13) List<Inner> list;
        @ProtoField(14) List<Integer> nums;
        @ProtoField(value = 15, zigzag = true) List<Long> zigs;
        @ProtoField(16) List<Float> reals;
        @ProtoField(17) List<String> strs;
        @ProtoField(18) Map<String, Long> m;
        @ProtoField(19) Map<Integer, Inner> mm;
        @ProtoField(20) String a;
        @ProtoField(21) long c;
        @ProtoField(22) Inner d;
        @ProtoField(23) List<Boolean> flags;
        @ProtoField(24) List<Long> negs;
        @ProtoField(25) Map<Boolean, Boolean> bm;
        public All() {}
    }

    // -- conf.proto, as a descriptor ------------------------------------------
    //
    //   message Inner { string name = 1; int64 n = 2; }
    //   message All {
    //     string s = 1; int64 i64 = 2; int32 i32 = 3; uint64 u64 = 4; uint32 u32 = 5;
    //     sint64 z64 = 6; sint32 z32 = 7; bool b = 8; double f64 = 9; float f32 = 10;
    //     bytes buf = 11; Inner inner = 12; repeated Inner list = 13;
    //     repeated uint32 nums = 14; repeated sint64 zigs = 15; repeated float reals = 16;
    //     repeated string strs = 17; map<string, int64> m = 18; map<int32, Inner> mm = 19;
    //     oneof choice { string a = 20; int64 c = 21; Inner d = 22; }
    //     repeated bool flags = 23; repeated int64 negs = 24; map<bool, bool> bm = 25;
    //   }

    private static FieldDescriptorProto field(String name, int num, Type type, Label label, String typeName) {
        FieldDescriptorProto.Builder f = FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(num).setType(type).setLabel(label).setJsonName(name);
        if (typeName != null) f.setTypeName(typeName);
        return f.build();
    }

    private static FieldDescriptorProto opt(String name, int num, Type type) {
        return field(name, num, type, Label.LABEL_OPTIONAL, null);
    }

    private static FieldDescriptorProto rep(String name, int num, Type type) {
        return field(name, num, type, Label.LABEL_REPEATED, null);
    }

    private static DescriptorProto mapEntry(String name, Type key, Type value, String valueType) {
        return DescriptorProto.newBuilder().setName(name)
                .addField(opt("key", 1, key))
                .addField(field("value", 2, value, Label.LABEL_OPTIONAL, valueType))
                .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                .build();
    }

    private static Descriptor confAll() throws Exception {
        DescriptorProto inner = DescriptorProto.newBuilder().setName("Inner")
                .addField(opt("name", 1, Type.TYPE_STRING))
                .addField(opt("n", 2, Type.TYPE_INT64))
                .build();
        FieldDescriptorProto a = opt("a", 20, Type.TYPE_STRING).toBuilder().setOneofIndex(0).build();
        FieldDescriptorProto c = opt("c", 21, Type.TYPE_INT64).toBuilder().setOneofIndex(0).build();
        FieldDescriptorProto d = field("d", 22, Type.TYPE_MESSAGE, Label.LABEL_OPTIONAL, ".conf.Inner")
                .toBuilder().setOneofIndex(0).build();
        DescriptorProto all = DescriptorProto.newBuilder().setName("All")
                .addField(opt("s", 1, Type.TYPE_STRING))
                .addField(opt("i64", 2, Type.TYPE_INT64))
                .addField(opt("i32", 3, Type.TYPE_INT32))
                .addField(opt("u64", 4, Type.TYPE_UINT64))
                .addField(opt("u32", 5, Type.TYPE_UINT32))
                .addField(opt("z64", 6, Type.TYPE_SINT64))
                .addField(opt("z32", 7, Type.TYPE_SINT32))
                .addField(opt("b", 8, Type.TYPE_BOOL))
                .addField(opt("f64", 9, Type.TYPE_DOUBLE))
                .addField(opt("f32", 10, Type.TYPE_FLOAT))
                .addField(opt("buf", 11, Type.TYPE_BYTES))
                .addField(field("inner", 12, Type.TYPE_MESSAGE, Label.LABEL_OPTIONAL, ".conf.Inner"))
                .addField(field("list", 13, Type.TYPE_MESSAGE, Label.LABEL_REPEATED, ".conf.Inner"))
                .addField(rep("nums", 14, Type.TYPE_UINT32))
                .addField(rep("zigs", 15, Type.TYPE_SINT64))
                .addField(rep("reals", 16, Type.TYPE_FLOAT))
                .addField(rep("strs", 17, Type.TYPE_STRING))
                .addField(field("m", 18, Type.TYPE_MESSAGE, Label.LABEL_REPEATED, ".conf.All.MEntry"))
                .addField(field("mm", 19, Type.TYPE_MESSAGE, Label.LABEL_REPEATED, ".conf.All.MmEntry"))
                .addField(a).addField(c).addField(d)
                .addField(rep("flags", 23, Type.TYPE_BOOL))
                .addField(rep("negs", 24, Type.TYPE_INT64))
                .addField(field("bm", 25, Type.TYPE_MESSAGE, Label.LABEL_REPEATED, ".conf.All.BmEntry"))
                .addOneofDecl(OneofDescriptorProto.newBuilder().setName("choice"))
                .addNestedType(mapEntry("MEntry", Type.TYPE_STRING, Type.TYPE_INT64, null))
                .addNestedType(mapEntry("MmEntry", Type.TYPE_INT32, Type.TYPE_MESSAGE, ".conf.Inner"))
                .addNestedType(mapEntry("BmEntry", Type.TYPE_BOOL, Type.TYPE_BOOL, null))
                .build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder()
                .setName("conf.proto").setPackage("conf").setSyntax("proto3")
                .addMessageType(inner).addMessageType(all)
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).findMessageTypeByName("All");
    }

    // -- inputs ----------------------------------------------------------------

    private static final int VARINT = 0, I64 = 1, LEN = 2, SGROUP = 3, EGROUP = 4, I32 = 5;

    private static byte[] varint(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
        return out.toByteArray();
    }

    private static byte[] tag(int num, int type) {
        return varint(((long) num << 3) | type);
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
        return out;
    }

    private static byte[] unhex(String h) {
        return HexFormat.of().parseHex(h);
    }

    private static String hex(byte[] v) {
        return HexFormat.of().formatHex(v);
    }

    /** Single records for field num: every wire type, each with a well-formed value and a value cut short. */
    private static List<byte[]> wireRecords(int num) {
        return List.of(
                cat(tag(num, VARINT), b(0x01)),
                cat(tag(num, VARINT), b(0x80)),                   // cut short
                cat(tag(num, I64), b(1, 0, 0, 0, 0, 0, 0, 0)),
                cat(tag(num, I64), b(1, 0, 0)),                   // cut short
                cat(tag(num, LEN), b(0x00)),
                cat(tag(num, LEN), b(0x01, 0x01)),
                cat(tag(num, LEN), b(0x01)),                      // cut short
                cat(tag(num, SGROUP), tag(num, EGROUP)),
                tag(num, SGROUP),                                 // never ended
                tag(num, EGROUP),                                 // ends nothing
                cat(tag(num, I32), b(1, 0, 0, 0)),
                cat(tag(num, I32), b(1, 0)),                      // cut short
                cat(tag(num, 6), b(0x01)),
                cat(tag(num, 7), b(0x01)));
    }

    /**
     * Every record for every field of All, alone and carried inside each
     * message field (12, 13, 22: Inner's fields 1, 2 and an unknown 3) and map
     * field (18, 19, 25: key 1, value 2, unknown 3).
     */
    private static List<byte[]> wireTypeInputs() {
        List<byte[]> out = new ArrayList<>();
        for (int num = 1; num <= 25; num++) out.addAll(wireRecords(num));
        for (int outer : new int[] {12, 13, 18, 19, 22, 25}) {
            for (int inner = 1; inner <= 3; inner++) {
                for (byte[] rec : wireRecords(inner)) {
                    out.add(cat(tag(outer, LEN), varint(rec.length), rec));
                }
            }
        }
        // A matching record after a mismatched one: the mismatch leaves the
        // field as it was, and the match still sets it.
        out.add(unhex("0a0161" + "0801"));      // s: "a", then s as a varint
        out.add(unhex("1007" + "1501000000"));  // i64: 7, then i64 as fixed32
        out.add(unhex("720101" + "7001"));      // nums packed [1], then nums as a varint
        out.add(unhex("7001" + "7501000000"));  // nums [1], then nums as fixed32
        return out;
    }

    /** protobuf-java's message for the bytes, its unknown fields dropped, or null when it rejects them. */
    @SuppressWarnings("unchecked")
    private static DynamicMessage oracle(Descriptor desc, byte[] in) {
        try {
            return DiscardUnknownFieldsParser.wrap(DynamicMessage.getDefaultInstance(desc).getParserForType())
                    .parseFrom(in);
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    void wireTypeMatchesProtobuf() throws Exception {
        Descriptor desc = confAll();
        // The oracle reads without unknown fields, or "the same values" would
        // compare an unknown field read here with the oracle keeping it.
        assertEquals(0, oracle(desc, unhex("c80601")).toByteArray().length, "field 99 is dropped");

        List<byte[]> inputs = wireTypeInputs();
        int accepted = 0;
        int rejected = 0;
        List<String> failures = new ArrayList<>();
        for (byte[] in : inputs) {
            DynamicMessage want = oracle(desc, in);
            All got = new All();
            IOException err = null;
            try {
                Pb.unmarshal(in, got);
            } catch (IOException e) {
                err = e;
            }
            if (want == null) {
                rejected++;
                if (err == null) failures.add(hex(in) + ": protobuf-java rejects it, Pb accepts it");
                continue;
            }
            accepted++;
            if (err != null) {
                failures.add(hex(in) + ": protobuf-java accepts it, Pb says " + err.getMessage());
                continue;
            }
            byte[] known = want.toByteArray();
            All wantPb = Pb.unmarshal(known, All.class);
            String g = hex(Pb.marshal(got));
            String w = hex(Pb.marshal(wantPb));
            if (!g.equals(w)) {
                failures.add(hex(in) + ": values differ from protobuf-java's: got " + g + ", want " + w);
            }
        }
        // A positive marker that both verdicts were exercised, and not only one.
        System.out.println(inputs.size() + " inputs: protobuf-java accepts " + accepted + ", rejects " + rejected
                + "; " + failures.size() + " differ");
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + inputs.size() + " inputs differ from protobuf-java:\n"
                    + String.join("\n", failures));
        }
        assertEquals(606, inputs.size());
        assertTrue(accepted > 100, "accepted " + accepted);
        assertTrue(rejected > 100, "rejected " + rejected);
    }

    // -- the rows of #257's table, with protowire-go's text -----------------

    public static final class LongHolder {
        @ProtoField(1) long v;
        public LongHolder() {}
    }

    public static final class StringHolder {
        @ProtoField(1) String v;
        public StringHolder() {}
    }

    /**
     * Each row of #257's table, and the corpus's two unknown-field rows. The
     * error is protowire-go's word for word, as protowire-go main (a0a78c2)
     * gives it for the same struct; {@code null} is an accept.
     */
    @Test
    void wireTypeRows() throws IOException {
        String[][] rows = {
            // hex, holder, protowire-go's error or null
            {"0e01", "long", "corrupt field 1: proto: cannot parse reserved wire type"},
            {"0f0161", "string", "corrupt field 1: proto: cannot parse reserved wire type"},
            {"0e0161", "string", "corrupt field 1: proto: cannot parse reserved wire type"},
            {"0a0101", "long", null},
            {"0d01000000", "long", null},
            {"0801", "string", null},
            {"0a01", "long", "corrupt field 1: unexpected EOF"},
            {"0d01", "long", "corrupt field 1: unexpected EOF"},
            {"9f06", "long", "corrupt field 99: proto: cannot parse reserved wire type"},
            {"9e0601", "long", "corrupt field 99: proto: cannot parse reserved wire type"},
            // The skip's other refusals, on a known field and an unknown one.
            {"0c", "long", "corrupt field 1: proto: mismatching end group marker"},
            {"0980", "long", "corrupt field 1: unexpected EOF"},
            {"9806ffffffffffffffffff02", "long", "corrupt field 99: proto: variable length integer overflow"},
            {"9a0605", "long", "corrupt field 99: unexpected EOF"},
            {"0b0c", "long", null},
        };
        for (String[] row : rows) {
            byte[] in = unhex(row[0]);
            Object dest = row[1].equals("long") ? new LongHolder() : new StringHolder();
            if (row[2] == null) {
                Pb.unmarshal(in, dest);
                if (dest instanceof LongHolder h) assertEquals(0, h.v, row[0]);
                else assertNull(((StringHolder) dest).v, row[0]);
            } else {
                IOException e = assertThrows(IOException.class, () -> Pb.unmarshal(in, dest), row[0]);
                assertEquals(row[2], e.getMessage(), row[0]);
            }
        }
    }

    /**
     * A group is skipped by protobuf-java, as before, so its verdicts are
     * protobuf's and its bound is 100 (HARDENING.md MaxNestingDepth). The
     * cause is protobuf-java's text, not protobuf-go's.
     */
    @Test
    void groupSkipKeepsProtobufJavasBound() throws IOException {
        IOException e = assertThrows(IOException.class, () -> Pb.unmarshal(unhex("0b"), new LongHolder()));
        assertTrue(e.getMessage().startsWith("corrupt field 1: "), e.getMessage());
        Pb.unmarshal(nest(100), new LongHolder());
        e = assertThrows(IOException.class, () -> Pb.unmarshal(nest(101), new LongHolder()));
        assertTrue(e.getMessage().startsWith("corrupt field 99: Protocol message had too many levels of nesting."),
                e.getMessage());
    }

    /** Field 99 as {@code n} nested groups. */
    private static byte[] nest(int n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < n; i++) out.writeBytes(tag(99, SGROUP));
        for (int i = 0; i < n; i++) out.writeBytes(tag(99, EGROUP));
        return out.toByteArray();
    }

    // -- a skipped field is not touched --------------------------------------

    public static final class Untouched {
        @ProtoField(1) Long p;
        @ProtoField(2) List<String> l;
        @ProtoField(3) String kept;
        public Untouched() {}
    }

    /** A skipped field is not touched: a boxed field stays null and a list gets no element. */
    @Test
    void wireTypeMismatchLeavesFieldAlone() throws IOException {
        Untouched m = Pb.unmarshal(unhex("0d01000000" + "1001" + "1a0161"), Untouched.class);
        assertNull(m.p);
        assertNull(m.l);
        assertEquals("a", m.kept);
    }

    // -- map entries --------------------------------------------------------

    public static final class MapHolder {
        @ProtoField(1) Map<String, Long> m;
        public MapHolder() {}
    }

    /**
     * A map entry's key and value are read by the same rule: one whose wire
     * type does not match is skipped, and the entry keeps that field's zero.
     */
    @Test
    void wireTypeMapEntry() throws IOException {
        // {key "a", value sent as fixed32}: the value stays 0.
        assertEquals(Map.of("a", 0L), Pb.unmarshal(unhex("0a08" + "0a0161" + "1501000000"), MapHolder.class).m);
        // {key sent as a varint, value 7}: the key stays "".
        assertEquals(Map.of("", 7L), Pb.unmarshal(unhex("0a04" + "0801" + "1007"), MapHolder.class).m);
        IOException e = assertThrows(IOException.class, () -> Pb.unmarshal(unhex("0a020e01"), MapHolder.class));
        assertEquals("corrupt field 1 in map entry: proto: cannot parse reserved wire type", e.getMessage());
        e = assertThrows(IOException.class, () -> Pb.unmarshal(unhex("0a021501"), MapHolder.class));
        assertEquals("corrupt field 2 in map entry: unexpected EOF", e.getMessage());
    }

    // -- the big-number messages --------------------------------------------

    public static final class BigHolder {
        @ProtoField(1) BigInteger i;
        @ProtoField(2) BigDecimal r;
        @ProtoField(3) BigFloat f;
        public BigHolder() {}
    }

    /**
     * The big-number messages are read by the same rule: a field whose wire
     * type does not match is skipped, and wire types 6 and 7 are refused. The
     * cases are protowire-go's {@code TestUnmarshal_WireTypeBigNumbers}; its
     * errors carry a {@code field I: } this port does not write.
     */
    @Test
    void wireTypeBigNumbers() throws IOException {
        // BigInt{abs: 5, negative sent as wire type 2}: negative is skipped.
        assertEquals(BigInteger.valueOf(5), Pb.unmarshal(unhex("0a06" + "0a0105" + "120101"), BigHolder.class).i);
        // BigInt{abs sent as a varint}: abs is skipped, the value is 0.
        assertEquals(BigInteger.ZERO, Pb.unmarshal(unhex("0a02" + "0805"), BigHolder.class).i);
        // Decimal{unscaled: 15, scale sent as fixed32}: scale stays 0.
        assertEquals(new BigDecimal("15"), Pb.unmarshal(unhex("1208" + "0a010f" + "1501000000"), BigHolder.class).r);
        // BigFloat{mantissa: 1, exponent sent as bytes, prec 53}: exponent stays 0.
        BigFloat f = Pb.unmarshal(unhex("1a08" + "0a0101" + "120101" + "1835"), BigHolder.class).f;
        assertArrayEquals(b(1), f.mantissa());
        assertEquals(0, f.exponent());
        assertEquals(53, f.prec());

        String[][] rejects = {
            {"0a020e01", "corrupt field 1 in BigInt: proto: cannot parse reserved wire type"},
            {"12031f0101", "corrupt field 3 in Decimal: proto: cannot parse reserved wire type"},
            {"1a022601", "corrupt field 4 in BigFloat: proto: cannot parse reserved wire type"},
            {"0a020d01", "corrupt field 1 in BigInt: unexpected EOF"},
            {"1a021501", "corrupt field 2 in BigFloat: unexpected EOF"},
        };
        for (String[] r : rejects) {
            IOException e = assertThrows(IOException.class, () -> Pb.unmarshal(unhex(r[0]), BigHolder.class), r[0]);
            assertEquals(r[1], e.getMessage(), r[0]);
        }
    }
}
