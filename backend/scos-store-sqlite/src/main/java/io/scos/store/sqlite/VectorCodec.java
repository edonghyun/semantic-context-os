package io.scos.store.sqlite;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** index.sqlite V1 벡터 형식: 리틀 엔디언 float64 BLOB. */
final class VectorCodec {
  private VectorCodec() {}

  static byte[] encode(double[] vector) {
    ByteBuffer buf =
        ByteBuffer.allocate(vector.length * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    for (double d : vector) buf.putDouble(d);
    return buf.array();
  }

  static double[] decode(byte[] bytes) {
    ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    double[] out = new double[bytes.length / Double.BYTES];
    for (int i = 0; i < out.length; i++) out[i] = buf.getDouble();
    return out;
  }
}
