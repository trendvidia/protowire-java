package org.protowire.pxf;

/** Position-aware PXF parse/decode/encode error. */
public class PxfException extends RuntimeException {
    private final Position pos;

    public PxfException(Position pos, String msg) {
        super(pos + ": " + msg);
        this.pos = pos;
    }

    public PxfException(Position pos, String msg, Throwable cause) {
        super(pos + ": " + msg, cause);
        this.pos = pos;
    }

    public Position position() { return pos; }
}
