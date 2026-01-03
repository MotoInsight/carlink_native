package com.carlink.video;

/**
 * PacketRingByteBuffer - Thread-safe circular buffer for streaming packet data
 *
 * Purpose:
 * Manages high-throughput video/audio packet streams from the CPC200-CCPA adapter
 * in a memory-efficient ring buffer. Designed for automotive environments where
 * memory constraints and stability are critical.
 *
 * Key Features:
 * - Circular buffer with automatic resizing (1MB min, 64MB max)
 * - Thread-safe packet write/read operations with wrap-around support
 * - Zero-copy direct write callback for performance-critical paths
 * - Safe copy on read to eliminate race condition that caused video degradation
 * - Optional zero-intermediate-allocation read into target buffer
 * - Emergency reset mechanisms to prevent OutOfMemoryError
 * - Extensive bounds validation to prevent buffer corruption
 *
 * Packet Structure:
 * Each packet consists of:
 * [4 bytes: packet length] [4 bytes: skip offset] [n bytes: data]
 *
 * Usage:
 * - directWriteToBuffer() - Zero-copy write via callback (preferred for H.264)
 * - readPacket() - Retrieve next packet as independent ByteBuffer (safe copy)
 * - readPacketInto(ByteBuffer target) - Direct copy into codec input buffer (optimal)
 * - availablePacketsToRead() - Check queued packet count
 *
 * Thread Safety:
 * All read/write operations are synchronized. Multiple threads can safely
 * write packets while a decoder thread reads them.
 *
 * Safety Limits:
 * MIN: 1MB | MAX: 64MB | EMERGENCY_RESET: 32MB
 */

import java.nio.ByteBuffer;
import com.carlink.util.LogCallback;
import com.carlink.util.VideoDebugLogger;

public class PacketRingByteBuffer {

    public interface DirectWriteCallback {
        void write(byte[] bytes, int offset);
    }

    // Java memory management best practices - prevent OutOfMemoryError
    private static final int MAX_BUFFER_SIZE = 64 * 1024 * 1024; // 64MB maximum
    private static final int MIN_BUFFER_SIZE = 1024 * 1024; // 1MB minimum
    private static final int EMERGENCY_RESET_THRESHOLD = 32 * 1024 * 1024; // 32MB emergency threshold

    private byte[] buffer;
    private int readPosition = 0;
    private int writePosition = 0;
    private int lastWritePositionBeforeEnd = 0;
    private int packetCount = 0;
    private int resizeAttemptCount = 0;
    private LogCallback logCallback;

    public PacketRingByteBuffer(int initialSize) {
        int safeSize = Math.max(MIN_BUFFER_SIZE, Math.min(initialSize, MAX_BUFFER_SIZE));
        buffer = new byte[safeSize];
        if (safeSize != initialSize) {
            log("Buffer size adjusted from " + initialSize + " to " + safeSize +
                " (min: " + MIN_BUFFER_SIZE + ", max: " + MAX_BUFFER_SIZE + ")");
        }
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
    }

    public void setLogCallback(LogCallback callback) {
        this.logCallback = callback;
    }

    public boolean isEmpty() {
        return packetCount == 0;
    }

    public int availablePacketsToRead() {
        return packetCount;
    }

    private void reorganizeAndResizeIfNeeded() {
        int available = 0;
        if (writePosition > readPosition) {
            available = readPosition + buffer.length - writePosition;
        } else {
            available = readPosition - writePosition;
        }

        int newLength = buffer.length;
        if (available < buffer.length / 2) {
            int proposedSize = newLength * 2;
            resizeAttemptCount++;

            if (proposedSize > MAX_BUFFER_SIZE) {
                if (buffer.length >= EMERGENCY_RESET_THRESHOLD) {
                    log("EMERGENCY: Buffer at " + (buffer.length / (1024*1024)) + "MB, performing emergency reset");
                    VideoDebugLogger.logRingEmergencyReset(buffer.length);
                    performEmergencyReset();
                    return;
                } else {
                    newLength = MAX_BUFFER_SIZE;
                    log("RESIZE capped at maximum: " + (newLength / (1024*1024)) + "MB");
                }
            } else {
                newLength = proposedSize;
                log("RESIZE to: " + (newLength / (1024*1024)) + "MB, attempt: " + resizeAttemptCount +
                    ", read:" + readPosition + ", write:" + writePosition + ", count:" + availablePacketsToRead());
                VideoDebugLogger.logRingResize(buffer.length, newLength, readPosition, writePosition);
            }
        }

        byte[] newBuffer = new byte[newLength];

        if (writePosition < readPosition) {
            int dataAtEndLength = lastWritePositionBeforeEnd - readPosition;
            if (dataAtEndLength < 0 || dataAtEndLength > buffer.length || readPosition + dataAtEndLength > buffer.length) {
                log("CRITICAL: Invalid end copy parameters");
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }
            System.arraycopy(buffer, readPosition, newBuffer, 0, dataAtEndLength);
            System.arraycopy(buffer, 0, newBuffer, dataAtEndLength, writePosition);
            readPosition = 0;
            writePosition += dataAtEndLength;
        } else {
            int copyLength = writePosition - readPosition;
            if (copyLength < 0 || readPosition + copyLength > buffer.length) {
                log("CRITICAL: Invalid linear copy parameters");
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }
            System.arraycopy(buffer, readPosition, newBuffer, 0, copyLength);
            writePosition -= readPosition;
            readPosition = 0;
        }

        log("RESIZE done, read:" + readPosition + ", write:" + writePosition + ", length:" + buffer.length);
        buffer = newBuffer;
    }

    private int availableSpaceAtHead() {
        if (writePosition < readPosition) {
            return readPosition - writePosition;
        }
        return buffer.length - writePosition;
    }

    private int availableSpaceAtStart() {
        if (writePosition < readPosition) {
            return 0;
        }
        return readPosition;
    }

    private static final int MAX_RESIZE_ATTEMPTS = 5;

    public void directWriteToBuffer(int length, int skipBytesCount, DirectWriteCallback callback) {
        synchronized (this) {
            if (length < 0 || skipBytesCount < 0 || skipBytesCount > length) {
                log("CRITICAL: Invalid write parameters - length: " + length + ", skipBytesCount: " + skipBytesCount);
                return;
            }

            if (length > buffer.length / 2) {
                log("WARNING: Large packet size " + length + " bytes, buffer size: " + buffer.length);
            }

            boolean hasSpaceToWriteLength = availableSpaceAtHead() > 8;
            boolean hasSpaceAtHead = availableSpaceAtHead() > length + 8;
            boolean hasSpaceAtStart = availableSpaceAtStart() > length + 8;

            int resizeLoopCount = 0;
            while (!hasSpaceToWriteLength || !(hasSpaceAtStart || hasSpaceAtHead)) {
                resizeLoopCount++;
                if (resizeLoopCount > MAX_RESIZE_ATTEMPTS) {
                    log("CRITICAL: Resize loop exceeded " + MAX_RESIZE_ATTEMPTS + " attempts, forcing emergency reset");
                    performEmergencyReset();
                    hasSpaceToWriteLength = availableSpaceAtHead() > 8;
                    hasSpaceAtHead = availableSpaceAtHead() > length + 8;
                    hasSpaceAtStart = availableSpaceAtStart() > length + 8;
                    if (!hasSpaceToWriteLength || !(hasSpaceAtStart || hasSpaceAtHead)) {
                        log("CRITICAL: Packet too large even after reset - dropping");
                        return;
                    }
                    break;
                }
                reorganizeAndResizeIfNeeded();
                hasSpaceToWriteLength = availableSpaceAtHead() > 8;
                hasSpaceAtHead = availableSpaceAtHead() > length + 8;
                hasSpaceAtStart = availableSpaceAtStart() > length + 8;
            }

            writeInt(writePosition, length);
            writePosition += 4;
            writeInt(writePosition, skipBytesCount);
            writePosition += 4;

            if (!hasSpaceAtHead && hasSpaceAtStart) {
                lastWritePositionBeforeEnd = writePosition;
                writePosition = 0;
            }

            callback.write(buffer, writePosition);
            writePosition += length;
            packetCount++;

            VideoDebugLogger.logRingWrite(length, skipBytesCount, packetCount);
        }
    }

    public void writePacket(byte[] source, int srcOffset, int length) {
        directWriteToBuffer(length, 0, (buf, off) -> System.arraycopy(source, srcOffset, buf, off, length));
    }

    /**
     * Reads the next packet and returns a ByteBuffer containing a safe copy of the data.
     * This prevents race conditions that caused progressive video degradation.
     */
    public ByteBuffer readPacket() {
        synchronized (this) {
            if (packetCount == 0) {
                return ByteBuffer.allocate(0);
            }

            int length = readInt(readPosition);
            readPosition += 4;
            int skipBytes = readInt(readPosition);
            readPosition += 4;

            if (length < 0 || skipBytes < 0 || skipBytes > length) {
                log("CRITICAL: Invalid packet parameters - length: " + length + ", skipBytes: " + skipBytes);
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return ByteBuffer.allocate(0);
            }

            if (readPosition + length > buffer.length) {
                readPosition = 0;
            }

            int actualLength = length - skipBytes;
            int startPos = readPosition + skipBytes;

            if (actualLength < 0 || startPos < 0 || startPos + actualLength > buffer.length) {
                log("CRITICAL: Buffer bounds exceeded - startPos: " + startPos + ", actualLength: " + actualLength);
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return ByteBuffer.allocate(0);
            }

            // CRITICAL FIX: Safe copy to prevent race condition
            // Previous zero-copy wrap caused USB thread to overwrite data before MediaCodec finished decoding
            byte[] packetData = new byte[actualLength];
            System.arraycopy(buffer, startPos, packetData, 0, actualLength);
            ByteBuffer result = ByteBuffer.wrap(packetData);

            readPosition += length;
            packetCount--;

            VideoDebugLogger.logRingRead(length, actualLength, packetCount);
            return result;
        }
    }

    /**
     * Reads the next packet directly into a pre-allocated target ByteBuffer.
     * Eliminates intermediate byte[] allocation for maximum performance.
     *
     * Used by H264Renderer for direct feeding into MediaCodec input buffers.
     */
    public int readPacketInto(ByteBuffer target) {
        synchronized (this) {
            if (packetCount == 0) {
                return 0;
            }

            int length = readInt(readPosition);
            readPosition += 4;

            int skipBytes = readInt(readPosition);
            readPosition += 4;

            if (length < 0 || skipBytes < 0 || skipBytes > length) {
                log("CRITICAL: Invalid packet parameters - length: " + length + ", skipBytes: " + skipBytes);
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return 0;
            }

            if (readPosition + length > buffer.length) {
                readPosition = 0;
            }

            int actualLength = length - skipBytes;
            int startPos = readPosition + skipBytes;

            if (actualLength < 0 || startPos < 0 || startPos + actualLength > buffer.length) {
                log("CRITICAL: Buffer bounds exceeded - startPos: " + startPos + ", actualLength: " + actualLength);
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return 0;
            }

            if (target.remaining() < actualLength) {
                log("CRITICAL: Target buffer too small - remaining: " + target.remaining() + ", needed: " + actualLength);
                readPosition -= 8; // rewind header
                return 0;
            }

            target.put(buffer, startPos, actualLength);

            readPosition += length;
            packetCount--;

            VideoDebugLogger.logRingRead(length, actualLength, packetCount);
            return actualLength;
        }
    }

    private void writeInt(int offset, int value) {
        if (offset < 0 || offset + 3 >= buffer.length) {
            log("CRITICAL: writeInt bounds exceeded - offset: " + offset);
            VideoDebugLogger.logRingBoundsError("writeInt", offset, 4, buffer.length);
            return;
        }
        buffer[offset]     = (byte) ((value & 0xFF000000) >> 24);
        buffer[offset + 1] = (byte) ((value & 0x00FF0000) >> 16);
        buffer[offset + 2] = (byte) ((value & 0x0000FF00) >> 8);
        buffer[offset + 3] = (byte)  (value & 0x000000FF);
    }

    private int readInt(int offset) {
        if (offset < 0 || offset + 3 >= buffer.length) {
            log("CRITICAL: readInt bounds exceeded - offset: " + offset);
            VideoDebugLogger.logRingBoundsError("readInt", offset, 4, buffer.length);
            return 0;
        }
        return ((buffer[offset]     << 24) & 0xFF000000) |
               ((buffer[offset + 1] << 16) & 0x00FF0000) |
               ((buffer[offset + 2] << 8)  & 0x0000FF00) |
               ( buffer[offset + 3]        & 0x000000FF);
    }

    public void reset() {
        synchronized (this) {
            packetCount = 0;
            writePosition = 0;
            readPosition = 0;
        }
    }

    private void performEmergencyReset() {
        log("EMERGENCY RESET: Resetting buffer to prevent OutOfMemoryError - was " +
            (buffer.length / (1024*1024)) + "MB, resetting to " + (MIN_BUFFER_SIZE / (1024*1024)) + "MB");
        buffer = new byte[MIN_BUFFER_SIZE];
        readPosition = 0;
        writePosition = 0;
        lastWritePositionBeforeEnd = 0;
        packetCount = 0;
        resizeAttemptCount = 0;
        log("Emergency reset complete");
    }
}
