package com.trendvidia.protowire.cli;

import com.google.protobuf.Descriptors.Descriptor;
import com.trendvidia.protowire.pxf.Pxf;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "validate", description = "Validate PXF against schema")
public final class ValidateCommand implements Callable<Integer> {

    @ParentCommand Main parent;

    @Parameters(index = "0") String pxfFile;

    @Override
    public Integer call() throws Exception {
        Descriptor desc = resolve();
        byte[] data = Files.readAllBytes(Path.of(pxfFile));
        Pxf.unmarshal(data, desc);
        System.err.println("valid");
        return 0;
    }

    private Descriptor resolve() throws Exception {
        if (parent.protoFiles != null && !parent.protoFiles.isEmpty())
            return Resolve.fromLocalDescSet(parent.protoFiles, parent.messageName);
        return Resolve.fromRegistry(parent.server, parent.namespace, parent.schemaName, parent.messageName);
    }
}
