package com.trendvidia.protowire.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "protowire",
        mixinStandardHelpOptions = true,
        version = "protowire4java 0.1.0",
        description = "PXF encoding tool — human-friendly protobuf text format",
        subcommands = {
                EncodeCommand.class,
                DecodeCommand.class,
                ValidateCommand.class,
                FmtCommand.class,
                Sbe2ProtoCommand.class,
                Proto2SbeCommand.class,
        })
public final class Main {

    @Option(names = {"-p", "--proto"}, description = "proto file(s) to compile", split = ",")
    public java.util.List<String> protoFiles;

    @Option(names = {"-m", "--message"}, description = "fully qualified message name")
    public String messageName;

    @Option(names = {"-s", "--server"}, description = "protoregistry gRPC address",
            defaultValue = "${env:PROTOREGISTRY_SERVER}")
    public String server;

    @Option(names = {"-n", "--namespace"}, description = "protoregistry namespace",
            defaultValue = "${env:PROTOREGISTRY_NAMESPACE}")
    public String namespace;

    @Option(names = {"--schema"}, description = "protoregistry schema name")
    public String schemaName;

    public static void main(String[] args) {
        int rc = new CommandLine(new Main()).execute(args);
        System.exit(rc);
    }
}
