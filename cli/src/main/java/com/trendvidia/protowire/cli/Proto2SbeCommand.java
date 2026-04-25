package com.trendvidia.protowire.cli;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.trendvidia.protowire.sbe.Convert;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "proto2sbe", description = "Convert .proto with SBE annotations to SBE XML (stdout). Pass FileDescriptorSet via --proto file.desc")
public final class Proto2SbeCommand implements Callable<Integer> {

    @ParentCommand Main parent;

    @Override
    public Integer call() throws Exception {
        if (parent.protoFiles == null || parent.protoFiles.isEmpty()) {
            throw new IllegalArgumentException("--proto is required");
        }
        Map<String, FileDescriptor> built = new HashMap<>();
        List<FileDescriptor> roots = new ArrayList<>();
        for (String p : parent.protoFiles) {
            byte[] bytes = Files.readAllBytes(Path.of(p));
            FileDescriptorSet set = FileDescriptorSet.parseFrom(bytes);
            for (FileDescriptorProto fp : set.getFileList()) {
                FileDescriptor[] deps = new FileDescriptor[fp.getDependencyCount()];
                for (int i = 0; i < deps.length; i++) {
                    FileDescriptor dep = built.get(fp.getDependency(i));
                    if (dep == null) throw new IllegalStateException("missing dep " + fp.getDependency(i));
                    deps[i] = dep;
                }
                FileDescriptor fd = FileDescriptor.buildFrom(fp, deps);
                built.put(fd.getName(), fd);
                roots.add(fd);
            }
        }
        String out = Convert.protoToXml(roots.toArray(new FileDescriptor[0]));
        System.out.print(out);
        System.out.flush();
        return 0;
    }
}
