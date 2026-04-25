package com.trendvidia.protowire.cli;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;

import com.trendvidia.protoregistry.v1.GetDescriptorRequest;
import com.trendvidia.protoregistry.v1.GetDescriptorResponse;
import com.trendvidia.protoregistry.v1.RegistryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.grpc.TlsChannelCredentials;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a message descriptor either from a local set of {@code .proto} files (when
 * {@code --proto} is given) or by calling a remote {@code protoregistry} server.
 *
 * <p>Local-from-source compilation isn't supported in pure Java without protoc as a binary —
 * users compile their own {@code .desc} files and pass the FileDescriptorSet path. The Go module
 * uses bufbuild/protocompile which has no Java equivalent. For local compiled sets pass a
 * {@code .desc} file via {@code --proto schema.desc}.
 */
final class Resolve {
    private Resolve() {}

    static Descriptor fromLocalDescSet(java.util.List<String> paths, String messageName) throws Exception {
        Map<String, FileDescriptor> built = new HashMap<>();
        for (String p : paths) {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(p));
            FileDescriptorSet set = FileDescriptorSet.parseFrom(bytes);
            for (FileDescriptorProto fp : set.getFileList()) {
                FileDescriptor[] deps = resolveDeps(fp, built);
                FileDescriptor fd = FileDescriptor.buildFrom(fp, deps);
                built.put(fd.getName(), fd);
            }
        }
        return findMessage(built, messageName);
    }

    static Descriptor fromRegistry(String server, String namespace, String schema, String messageName) throws Exception {
        ManagedChannel channel = NettyChannelBuilder.forTarget(server).usePlaintext().build();
        try {
            var stub = RegistryServiceGrpc.newBlockingStub(channel);
            GetDescriptorResponse resp = stub.getDescriptor(GetDescriptorRequest.newBuilder()
                    .setNamespaceId(namespace)
                    .setSchemaId(schema)
                    .build());
            FileDescriptorSet set = resp.getFileDescriptorSet();
            Map<String, FileDescriptor> built = new HashMap<>();
            for (FileDescriptorProto fp : set.getFileList()) {
                FileDescriptor[] deps = resolveDeps(fp, built);
                FileDescriptor fd = FileDescriptor.buildFrom(fp, deps);
                built.put(fd.getName(), fd);
            }
            return findMessage(built, messageName);
        } finally {
            channel.shutdownNow();
        }
    }

    private static FileDescriptor[] resolveDeps(FileDescriptorProto fp, Map<String, FileDescriptor> built) {
        FileDescriptor[] deps = new FileDescriptor[fp.getDependencyCount()];
        for (int i = 0; i < deps.length; i++) {
            FileDescriptor dep = built.get(fp.getDependency(i));
            if (dep == null) {
                throw new IllegalStateException("missing dependency: " + fp.getDependency(i));
            }
            deps[i] = dep;
        }
        return deps;
    }

    private static Descriptor findMessage(Map<String, FileDescriptor> files, String fullName) {
        for (FileDescriptor fd : files.values()) {
            Descriptor m = findIn(fd.getMessageTypes(), fullName);
            if (m != null) return m;
        }
        throw new IllegalArgumentException("message \"" + fullName + "\" not found in descriptors");
    }

    private static Descriptor findIn(java.util.List<Descriptor> ms, String fullName) {
        for (Descriptor m : ms) {
            if (m.getFullName().equals(fullName)) return m;
            Descriptor nested = findIn(m.getNestedTypes(), fullName);
            if (nested != null) return nested;
        }
        return null;
    }
}
