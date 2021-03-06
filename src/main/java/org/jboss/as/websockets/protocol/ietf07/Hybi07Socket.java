/*
 * Copyright 2012 JBoss, by Red Hat, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.as.websockets.protocol.ietf07;

import org.jboss.as.websockets.AbstractWebSocket;
import org.jboss.as.websockets.WebSocket;
import org.jboss.as.websockets.protocol.ietf00.Hybi00Socket;
import org.jboss.as.websockets.util.Hash;
import org.jboss.servlet.http.HttpEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Implementation of the Hybi-07 Websocket Framing Protocol.
 *
 * @author Mike Brock
 */
public class Hybi07Socket extends AbstractWebSocket {
  private final HttpEvent event;
  private final InputStream inputStream;
  private final OutputStream outputStream;

  public Hybi07Socket(HttpEvent event, InputStream inputStream, OutputStream outputStream) {
    this.event = event;
    this.inputStream = inputStream;
    this.outputStream = outputStream;
  }

  public static WebSocket from(final HttpEvent event) throws IOException {
    return new Hybi07Socket(
            event,
            event.getHttpServletRequest().getInputStream(),
            event.getHttpServletResponse().getOutputStream());
  }

  private static final byte FRAME_FIN = Byte.MIN_VALUE;
  private static final byte FRAME_OPCODE = 127;
  private static final byte FRAME_MASKED = Byte.MIN_VALUE;
  private static final byte FRAME_LENGTH = 127;

  private static final int OPCODE_CONTINUATION = 0;
  private static final int OPCODE_TEXT = 1;
  private static final int OPCODE_BINARY = 2;
  private static final int OPCODE_CONNECTION_CLOSE = 3;
  private static final int OPCODE_PING = 4;
  private static final int OPCODE_PONG = 5;

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private String _readTextFrame() throws IOException {
    //TODO: check the first bit?
    int b = inputStream.read();
    final int opcode = (b & FRAME_OPCODE);

    b = inputStream.read();
    final boolean frameMasked = (b & FRAME_MASKED) != 0;

    int payloadLength = (b & FRAME_LENGTH);
    if (payloadLength == 126) {
      payloadLength = ((inputStream.read() & 0xFF) << 8) +
              (inputStream.read() & 0xFF);
    }
    else if (payloadLength == 127) {
      // ignore the first 4-bytes. We can't deal with 64-bit ints right now anyways.
      inputStream.read();
      inputStream.read();
      inputStream.read();
      inputStream.read();
      payloadLength = ((inputStream.read() & 0xFF) << 24) +
              ((inputStream.read() & 0xFF) << 16) +
              ((inputStream.read() & 0xFF) << 8) +
              ((inputStream.read() & 0xFF));
    }

    final byte[] frameMaskingKey = new byte[4];

    if (frameMasked) {
      inputStream.read(frameMaskingKey);
    }

    final StringBuilder payloadBuffer = new StringBuilder(payloadLength);
    switch (opcode) {
      case OPCODE_TEXT:
        int read = 0;
        if (frameMasked) {
          do {
            payloadBuffer.append(((char) ((inputStream.read() ^ frameMaskingKey[read % 4]) & 127)));
          }
          while (++read < payloadLength);
        }
        else {
          // support unmasked frames for testing.

          do {
            payloadBuffer.append((char) inputStream.read());
          }
          while (++read < payloadLength);
        }
        break;
      case OPCODE_CONNECTION_CLOSE:
        event.close();
        break;

      case OPCODE_PING:
      case OPCODE_PONG:
        break;

      case OPCODE_BINARY:
        // binary transmission not yet supported
        throw new RuntimeException("binary frame not yet supported");
    }


    return payloadBuffer.toString();
  }

//  private final static String secureRandomAlgorithm = "SHA1PRNG";
//  final static SecureRandom random;
//
//  static {
//    try {
//      random = SecureRandom.getInstance(secureRandomAlgorithm);
//      random.setSeed(SecureRandom.getInstance(secureRandomAlgorithm).generateSeed(64));
//    }
//    catch (NoSuchAlgorithmException e) {
//      throw new RuntimeException("runtime does not support secure random algorithm: " + secureRandomAlgorithm);
//    }
//  }

  private void _writeTextFrame(final String txt) throws IOException {
    byte[] strBytes = txt.getBytes("UTF-8");

    outputStream.write(-127);
    if (strBytes.length > Short.MAX_VALUE) {
      outputStream.write(-1); // unsigned 7-bit int of value 127 -- leading bit indicates masking.

      // pad the first 4 bytes of 64-bit context length. If this frame is larger than 2GB, you're in trouble. =)
      outputStream.write(0);
      outputStream.write(0);
      outputStream.write(0);
      outputStream.write(0);
      outputStream.write((strBytes.length & 0xFF) << 24);
      outputStream.write((strBytes.length & 0xFF) << 16);
      outputStream.write((strBytes.length & 0xFF) << 8);
      outputStream.write((strBytes.length & 0xFF));
    }
    else if (strBytes.length > 125) {
      outputStream.write(-2); // unsigned 7-bit int of value 126 -- leading bit indicates masking.
      outputStream.write(((strBytes.length >> 8) & 0xFF));
      outputStream.write(((strBytes.length) & 0xFF));
    }
    else {
      outputStream.write(-128 | (strBytes.length & 127));
    }

    /**
     * From IETF Websockets Protocol Specification:
     *
     *   The masking key is a 32-bit value chosen at random by the client.
     The masking key MUST be derived from a strong source of entropy, and
     the masking key for a given frame MUST NOT make it simple for a
     server to predict the masking key for a subsequent frame.  RFC 4086
     [RFC4086] discusses what entails a suitable source of entropy for
     security-sensitive applications.
     */
    final byte[] mask = new byte[4];
    Hash.getRandomBytes(mask);
    outputStream.write(mask);

    int len = strBytes.length;
    for (int j = 0; j < len; j++) {
      outputStream.write((strBytes[j] ^ mask[j % 4]));
    }

    outputStream.flush();
  }

  public void writeTextFrame(String text) throws IOException {
    _writeTextFrame(text);
  }

  public String readTextFrame() throws IOException {
    return _readTextFrame();
  }
}
