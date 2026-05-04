// Cross-port PXF microbench: lite-tier (protobuf-javalite) Java implementation.
//
// Mirrors :bench-pxf's BenchPxf shape — same JSON output schema so
// scripts/cross_pxf_bench.sh aggregates the numbers under the `java-lite`
// port row alongside the JVM `java` results. Differences:
//
//   - No FileDescriptorSet / DynamicMessage path. Lite consumes typed
//     codegen-emitted classes only.
//   - unmarshal goes through ConfigPxfCodec.unmarshal (Parser →
//     LiteWireWriter → Config.parseFrom).
//   - marshal goes through ConfigPxfCodec.marshal (Config.toByteArray →
//     LiteWireReader.toAst → Format.formatDocument).
//
// A divergence between :bench-pxf's per-op cost and ours measures the
// runtime overhead the codegen tier eliminates (no descriptor reflection)
// vs. the runtime overhead the lite tier adds (textual round-trip on the
// marshal side).
package org.protowire.bench;

import bench.v1.Config;
import bench.v1.ConfigPxfCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class BenchPxfAndroid {
    private BenchPxfAndroid() {}

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
                    System.err.println("bench-pxf-android: unknown arg " + args[i]);
                    System.exit(2);
            }
        }

        byte[] pxfBytes = Files.readAllBytes(testdata.resolve("bench-test.pxf"));
        long targetNanos = (long) (seconds * 1_000_000_000L);

        // Warm-up to amortize class loading + JIT. Without this the first
        // iterations of the timed loop dominate the average for short runs.
        ConfigPxfCodec.unmarshal(pxfBytes);

        long[] u = timeLoop(targetNanos, () -> {
            ConfigPxfCodec.unmarshal(pxfBytes);
        });
        emitUnmarshal(u[0], u[1], pxfBytes.length);

        Config msg = ConfigPxfCodec.unmarshal(pxfBytes);
        long[] m = timeLoop(targetNanos, () -> {
            ConfigPxfCodec.marshal(msg);
        });
        emitMarshal(m[0], m[1]);
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
            "{\"port\":\"java-lite\",\"op\":\"unmarshal\",\"ns_per_op\":%d,"
            + "\"mib_per_sec\":%s,\"iterations\":%d,\"bytes\":%d}%n",
            nsPerOp, Double.toString(mibPerSec), iters, payloadBytes);
    }

    private static void emitMarshal(long iters, long elapsedNanos) {
        long nsPerOp = elapsedNanos / iters;
        System.out.printf(
            "{\"port\":\"java-lite\",\"op\":\"marshal\",\"ns_per_op\":%d,\"iterations\":%d}%n",
            nsPerOp, iters);
    }
}
