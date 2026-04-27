// Cross-port SBE microbench: Java implementation.
//
// Loads `<testdata>/sbe-bench.binpb` (FileDescriptorSet), populates a
// canonical `bench.v1.Order` (10 scalars + 2-entry Fill group), and
// times marshal + unmarshal for at least `--seconds` (default 3).
package com.trendvidia.protowire.bench;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.trendvidia.protowire.sbe.Codec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public final class BenchSbe {
    private BenchSbe() {}

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
                    System.err.println("bench-sbe: unknown arg " + args[i]);
                    System.exit(2);
            }
        }

        byte[] fdsBytes = Files.readAllBytes(testdata.resolve("sbe-bench.binpb"));
        Map<String, FileDescriptor> built = new HashMap<>();
        FileDescriptorSet fds = FileDescriptorSet.parseFrom(fdsBytes);
        FileDescriptor benchFile = null;
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
            if (fp.getName().equals("sbe-bench.proto")) benchFile = fd;
        }
        if (benchFile == null) {
            throw new IllegalStateException("sbe-bench.proto not found in FDS");
        }

        Codec codec = Codec.of(benchFile);
        Descriptor orderDesc = benchFile.findMessageTypeByName("Order");
        Descriptor fillDesc = orderDesc.findNestedTypeByName("Fill");

        Message order = buildOrder(orderDesc, fillDesc);
        long targetNanos = (long) (seconds * 1_000_000_000L);

        // Warm-up + capture wire size.
        byte[] wireBytes = codec.marshal(order);
        int n = wireBytes.length;

        long[] m = timeLoop(targetNanos, () -> {
            codec.marshal(order);
        });
        System.out.printf(
            "{\"port\":\"java\",\"op\":\"sbe-marshal\",\"ns_per_op\":%d,"
            + "\"iterations\":%d,\"bytes\":%d}%n",
            m[1] / m[0], m[0], n);

        long[] u = timeLoop(targetNanos, () -> {
            codec.unmarshalDescriptor(wireBytes, orderDesc);
        });
        double secondsF = u[1] / 1_000_000_000.0;
        double mibPerSec = ((double) n * u[0]) / (1024.0 * 1024.0) / secondsF;
        System.out.printf(
            "{\"port\":\"java\",\"op\":\"sbe-unmarshal\",\"ns_per_op\":%d,"
            + "\"mib_per_sec\":%s,\"iterations\":%d,\"bytes\":%d}%n",
            u[1] / u[0], Double.toString(mibPerSec), u[0], n);
    }

    private static Message buildOrder(Descriptor orderDesc, Descriptor fillDesc) {
        DynamicMessage.Builder b = DynamicMessage.newBuilder(orderDesc);
        b.setField(field(orderDesc, "order_id"), 1001L);
        b.setField(field(orderDesc, "symbol"), "AAPL");
        b.setField(field(orderDesc, "price"), 19150L);
        b.setField(field(orderDesc, "quantity"), 100);
        b.setField(field(orderDesc, "side"),
                   orderDesc.findFieldByName("side").getEnumType().findValueByNumber(1));
        b.setField(field(orderDesc, "active"), true);
        b.setField(field(orderDesc, "weight"), 0.85);
        b.setField(field(orderDesc, "score"), 2.5f);

        for (long[] f : new long[][]{
                {19155L, 25L, 5001L},
                {19160L, 50L, 5002L},
        }) {
            DynamicMessage.Builder fb = DynamicMessage.newBuilder(fillDesc);
            fb.setField(field(fillDesc, "fill_price"), f[0]);
            fb.setField(field(fillDesc, "fill_qty"), (int) f[1]);
            fb.setField(field(fillDesc, "fill_id"), f[2]);
            b.addRepeatedField(field(orderDesc, "fills"), fb.build());
        }
        return b.build();
    }

    private static FieldDescriptor field(Descriptor d, String name) {
        FieldDescriptor fd = d.findFieldByName(name);
        if (fd == null) throw new IllegalStateException("no field " + name);
        return fd;
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
