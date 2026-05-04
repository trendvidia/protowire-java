// PXF-driven mirror of DumpEnvelope / DumpEnvelopeAndroid: builds the same
// canonical envelope value, but via PXF text → Parser → LiteWireWriter →
// MessageLite.parseFrom → toByteArray. The hex output is asserted against
// :dump-envelope-android's by scripts/cross_envelope_check.sh's
// WITH_JAVA_PXF_LITE branch in the canonical repo — divergence catches an
// encoder-side wire bug in :pxf-android that the protobuf-javalite-direct
// path can't.
package org.protowire.dump;

import org.protowire.envelope.v1.Envelope;
import org.protowire.envelope.v1.EnvelopePxfMeta;
import org.protowire.pxf.Ast;
import org.protowire.pxf.Parser;
import org.protowire.pxf.android.LiteWireWriter;

public final class DumpEnvelopePxfAndroid {
    private DumpEnvelopePxfAndroid() {}

    /**
     * The canonical envelope value, expressed in PXF text. Mirrors the
     * programmatic builder chain in {@code DumpEnvelope.main()} field-for-
     * field. The base64 string {@code "3q2+7w=="} decodes to the four bytes
     * {@code 0xDE 0xAD 0xBE 0xEF} that {@code DumpEnvelope.setData(...)}
     * writes via {@code ByteString.copyFrom(new byte[] {…})}.
     */
    private static final String CANONICAL_PXF =
        "status = 402\n" +
        "data = b\"3q2+7w==\"\n" +
        "error = {\n" +
        "  code = \"INSUFFICIENT_FUNDS\"\n" +
        "  message = \"balance too low\"\n" +
        "  args = [\"$3.50\", \"$10.00\"]\n" +
        "  details = [{\n" +
        "    field = \"amount\"\n" +
        "    code = \"MIN_VALUE\"\n" +
        "    message = \"below minimum\"\n" +
        "    args = [\"10.00\"]\n" +
        "  }]\n" +
        "  metadata {\n" +
        "    \"request_id\": \"req-123\"\n" +
        "  }\n" +
        "}\n";

    public static void main(String[] args) throws Exception {
        Ast.Document doc = Parser.parse(CANONICAL_PXF);
        byte[] wire = LiteWireWriter.encode(doc, EnvelopePxfMeta.INSTANCE);
        // Round-trip through MessageLite.parseFrom + toByteArray to confirm the
        // bytes are syntactically valid as well as binary-equal: parseFrom
        // would reject malformed wire input, so a successful re-serialize
        // implies LiteWireWriter produced a well-formed Envelope.
        Envelope env = Envelope.parseFrom(wire);
        byte[] bytes = env.toByteArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        System.out.println(sb);
    }
}
