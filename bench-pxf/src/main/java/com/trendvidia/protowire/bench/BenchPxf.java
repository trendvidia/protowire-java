// Cross-port PXF microbench: Java implementation.
//
// Reads `<testdata>/bench-test.binpb` (FileDescriptorSet) and
// `<testdata>/bench-test.pxf` (text payload), times unmarshal +
// marshal of `bench.v1.Config` for at least `--seconds` (default 3),
// and prints one JSON line per op. The other ports' bench-pxf
// binaries print the same shape; the
// `protowire/scripts/cross_pxf_bench.sh` runner aggregates them.
package com.trendvidia.protowire.bench;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.trendvidia.protowire.pxf.Pxf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public final class BenchPxf {
    private BenchPxf() {}

    public static void main(String[] args) throws Exception {
        double seconds = 3.0;
        Path testdata = Paths.get(System.getProperty("user.dir"), "testdata");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seconds":
                    seconds = Double.parseDouble(args[++i]);
                    break;
                case "--testdata":
                    testdata = Paths.get(args[++i]);
                    break;
                default:
                    System.err.println("bench-pxf: unknown arg " + args[i]);
                    System.exit(2);
            }
        }

        byte[] fdsBytes = Files.readAllBytes(testdata.resolve("bench-test.binpb"));
        byte[] pxfBytes = Files.readAllBytes(testdata.resolve("bench-test.pxf"));

        Descriptor desc = loadConfigDescriptor(fdsBytes);
        long targetNanos = (long) (seconds * 1_000_000_000L);

        // Warm-up.
        Pxf.unmarshal(pxfBytes, desc);

        long[] u = timeLoop(targetNanos, () -> {
            Pxf.unmarshal(pxfBytes, desc);
        });
        emitUnmarshal(u[0], u[1], pxfBytes.length);

        DynamicMessage msg = (DynamicMessage) Pxf.unmarshal(pxfBytes, desc);
        long[] m = timeLoop(targetNanos, () -> {
            Pxf.marshal(msg);
        });
        emitMarshal(m[0], m[1]);
    }

    private static Descriptor loadConfigDescriptor(byte[] fdsBytes) throws Exception {
        FileDescriptorSet fds = FileDescriptorSet.parseFrom(fdsBytes);
        Map<String, FileDescriptor> built = new HashMap<>();
        for (var fp : fds.getFileList()) {
            FileDescriptor[] deps = new FileDescriptor[fp.getDependencyCount()];
            for (int i = 0; i < deps.length; i++) {
                FileDescriptor dep = built.get(fp.getDependency(i));
                if (dep == null) {
                    throw new IllegalStateException(
                        "missing dependency " + fp.getDependency(i)
                        + " for " + fp.getName());
                }
                deps[i] = dep;
            }
            FileDescriptor fd = FileDescriptor.buildFrom(fp, deps);
            built.put(fp.getName(), fd);
        }
        for (FileDescriptor fd : built.values()) {
            Descriptor d = fd.findMessageTypeByName("Config");
            if (d != null && d.getFullName().equals("bench.v1.Config")) {
                return d;
            }
        }
        // Fallback: walk all files, search by full name.
        for (FileDescriptor fd : built.values()) {
            for (Descriptor d : fd.getMessageTypes()) {
                if (d.getFullName().equals("bench.v1.Config")) return d;
            }
        }
        throw new IllegalStateException("bench.v1.Config not found in FileDescriptorSet");
    }

    private static long[] timeLoop(long targetNanos, Runnable fn) {
        long start = System.nanoTime();
        long deadline = start + targetNanos;
        long iters = 0;
        for (;;) {
            for (int i = 0; i < 64; i++) fn.run();
            iters += 64;
            if (System.nanoTime() >= deadline) break;
        }
        long elapsed = System.nanoTime() - start;
        return new long[] { iters, elapsed };
    }

    private static void emitUnmarshal(long iters, long elapsedNanos, int payloadBytes) {
        long nsPerOp = elapsedNanos / iters;
        double seconds = elapsedNanos / 1_000_000_000.0;
        double mibPerSec = ((double) payloadBytes * iters) / (1024.0 * 1024.0) / seconds;
        System.out.printf(
            "{\"port\":\"java\",\"op\":\"unmarshal\",\"ns_per_op\":%d,"
            + "\"mib_per_sec\":%s,\"iterations\":%d,\"bytes\":%d}%n",
            nsPerOp, Double.toString(mibPerSec), iters, payloadBytes);
    }

    private static void emitMarshal(long iters, long elapsedNanos) {
        long nsPerOp = elapsedNanos / iters;
        System.out.printf(
            "{\"port\":\"java\",\"op\":\"marshal\",\"ns_per_op\":%d,\"iterations\":%d}%n",
            nsPerOp, iters);
    }
}
