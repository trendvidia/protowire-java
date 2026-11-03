// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
// CheckDecodeAndroid is the lite tier's `check-decode`, driven by protowire's
// scripts/cross_security_check.sh and scripts/cross_text_check.sh. See
// CheckDecode in :check-decode for the contract.
//
// The lite tier has no descriptor-driven decoder, so each format reaches what it
// does have:
//
//   pxf                 text to wire, through LiteWireWriter
//   pxf-pb              as pxf, and writes the wire bytes on stdout
//   pb, pb-render       wire to text, through LiteWireReader
//   pb-text             as pb-render, and writes the text on stdout
//   sbe                 SbeWireCodec on the generated template, called directly
//                       because the generated codec takes no limits
package org.protowire.check.android;

import org.protowire.pxf.DecodeLimits;
import org.protowire.pxf.Format;
import org.protowire.pxf.Parser;
import org.protowire.pxf.PxfMeta;
import org.protowire.pxf.android.LiteWireReader;
import org.protowire.pxf.android.LiteWireWriter;
import org.protowire.sbe.runtime.SbeWireCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public final class CheckDecodeAndroid {
    private CheckDecodeAndroid() {}

    private static final Map<String, PxfMeta> METAS = Map.ofEntries(
        Map.entry("adversarial.v1.Tree", adversarial.v1.TreePxfMeta.INSTANCE),
        Map.entry("adversarial.v1.StringHolder", adversarial.v1.StringHolderPxfMeta.INSTANCE),
        Map.entry("adversarial.v1.BytesHolder", adversarial.v1.BytesHolderPxfMeta.INSTANCE),
        Map.entry("adversarial.v1.BigIntHolder", adversarial.v1.BigIntHolderPxfMeta.INSTANCE),
        Map.entry("adversarial.v1.BigNumHolder", adversarial.v1.BigNumHolderPxfMeta.INSTANCE),
        Map.entry("adversarial.v1.ListHolder", adversarial.v1.ListHolderPxfMeta.INSTANCE),
        Map.entry("adversarial.v1.SbeWithGroup", adversarial.v1.SbeWithGroupPxfMeta.INSTANCE),
        Map.entry("adversarial.v1.SbeText", adversarial.v1.SbeTextPxfMeta.INSTANCE),
        Map.entry("text.v1.Scalars", text.v1.ScalarsPxfMeta.INSTANCE),
        Map.entry("text.v1.Shapes", text.v1.ShapesPxfMeta.INSTANCE),
        Map.entry("text.v1.WellKnown", text.v1.WellKnownPxfMeta.INSTANCE));

    public static void main(String[] args) {
        String format = null, schema = null, input = null;
        DecodeLimits limits = DecodeLimits.defaults();
        for (int i = 0; i + 1 < args.length; i += 2) {
            String v = args[i + 1];
            switch (args[i]) {
                case "--format" -> format = v;
                case "--schema" -> schema = v;
                case "--input"  -> input = v;
                case "--proto"  -> { /* see the class comment */ }
                case "--limit"  -> {
                    int eq = v.indexOf('=');
                    limits = limits.with(v.substring(0, eq), Integer.parseInt(v.substring(eq + 1)));
                }
                default -> {
                    System.err.println("check-decode-android: unknown arg " + args[i]);
                    System.exit(2);
                }
            }
        }
        PxfMeta meta = METAS.get(schema);
        if (format == null || input == null || meta == null) {
            System.err.println("check-decode-android: need --format, --input and a known --schema, got " + schema);
            System.exit(2);
        }
        try {
            run(format, schema, meta, input, limits);
            System.exit(0);
        } catch (UnsupportedFormat e) {
            System.err.println("check-decode-android: " + e.getMessage());
            System.exit(2);
        } catch (VirtualMachineError e) {
            // What HARDENING.md item 1 forbids, so not a rejection. See CheckDecode.
            System.err.println("crash: " + e.getClass().getSimpleName());
            System.exit(2);
        } catch (Throwable t) {
            System.err.println("reject: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            System.exit(1);
        }
    }

    private static void run(String format, String schema, PxfMeta meta, String input, DecodeLimits limits) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(input));
        switch (format) {
            case "pxf" -> LiteWireWriter.encode(Parser.parse(data, limits), meta, limits);
            case "pxf-pb" -> {
                System.out.write(LiteWireWriter.encode(Parser.parse(data, limits), meta, limits));
                System.out.flush();
            }
            case "pb", "pb-render" -> LiteWireReader.toAst(data, meta, limits);
            case "pb-text" -> {
                byte[] text = Format.formatDocument(LiteWireReader.toAst(data, meta, limits))
                    .getBytes(StandardCharsets.UTF_8);
                System.out.write(text);
                System.out.flush();
            }
            case "sbe" -> sbeDecode(data, schema, limits);
            default -> throw new UnsupportedFormat("the lite tier has no " + format + " decoder");
        }
    }

    private static void sbeDecode(byte[] data, String schema, DecodeLimits limits) {
        switch (schema) {
            case "adversarial.v1.SbeWithGroup" -> SbeWireCodec.unmarshal(data,
                adversarial.v1.SbeWithGroupSbeMeta.writer(adversarial.v1.SbeWithGroup.newBuilder()),
                adversarial.v1.SbeWithGroupSbeMeta.TEMPLATE, limits.maxMessageSize(), limits.maxRepeatedCount());
            case "adversarial.v1.SbeText" -> SbeWireCodec.unmarshal(data,
                adversarial.v1.SbeTextSbeMeta.writer(adversarial.v1.SbeText.newBuilder()),
                adversarial.v1.SbeTextSbeMeta.TEMPLATE, limits.maxMessageSize(), limits.maxRepeatedCount());
            default -> throw new UnsupportedFormat("the lite tier has no SBE template for " + schema);
        }
    }

    private static final class UnsupportedFormat extends RuntimeException {
        UnsupportedFormat(String message) { super(message); }
    }
}
