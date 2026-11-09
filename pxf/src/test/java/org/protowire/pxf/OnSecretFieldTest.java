// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.DynamicMessage;
import org.junit.jupiter.api.Test;
import test.v1.secrets.Secrets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link UnmarshalOptions#withOnSecretField} hands each {@code pxf.Secret}
 * value to the hook in place of the message, as protowire-go's
 * {@code OnSecretField} does (#206). {@code secret-hook/go.tsv} is what
 * protowire-go v1.17.0 does with each document, on the same schema
 * ({@code test/v1/secrets.proto}'s {@code Vault}): the hook calls, the
 * decoded message's bytes, the present paths and the error, with no hook,
 * with the hook on {@code Unmarshal}, and with it on {@code UnmarshalFull}.
 * The message's bytes are compared only where the decode succeeds; after an
 * error, how much of the message was built is neither port's contract.
 *
 * <p>One divergence, pinned in {@link #LITERAL_ELEMENT_PRESENCE}: a Secret
 * literal in a list or a map value reports {@code value} present in Java,
 * as its block does (decided in #127), and not in protowire-go, though Go's
 * block in the same place does. The hook's calls and the message are the
 * same in both.
 */
class OnSecretFieldTest {

    private record Row(String mode, String doc, String calls, String pb, String present, String error) {
        @Override public String toString() { return mode + " " + doc; }
    }

    private static List<Row> rows() throws IOException {
        String text;
        try (InputStream in = OnSecretFieldTest.class.getResourceAsStream("/secret-hook/go.tsv")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Row> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            out.add(new Row(f[0], f[1], f[2], f[3], f[4], f[5]));
        }
        return out;
    }

    /** Document to Java's present paths, where Go leaves out the literal elements' {@code value} (see the class doc). */
    static final java.util.Map<String, String> LITERAL_ELEMENT_PRESENCE = java.util.Map.of(
        "list = [\"a\", \"b\"]", "list|list[0].value|list[1].value",
        "list = [{ value = \"a\"  hint = \"h\" }, \"b\"]", "list|list[0].hint|list[0].value|list[1].value",
        "by_name = { \"acme\": \"k\"  \"z\": { value = \"v\" } }", "by_name|by_name[\"acme\"].value|by_name[\"z\"].value");

    @Test
    void eachDocumentIsDecodedAsGoDecodesIt() throws Exception {
        List<Row> rows = rows();
        assertEquals(48, rows.size(), "go.tsv's rows");
        for (Row r : rows) {
            List<String> calls = new ArrayList<>();
            UnmarshalOptions o = UnmarshalOptions.defaults();
            if (!r.mode().equals("none")) {
                o = o.withOnSecretField((path, value) -> {
                    calls.add(path + "=" + value);
                    if (value.equals("deny")) throw new Exception("denied");
                });
            }
            DynamicMessage.Builder b = DynamicMessage.newBuilder(Secrets.Vault.getDescriptor());
            String error = "";
            Result result = null;
            try {
                if (r.mode().equals("plain")) o.unmarshal(r.doc().getBytes(StandardCharsets.UTF_8), b);
                else result = o.unmarshalFull(r.doc().getBytes(StandardCharsets.UTF_8), b);
            } catch (PxfException e) {
                error = e.getMessage();
            }
            assertEquals(r.error(), error, r.toString());
            assertEquals(r.calls(), String.join("|", calls), r.toString());
            if (!error.isEmpty()) continue;
            if (!r.mode().equals("none")) assertEquals(r.pb(), HexFormat.of().formatHex(b.build().toByteArray()), r.toString());
            if (result != null) {
                assertEquals(LITERAL_ELEMENT_PRESENCE.getOrDefault(r.doc(), r.present()),
                    String.join("|", new TreeSet<>(result.presentFieldsView())), r.toString());
            }
        }
    }

    /** As protowire-go's TestDatasetScan_OnSecretField: a row's cell is a field of the row, so its path is the column. */
    @Test
    void aDatasetRowPassesItsSecretsToTheHook() throws Exception {
        List<String> calls = new ArrayList<>();
        UnmarshalOptions o = UnmarshalOptions.defaults().withOnSecretField((path, value) -> calls.add(path + "=" + value));
        DatasetReader r = new DatasetReader(new java.io.ByteArrayInputStream(
            "@dataset test.v1.secrets.Vault (pw, nested.pw)\n(\"s3cret\", \"n\")\n".getBytes(StandardCharsets.UTF_8)), o);
        DynamicMessage.Builder b = DynamicMessage.newBuilder(Secrets.Vault.getDescriptor());
        r.scan(b);
        assertEquals(List.of("pw=s3cret", "nested.pw=n"), calls);
        DynamicMessage pw = (DynamicMessage) b.build().getField(Secrets.Vault.getDescriptor().findFieldByName("pw"));
        assertEquals("", pw.getField(pw.getDescriptorForType().findFieldByName("value")));
    }
}
