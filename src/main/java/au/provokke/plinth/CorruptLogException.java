package au.provokke.plinth;

import java.io.IOException;

public final class CorruptLogException extends IOException {
    public CorruptLogException(String message) {
        super(message);
    }
}
