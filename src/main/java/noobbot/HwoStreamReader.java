package noobbot;

import java.io.IOException;
import java.io.Reader;

/**
 * reads json's messages and converts it to json array stream
 */
public final class HwoStreamReader extends Reader {
    private final Reader inputReader;
    private boolean prevCharWasNewLine;
    private boolean arrayOpenSent, arrayCloseSent;

    public HwoStreamReader(Reader inputReader) {
        this.inputReader = inputReader;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        if (len > 0) {
            if (arrayOpenSent) {
                for (int i = 0; i < len; i++) {
                    int ch = inputReader.read();
                    if (ch == -1) {
                        if (i == 0) {
                            if (arrayCloseSent) {
                                return -1;
                            } else {
                                arrayCloseSent = true;
                                cbuf[off] = ']';
                                return 1;
                            }
                        } else {
                            return i;
                        }
                    } else {
                        if (ch == '\n') {
                            cbuf[off + i] = prevCharWasNewLine ? '\n' : ',';
                            prevCharWasNewLine = true;
                            return i + 1;
                        } else {
                            cbuf[off + i] = (char) ch;
                            prevCharWasNewLine = false;
                        }
                    }
                }
                return len;
            } else {
                arrayOpenSent = true;
                cbuf[off] = '[';
                return 1;
            }
        }
        return 0;
    }

    @Override
    public void close() throws IOException {
        inputReader.close();
    }
}
