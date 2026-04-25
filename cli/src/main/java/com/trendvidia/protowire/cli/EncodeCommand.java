package com.trendvidia.protowire.cli;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.trendvidia.protowire.pxf.Pxf;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "encode", description = "Encode PXF to protobuf binary (stdout)")
public final class EncodeCommand implements Callable<Integer> {

    @ParentCommand Main parent;

    @Parameters(index = "0", description = "PXF file") String pxfFile;

    @Override
    public Integer call() throws Exception {
        Descriptor desc = resolve();
        byte[] data = Files.readAllBytes(Path.of(pxfFile));
        DynamicMessage msg = Pxf.unmarshal(data, desc);
        System.out.write(msg.toByteArray());
        System.out.flush();
        return 0;
    }

    Descriptor resolve() throws Exception {
        if (parent.messageName == null || parent.messageName.isEmpty())
            throw new IllegalArgumentException("--message is required");
        if (parent.protoFiles != null && !parent.protoFiles.isEmpty())
            return Resolve.fromLocalDescSet(parent.protoFiles, parent.messageName);
        if (parent.server != null && !parent.server.isEmpty())
            return Resolve.fromRegistry(parent.server, parent.namespace, parent.schemaName, parent.messageName);
        throw new IllegalArgumentException("specify --proto or --server");
    }
}
