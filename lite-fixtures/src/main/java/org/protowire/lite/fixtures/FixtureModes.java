// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.lite.fixtures;

import bench.v1.Order;
import bench.v1.OrderPxfCodec;
import bench.v1.OrderSbeCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.protowire.pxf.PxfException;
import settings.v1.Settings;
import settings.v1.SettingsPxfCodec;

/**
 * The {@code --pb} / {@code --sbe} fixture modes of the lite dumpers, driven
 * by protowire's {@code scripts/cross_envelope_check.sh}:
 *
 * <pre>
 *   dump-envelope-*-android --pb  FDS MESSAGE DOC   PXF DOC decoded as MESSAGE → pb hex
 *   dump-envelope-*-android --sbe FDS MESSAGE DOC   same → SBE hex
 * </pre>
 *
 * <p>This is a codegen tier: {@code MESSAGE} names a type generated in this
 * module from protowire's fixture schemas, and {@code FDS} is ignored, as
 * {@code bench-sbe-android --testdata} already is. The document goes through
 * the generated {@code <Message>PxfCodec}, whose decode is
 * {@code LiteWireWriter} with the message's {@code PxfMeta}: that is where
 * {@code (pxf.default)} is applied and {@code (pxf.required)} enforced, from
 * values the plugin lifted at codegen time from the registered numbers. SBE
 * bytes come from the generated {@code <Message>SbeCodec}.
 *
 * <p>Exit status: 0 with hex on stdout; 1 with {@code reject: <reason>} on
 * stderr when the schema rejects the document; 2 for anything that is the
 * harness's fault.
 */
public final class FixtureModes {
    private FixtureModes() {}

    /** Returns the process exit code; prints hex or a diagnostic. */
    public static int run(String mode, String fdsPath, String message, String docPath) {
        String doc;
        try {
            doc = new String(Files.readAllBytes(Paths.get(docPath)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("dump-envelope: " + docPath + ": " + e.getMessage());
            return 2;
        }
        try {
            switch (message) {
                case "settings.v1.Settings": {
                    Settings m = SettingsPxfCodec.unmarshal(doc);
                    if (mode.equals("--sbe")) {
                        System.err.println("dump-envelope: " + message + " carries no (sbe.template_id)");
                        return 2;
                    }
                    System.out.println(hex(m.toByteArray()));
                    return 0;
                }
                case "bench.v1.Order": {
                    Order m = OrderPxfCodec.unmarshal(doc);
                    System.out.println(hex(mode.equals("--sbe") ? OrderSbeCodec.marshal(m) : m.toByteArray()));
                    return 0;
                }
                default:
                    System.err.println("dump-envelope: " + message + ": no generated type in lite-fixtures (FDS "
                        + fdsPath + " is not read by a codegen tier)");
                    return 2;
            }
        } catch (PxfException | IllegalArgumentException | IllegalStateException e) {
            System.err.println("reject: " + e.getMessage());
            return 1;
        }
    }

    /** Dispatches a dumper's argument vector: no arguments means the envelope; otherwise a fixture mode. */
    public static boolean isFixtureInvocation(String[] args) {
        return args.length == 4 && (args[0].equals("--pb") || args[0].equals("--sbe"));
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
