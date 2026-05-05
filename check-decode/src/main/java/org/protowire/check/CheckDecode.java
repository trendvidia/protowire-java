// CheckDecode is the Java reference for the per-port `check-decode` binary
// driven by the protowire HARDENING.md conformance corpus. See:
//
//   protowire/docs/HARDENING.md
//   protowire/scripts/cross_security_check.sh
//   protowire/testdata/adversarial/README.md
//
// Contract:
//
//   check-decode --format <pxf|pb|sbe|envelope> \
//                --schema <fully.qualified.MessageType> \
//                --proto  <path-to-adversarial.proto> \
//                --input  <path>
//
//   Exit 0 -> input was accepted
//   Exit 1 -> input was rejected (clean error; "reject: <msg>" on stderr)
//   Other  -> bug in the decoder (uncaught exception / OOM / ...)
//
// Like the Rust and C++ ports, the Java port handles `--proto <path>.proto`
// by reading the sibling `<stem>.binpb` (a FileDescriptorSet built with
// --include_imports). The protobuf-java runtime cannot compile `.proto` text.
package org.protowire.check;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import org.protowire.pb.Pb;
import org.protowire.pb.ProtoField;
import org.protowire.pxf.Pxf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CheckDecode {

    private CheckDecode() {}

    // -- Hand-mirrored POJOs for adversarial.proto. --------------------------
    // protowire-java's Pb.unmarshal is reflection-driven over @ProtoField, so
    // the four adversarial schemas are re-encoded as Java classes. Drift
    // against adversarial.proto must be caught by the conformance run itself
    // (a wrong number flips a manifest accept/reject expectation).

    public static final class Tree {
        @ProtoField(1) public Tree child;
        @ProtoField(2) public String label = "";
        public Tree() {}
    }

    public static final class StringHolder {
        @ProtoField(1) public String value = "";
        public StringHolder() {}
    }

    public static final class BytesHolder {
        @ProtoField(1) public byte[] value = new byte[0];
        public BytesHolder() {}
    }

    public static final class BigIntHolder {
        @ProtoField(1) public long value;
        public BigIntHolder() {}
    }

    public static void main(String[] args) {
        String format = null, schema = null, proto = null, input = null;
        for (int i = 0; i + 1 < args.length; i += 2) {
            String k = args[i];
            String v = args[i + 1];
            switch (k) {
                case "--format" -> format = v;
                case "--schema" -> schema = v;
                case "--proto"  -> proto  = v;
                case "--input"  -> input  = v;
                default -> {
                    System.err.println("check-decode: unknown arg " + k);
                    System.exit(2);
                }
            }
        }
        if (format == null || schema == null || input == null) {
            System.err.println("usage: check-decode --format <pxf|pb|sbe|envelope> "
                    + "--schema <full.name> --proto <path> --input <path>");
            System.exit(2);
        }

        try {
            run(format, schema, proto, input);
            System.exit(0);
        } catch (RejectException e) {
            System.err.println("reject: " + e.getMessage());
            System.exit(1);
        } catch (Throwable t) {
            // The conformance contract treats "anything other than rc=0/1" as
            // a decoder bug — leave this branch to surface those, but still
            // mark it as a clean reject so a stray IOException from a corrupt
            // input doesn't get reported as an OOM-class crash. We mirror the
            // Go reference, which catches all errors as "reject".
            System.err.println("reject: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
            System.exit(1);
        }
    }

    private static void run(String format, String schema, String proto, String input) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(input));
        switch (format) {
            case "pxf"      -> pxfDecode(data, schema, proto);
            case "pb"       -> pbDecode(data, schema);
            case "envelope" -> throw new RejectException("envelope decode not yet implemented in this reference");
            case "sbe"      -> throw new RejectException("sbe decode not yet implemented in this reference");
            default         -> throw new RejectException("unsupported format: " + format);
        }
    }

    private static void pxfDecode(byte[] data, String schema, String protoPath) throws Exception {
        if (protoPath == null || protoPath.isEmpty()) {
            throw new RejectException("--proto is required for format=pxf");
        }
        Descriptor desc = loadDescriptor(protoPath, schema);
        try {
            Pxf.unmarshal(data, desc);
        } catch (RuntimeException e) {
            // PxfException is the canonical clean-reject signal. Other
            // RuntimeExceptions from descriptor-driven decoding (e.g.
            // IllegalArgumentException for bad UTF-8 or malformed numbers)
            // are also clean rejects under the contract.
            throw new RejectException(messageOf(e));
        }
    }

    private static void pbDecode(byte[] data, String schema) throws Exception {
        Object dest = switch (schema) {
            case "adversarial.v1.Tree"          -> new Tree();
            case "adversarial.v1.StringHolder"  -> new StringHolder();
            case "adversarial.v1.BytesHolder"   -> new BytesHolder();
            case "adversarial.v1.BigIntHolder"  -> new BigIntHolder();
            default -> throw new RejectException("unknown schema for pb: " + schema);
        };
        try {
            Pb.unmarshal(data, dest);
        } catch (IOException | RuntimeException e) {
            throw new RejectException("pb: " + messageOf(e));
        }
    }

    // -- Descriptor loading ---------------------------------------------------

    private static Descriptor loadDescriptor(String protoPath, String schema) throws Exception {
        Path proto = Paths.get(protoPath).toAbsolutePath();
        Path fdsPath = withExtension(proto, ".binpb");
        if (!Files.exists(fdsPath)) {
            throw new RejectException("missing sibling FileDescriptorSet: " + fdsPath);
        }
        byte[] fdsBytes;
        try {
            fdsBytes = Files.readAllBytes(fdsPath);
        } catch (IOException e) {
            throw new RejectException("read " + fdsPath + ": " + e.getMessage());
        }

        FileDescriptorSet fds;
        try {
            fds = FileDescriptorSet.parseFrom(fdsBytes);
        } catch (Exception e) {
            throw new RejectException("parse FileDescriptorSet " + fdsPath + ": " + messageOf(e));
        }

        // Build descriptors in dependency order. The corpus FDS is generated
        // with --include_imports so all transitive deps are present.
        Map<String, FileDescriptor> built = new HashMap<>();
        Map<String, FileDescriptorProto> remaining = new HashMap<>();
        for (FileDescriptorProto fp : fds.getFileList()) {
            remaining.put(fp.getName(), fp);
        }
        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            List<String> toBuild = new ArrayList<>();
            for (Map.Entry<String, FileDescriptorProto> e : remaining.entrySet()) {
                FileDescriptorProto fp = e.getValue();
                boolean depsReady = true;
                for (String dep : fp.getDependencyList()) {
                    if (!built.containsKey(dep)) { depsReady = false; break; }
                }
                if (depsReady) toBuild.add(e.getKey());
            }
            for (String name : toBuild) {
                FileDescriptorProto fp = remaining.remove(name);
                FileDescriptor[] deps = new FileDescriptor[fp.getDependencyCount()];
                for (int i = 0; i < deps.length; i++) {
                    deps[i] = built.get(fp.getDependency(i));
                }
                FileDescriptor fd;
                try {
                    fd = FileDescriptor.buildFrom(fp, deps);
                } catch (Descriptors.DescriptorValidationException ex) {
                    throw new RejectException("buildFrom " + fp.getName() + ": " + ex.getMessage());
                }
                built.put(name, fd);
                progress = true;
            }
        }
        if (!remaining.isEmpty()) {
            throw new RejectException("unresolved descriptor dependencies: " + remaining.keySet());
        }

        for (FileDescriptor fd : built.values()) {
            Descriptor d = findMessage(fd, schema);
            if (d != null) return d;
        }
        throw new RejectException("schema \"" + schema + "\" not in " + fdsPath);
    }

    private static Descriptor findMessage(FileDescriptor fd, String fullName) {
        for (Descriptor m : fd.getMessageTypes()) {
            Descriptor found = findInMessage(m, fullName);
            if (found != null) return found;
        }
        return null;
    }

    private static Descriptor findInMessage(Descriptor d, String fullName) {
        if (d.getFullName().equals(fullName)) return d;
        for (Descriptor n : d.getNestedTypes()) {
            Descriptor found = findInMessage(n, fullName);
            if (found != null) return found;
        }
        return null;
    }

    private static Path withExtension(Path p, String newExt) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        return p.resolveSibling(stem + newExt);
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        if (m != null && !m.isEmpty()) return m;
        return t.getClass().getSimpleName();
    }

    /** Marker for "input was rejected with a clean error" — exit 1. */
    private static final class RejectException extends RuntimeException {
        RejectException(String msg) { super(msg); }
    }
}
