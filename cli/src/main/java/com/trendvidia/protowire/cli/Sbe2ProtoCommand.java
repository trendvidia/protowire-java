package com.trendvidia.protowire.cli;

import com.trendvidia.protowire.sbe.Convert;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "sbe2proto", description = "Convert SBE XML schema to .proto (stdout)")
public final class Sbe2ProtoCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "SBE XML schema file") String xmlFile;

    @Override
    public Integer call() throws Exception {
        byte[] data = Files.readAllBytes(Path.of(xmlFile));
        String out = Convert.xmlToProto(data);
        System.out.print(out);
        System.out.flush();
        return 0;
    }
}
