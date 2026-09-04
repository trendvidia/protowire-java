// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
// Cross-port wire-compatibility dumper, driven by protowire's
// scripts/cross_envelope_check.sh. Every port carries the same program and
// the script compares their output byte for byte. Mirrors
// protowire-go/scripts/dump_envelope.
//
//   dump-envelope                        canonical Envelope → pb hex
//   dump-envelope --pb  FDS MESSAGE DOC  PXF DOC decoded against MESSAGE in FDS → pb hex
//   dump-envelope --sbe FDS MESSAGE DOC  same → SBE hex
//
// The fixture modes apply the PXF annotations the descriptor carries, which
// is how the gate proves this port reads (pxf.required) = 1314,
// (pxf.default) = 1315 and the SBE numbers 1319–1323 from a descriptor it did
// not compile itself (STABILITY.md promise 3, protowire#244). A port looking
// for the wrong number decodes to different bytes, or accepts a document it
// must reject.
//
// Exit 0 with hex on stdout; 1 with "reject: <reason>" on stderr when the
// schema rejects DOC; 2 for anything that is the harness's fault.
package org.protowire.dump;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.protowire.envelope.v1.AppError;
import org.protowire.envelope.v1.Envelope;
import org.protowire.envelope.v1.FieldError;
import org.protowire.pxf.Pxf;
import org.protowire.pxf.PxfException;
import org.protowire.sbe.Codec;

public final class DumpEnvelope {
    private DumpEnvelope() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            dumpEnvelope();
            return;
        }
        if (args.length != 4 || !(args[0].equals("--pb") || args[0].equals("--sbe"))) {
            fatal(2, "usage: dump-envelope [--pb|--sbe FDS MESSAGE DOC]");
        }
        dumpFixture(args[0], args[1], args[2], args[3]);
    }

    private static void dumpEnvelope() {
        FieldError fe = FieldError.newBuilder()
            .setField("amount").setCode("MIN_VALUE")
            .setMessage("below minimum").addArgs("10.00").build();
        AppError ae = AppError.newBuilder()
            .setCode("INSUFFICIENT_FUNDS").setMessage("balance too low")
            .addArgs("$3.50").addArgs("$10.00")
            .addDetails(fe)
            .putMetadata("request_id", "req-123")
            .build();
        Envelope env = Envelope.newBuilder()
            .setStatus(402)
            .setData(ByteString.copyFrom(new byte[] {(byte)0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF}))
            .setError(ae)
            .build();
        System.out.println(hex(env.toByteArray()));
    }

    private static void dumpFixture(String mode, String fdsPath, String message, String docPath) {
        Descriptor desc = null;
        byte[] doc = null;
        try {
            FileDescriptorSet fds = FileDescriptorSet.parseFrom(Files.readAllBytes(Paths.get(fdsPath)));
            Map<String, FileDescriptor> built = new HashMap<>();
            for (FileDescriptorProto fp : fds.getFileList()) {
                FileDescriptor[] deps = new FileDescriptor[fp.getDependencyCount()];
                for (int i = 0; i < deps.length; i++) {
                    deps[i] = built.get(fp.getDependency(i));
                    if (deps[i] == null) {
                        throw new IllegalStateException(
                            "missing dependency " + fp.getDependency(i) + " for " + fp.getName());
                    }
                }
                FileDescriptor fd = FileDescriptor.buildFrom(fp, deps);
                built.put(fp.getName(), fd);
                for (Descriptor d : fd.getMessageTypes()) {
                    Descriptor found = find(d, message);
                    if (found != null) desc = found;
                }
            }
            if (desc == null) {
                throw new IllegalStateException(fdsPath + ": " + message + " not found");
            }
            doc = Files.readAllBytes(Paths.get(docPath));
        } catch (Exception e) {
            fatal(2, e.toString());
        }

        // The full decode is the one that validates (pxf.required) and applies
        // (pxf.default); plain unmarshal leaves both to the caller.
        DynamicMessage msg = null;
        try {
            DynamicMessage.Builder b = DynamicMessage.newBuilder(desc);
            Pxf.unmarshalFull(doc, desc, b);
            msg = b.build();
        } catch (PxfException e) {
            System.err.println("reject: " + e.getMessage());
            System.exit(1);
        }

        byte[] out = null;
        try {
            out = mode.equals("--pb") ? msg.toByteArray() : Codec.of(desc.getFile()).marshal(msg);
        } catch (Exception e) {
            fatal(2, e.toString());
        }
        System.out.println(hex(out));
    }

    private static Descriptor find(Descriptor d, String fullName) {
        if (d.getFullName().equals(fullName)) return d;
        for (Descriptor n : d.getNestedTypes()) {
            Descriptor f = find(n, fullName);
            if (f != null) return f;
        }
        return null;
    }

    private static void fatal(int code, String msg) {
        System.err.println("dump-envelope: " + msg);
        System.exit(code);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
