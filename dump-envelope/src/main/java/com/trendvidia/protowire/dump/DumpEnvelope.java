// Dumps a canonical envelope's protobuf-encoded bytes as hex, for cross-port
// wire-compat checking. The same canonical value is constructed in the Go,
// C++, and TypeScript ports' dump_envelope tools.
package com.trendvidia.protowire.dump;

import com.google.protobuf.ByteString;
import com.trendvidia.protowire.envelope.v1.AppError;
import com.trendvidia.protowire.envelope.v1.Envelope;
import com.trendvidia.protowire.envelope.v1.FieldError;

public final class DumpEnvelope {
    private DumpEnvelope() {}

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
