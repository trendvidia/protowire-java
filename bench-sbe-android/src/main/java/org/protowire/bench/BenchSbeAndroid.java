// Cross-port SBE microbench: lite-tier (protobuf-javalite) Java implementation.
//
// Mirrors :bench-sbe's BenchSbe shape — same JSON output schema so
// scripts/cross_sbe_bench.sh aggregates the numbers under the `java-lite`
// port row alongside the JVM `java` results. Differences:
//
//   - No FileDescriptorSet / DynamicMessage path. Lite consumes typed
//     codegen-emitted classes only.
//   - marshal goes through OrderSbeCodec.marshal(Order) which composes
//     codegen-emitted typed reader dispatch with :sbe-runtime's
//     SbeWireCodec.
//   - unmarshal goes through OrderSbeCodec.unmarshal(byte[]) → typed Order.
//
// A divergence between :bench-sbe's per-op cost and ours measures the
// runtime overhead the codegen tier eliminates (no descriptor reflection)
// vs. the runtime overhead the lite tier adds (typed switch dispatch on
// every field access).
package org.protowire.bench;

import bench.v1.Order;
import bench.v1.OrderSbeCodec;
import bench.v1.Side;

public final class BenchSbeAndroid {
    private BenchSbeAndroid() {}

    public static void main(String[] args) throws Exception {
        double seconds = 3.0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seconds":
                    seconds = Double.parseDouble(args[++i]);
                    break;
                case "--testdata":
                    // Lite tier doesn't read FileDescriptorSet; --testdata is
                    // accepted for CLI compatibility with cross_sbe_bench.sh
                    // but the value is ignored. Field values are baked in to
                    // match the canonical Order fixture exactly.
                    i++;
                    break;
                default:
                    System.err.println("bench-sbe-android: unknown arg " + args[i]);
                    System.exit(2);
            }
        }

        Order order = canonicalOrder();
        long targetNanos = (long) (seconds * 1_000_000_000L);

        // Warm-up + capture wire size.
        byte[] wireBytes = OrderSbeCodec.marshal(order);
        int n = wireBytes.length;

        long[] m = timeLoop(targetNanos, () -> {
            OrderSbeCodec.marshal(order);
        });
        System.out.printf(
            "{\"port\":\"java-lite\",\"op\":\"sbe-marshal\",\"ns_per_op\":%d,"
            + "\"iterations\":%d,\"bytes\":%d}%n",
            m[1] / m[0], m[0], n);

        long[] u = timeLoop(targetNanos, () -> {
            OrderSbeCodec.unmarshal(wireBytes);
        });
        double secondsF = u[1] / 1_000_000_000.0;
        double mibPerSec = ((double) n * u[0]) / (1024.0 * 1024.0) / secondsF;
        System.out.printf(
            "{\"port\":\"java-lite\",\"op\":\"sbe-unmarshal\",\"ns_per_op\":%d,"
            + "\"mib_per_sec\":%s,\"iterations\":%d,\"bytes\":%d}%n",
            u[1] / u[0], Double.toString(mibPerSec), u[0], n);
    }

    /**
     * Programmatic equivalent of the Order fixture :bench-sbe builds via
     * DynamicMessage. Same field values, same group entries — lite-tier
     * marshal output should byte-equal full-tier marshal output for the
     * same logical input.
     */
    private static Order canonicalOrder() {
        return Order.newBuilder()
            .setOrderId(1001L)
            .setSymbol("AAPL")
            .setPrice(19150L)
            .setQuantity(100)
            .setSide(Side.SIDE_SELL)
            .setActive(true)
            .setWeight(0.85)
            .setScore(2.5f)
            .addFills(Order.Fill.newBuilder()
                .setFillPrice(19155L).setFillQty(25).setFillId(5001L).build())
            .addFills(Order.Fill.newBuilder()
                .setFillPrice(19160L).setFillQty(50).setFillId(5002L).build())
            .build();
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
        return new long[]{iters, System.nanoTime() - start};
    }
}
