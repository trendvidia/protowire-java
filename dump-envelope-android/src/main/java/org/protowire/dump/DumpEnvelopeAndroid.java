// Lite-runtime mirror of DumpEnvelope: builds the same canonical envelope
// and prints its protobuf-encoded bytes as hex. The body is intentionally a
// near-copy of :dump-envelope's main() so divergence between the JVM and
// Android tiers can only be wire-format related, not construction-related.
//
// scripts/cross_envelope_check.sh in the canonical repo asserts exact byte
// equality between this output and :dump-envelope's.
package org.protowire.dump;

import com.google.protobuf.ByteString;
import org.protowire.envelope.v1.AppError;
import org.protowire.envelope.v1.Envelope;
import org.protowire.envelope.v1.FieldError;

public final class DumpEnvelopeAndroid {
    private DumpEnvelopeAndroid() {}

    public static void main(String[] args) {
        FieldError fe = FieldError.newBuilder()
            .setField("amount").setCode("MIN_VALUE")
            .setMessage("below minimum").addArgs("10.00").build();
        AppError ae = AppError.newBuilder()
            .setCode("INSUFFICIENT_FUNDS").setMessage("balance too low")
            .addArgs("$3.50").addArgs("$10.00")
            .addDetails(fe)
            .putMetadata("request_id", "req-123")
            .build();
        Envelope env = Envelope.newBuilder()
            .setStatus(402)
            .setData(ByteString.copyFrom(new byte[] {(byte)0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF}))
            .setError(ae)
            .build();
        byte[] bytes = env.toByteArray();
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        System.out.println(sb);
    }
}
