package org.protowire.envelope;

import com.google.protobuf.ByteString;
import org.protowire.envelope.v1.AppError;
import org.protowire.envelope.v1.Envelope;
import org.protowire.envelope.v1.FieldError;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builders + queries on top of the generated envelope.v1 protos. */
public final class Envelopes {
    private Envelopes() {}

    public static Envelope ok(int status, byte[] data) {
        return Envelope.newBuilder().setStatus(status).setData(ByteString.copyFrom(data)).build();
    }

    public static Envelope err(int status, String code, String message, String... args) {
        AppError.Builder ae = AppError.newBuilder().setCode(code).setMessage(message);
        for (String a : args) ae.addArgs(a);
        return Envelope.newBuilder().setStatus(status).setError(ae).build();
    }

    public static Envelope transportErr(String message) {
        return Envelope.newBuilder().setTransportError(message).build();
    }

    public static AppError appError(String code, String message, String... args) {
        AppError.Builder b = AppError.newBuilder().setCode(code).setMessage(message);
        for (String a : args) b.addArgs(a);
        return b.build();
    }

    public static AppError withField(AppError e, String field, String code, String message, String... args) {
        AppError.Builder b = e.toBuilder();
        FieldError.Builder fe = FieldError.newBuilder().setField(field).setCode(code).setMessage(message);
        for (String a : args) fe.addArgs(a);
        b.addDetails(fe);
        return b.build();
    }

    public static AppError withMeta(AppError e, String key, String value) {
        return e.toBuilder().putMetadata(key, value).build();
    }

    // -- queries -----------------------------------------------------------

    public static boolean isOk(Envelope e) {
        return e.getTransportError().isEmpty() && !e.hasError();
    }

    public static boolean isTransportError(Envelope e) {
        return !e.getTransportError().isEmpty();
    }

    public static boolean isAppError(Envelope e) { return e.hasError(); }

    public static String errorCode(Envelope e) {
        return e.hasError() ? e.getError().getCode() : "";
    }

    public static Map<String, FieldError> fieldErrors(Envelope e) {
        if (!e.hasError() || e.getError().getDetailsCount() == 0) return Map.of();
        Map<String, FieldError> out = new HashMap<>();
        for (FieldError fe : e.getError().getDetailsList()) out.put(fe.getField(), fe);
        return out;
    }
}
