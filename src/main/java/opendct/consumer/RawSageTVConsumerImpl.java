/*
 * Copyright 2015 The OpenDCT Authors. All Rights Reserved
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

package opendct.consumer;

import opendct.config.Config;
import opendct.config.options.BooleanDeviceOption;
import opendct.config.options.DeviceOption;
import opendct.config.options.DeviceOptionException;
import opendct.config.options.IntegerDeviceOption;
import opendct.consumer.buffers.SeekableCircularBufferNIO;
import opendct.consumer.upload.NIOSageTVMediaServer;
import opendct.nanohttpd.pojo.JsonOption;
import opendct.video.java.VideoUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class RawSageTVConsumerImpl implements SageTVConsumer {
    private static final Logger logger = LogManager.getLogger(RawSageTVConsumerImpl.class);

    private final boolean acceptsUploadID = uploadIdEnabledOpt.getBoolean();
    private final int minTransferSize = minTransferSizeOpt.getInteger();
    private final int maxTransferSize = maxTransferSizeOpt.getInteger();
    private final int bufferSize = bufferSizeOpt.getInteger();
    private final int rawThreadPriority = threadPriorityOpt.getInteger();

    // volatile long is atomic as long as only one thread ever updates it.
    private volatile long bytesStreamed = 0;

    private boolean consumeToNull = false;
    private FileOutputStream currentFileOutputStream = null;
    private FileOutputStream switchFileOutputStream = null;
    private String currentRecordingFilename = null;
    private String switchRecordingFilename = null;
    private int currentUploadID = -1;
    private int switchUploadID = -1;
    private String currentRecordingQuality = null;
    private int desiredProgram = -1;
    private String tunedChannel = "";

    private AtomicBoolean running = new AtomicBoolean(false);
    private long stvRecordBufferSize = 0;
    private long stvRecordBufferPos = 0;

    private int switchAttempts = 100;
    private volatile boolean switchFile = false;
    private final Object switchMonitor = new Object();

    private ByteBuffer streamBuffer = ByteBuffer.allocateDirect(maxTransferSize);
    private SeekableCircularBufferNIO seekableBuffer = new SeekableCircularBufferNIO(bufferSize);

    private NIOSageTVMediaServer mediaServer = null;

    private final int uploadIDPort = uploadIdPortOpt.getInteger();
    private SocketAddress uploadSocket = null;

    static {
        deviceOptions = new ConcurrentHashMap<>();

        initDeviceOptions();
    }

    public void run() {
        if (running.getAndSet(true)) {
            throw new IllegalThreadStateException("Raw consumer is already running.");
        }

        logger.debug("Thread priority is {}.", rawThreadPriority);
        Thread.currentThread().setPriority(rawThreadPriority);

        int bytesReceivedCount = 0;
        int bytesReceivedBuffer = 0;

        boolean isFailed = false;
        boolean uploadEnabled = false;
        int bytesToStream = 0;
        FileChannel currentFile = null;
        switchFile = false;
        seekableBuffer.clear();

        try {
            logger.info("Raw consumer thread is now running.");

            if (currentUploadID > 0) {
                if (mediaServer == null) {
                    mediaServer = new NIOSageTVMediaServer();
                } else {
                    mediaServer.reset();
                }

                boolean uploadIDConfigured = false;

                try {
                    uploadIDConfigured = mediaServer.startUpload(
                            uploadSocket, currentRecordingFilename, currentUploadID);
                } catch (IOException e) {
                    logger.error("Unable to connect to SageTV server to start transfer via uploadID.");
                }

                if (!uploadIDConfigured) {

                    logger.error("Raw consumer did not receive OK from SageTV server to start" +
                                    " uploading to the file '{}' via the upload id '{}'" +
                                    " using the socket '{}'.",
                            currentRecordingFilename, currentUploadID, uploadSocket);

                    if (currentRecordingFilename != null) {
                        logger.info("Attempting to write the file directly...");
                        try {
                            this.currentFileOutputStream = new FileOutputStream(currentRecordingFilename);
                            currentFile = currentFileOutputStream.getChannel();
                        } catch (FileNotFoundException e) {
                            logger.error("Unable to create the recording file '{}'.", currentRecordingFilename);
                            currentRecordingFilename = null;
                        }
                    }
                } else {
                    uploadEnabled = true;
                }
            } else if (currentRecordingFilename != null) {
                currentFile = currentFileOutputStream.getChannel();
            } else if (consumeToNull) {
                logger.debug("Consuming to a null output...");
            } else {
                logger.error("Raw consumer does not have a file or UploadID to use.");
                throw new IllegalThreadStateException(
                        "Raw consumer does not have a file or UploadID to use.");
            }

            boolean start = true;
            logger.info("Waiting for PES start byte...");
            while (!Thread.currentThread().isInterrupted()) {
                streamBuffer.clear();

                while (streamBuffer.position() < minTransferSize && !Thread.currentThread().isInterrupted()) {

                    seekableBuffer.read(streamBuffer);

                    if (switchFile) {
                        break;
                    }
                }

                // Switch the buffers to reading mode.
                streamBuffer.flip();

                if (start) {
                    int startIndex = VideoUtil.getTsVideoPesStartByte(
                            streamBuffer,
                            false
                    );

                    if (startIndex > 0) {
                        streamBuffer.position(startIndex);
                        start = false;
                        logger.info("Raw consumer is now streaming...");
                    } else {
                        continue;
                    }
                }

                try {
                    if (uploadEnabled) {
                        if (switchFile) {
                            int switchIndex;

                            if (switchAttempts-- > 0) {
                                switchIndex = VideoUtil.getTsVideoRandomAccessIndicator(
                                        streamBuffer,
                                        false
                                );
                            } else {
                                if (switchAttempts == -1) {
                                    logger.warn("Stream does not appear to contain any random access" +
                                            " indicators. Using the nearest PES packet.");
                                }

                                switchIndex = VideoUtil.getTsVideoPesStartByte(
                                        streamBuffer,
                                        false
                                );
                            }

                            if (switchIndex > -1) {
                                switchAttempts = 100;

                                synchronized (switchMonitor) {
                                    int lastBytesToStream = 0;
                                    if (switchIndex > streamBuffer.position()) {
                                        ByteBuffer lastWriteBuffer = streamBuffer.duplicate();
                                        lastWriteBuffer.limit(switchIndex - 1);
                                        streamBuffer.position(switchIndex);

                                        lastBytesToStream = lastWriteBuffer.remaining();


                                        if (stvRecordBufferSize > 0) {
                                            mediaServer.uploadAutoBuffered(stvRecordBufferSize, lastWriteBuffer);
                                        } else {
                                            mediaServer.uploadAutoIncrement(lastWriteBuffer);
                                        }
                                    }

                                    bytesStreamed += lastBytesToStream;

                                    mediaServer.endUpload();
                                    mediaServer.reset();
                                    if (!mediaServer.startUpload(uploadSocket,
                                            switchRecordingFilename, switchUploadID)) {

                                        logger.error("Raw consumer did not receive OK from SageTV" +
                                                        " server to switch to the file '{}' via the" +
                                                        " upload id '{}'.",
                                                switchRecordingFilename, switchUploadID);
                                    } else {
                                        currentRecordingFilename = switchRecordingFilename;
                                        currentUploadID = switchUploadID;
                                        bytesStreamed = 0;
                                        switchFile = false;

                                        switchMonitor.notifyAll();
                                        logger.info("SWITCH was successful.");
                                    }

                                }
                            }
                        }

                        boolean retry = true;
                        bytesToStream = streamBuffer.remaining();
                        while (true) {
                            try {
                                if (stvRecordBufferSize > 0) {
                                    mediaServer.uploadAutoBuffered(stvRecordBufferSize, streamBuffer);
                                } else {
                                    mediaServer.uploadAutoIncrement(streamBuffer);
                                }
                            } catch (IOException e) {
                                if (retry && !isFailed) {
                                    retry = false;
                                    isFailed = true;
                                    try {
                                        mediaServer.endUpload();
                                    } catch (Exception e1) {
                                        logger.debug("Error cleaning up broken connection => {}" + e1.getMessage());
                                    }
                                    mediaServer.startUpload(uploadSocket, currentRecordingFilename, currentUploadID, mediaServer.getAutoOffset());
                                    isFailed = false;
                                    continue;
                                } else {
                                    throw e;
                                }
                            }
                            break;
                        }

                        bytesStreamed += bytesToStream;
                    } else if (!consumeToNull) {
                        if (switchFile) {
                            int switchIndex = VideoUtil.getTsVideoPatStartByte(
                                    streamBuffer,
                                    false
                            );

                            if (switchIndex > -1) {
                                synchronized (switchMonitor) {
                                    int lastBytesToStream = 0;
                                    if (switchIndex > streamBuffer.position()) {
                                        ByteBuffer lastWriteBuffer = streamBuffer.duplicate();
                                        lastWriteBuffer.limit(switchIndex - 1);
                                        streamBuffer.position(switchIndex);

                                        lastBytesToStream = lastWriteBuffer.remaining();

                                        while (lastWriteBuffer.hasRemaining()) {
                                            int savedSize = currentFile.write(lastWriteBuffer);
                                            bytesStreamed += savedSize;

                                            if (stvRecordBufferSize > 0 && stvRecordBufferPos >
                                                    stvRecordBufferSize) {

                                                currentFile.position(0);
                                            }
                                            stvRecordBufferPos = currentFile.position();
                                        }
                                    }

                                    bytesStreamed += lastBytesToStream;

                                    if (switchFileOutputStream != null) {
                                        if (currentFile != null && currentFile.isOpen()) {
                                            try {
                                                currentFile.close();
                                            } catch (IOException e) {
                                                logger.error("Raw consumer created an exception" +
                                                        " while closing the current file => {}", e);
                                            } finally {
                                                currentFile = null;
                                            }
                                        }
                                        currentFile = switchFileOutputStream.getChannel();
                                        currentFileOutputStream = switchFileOutputStream;
                                        currentRecordingFilename = switchRecordingFilename;
                                        switchFileOutputStream = null;
                                        bytesStreamed = 0;
                                    }
                                    switchFile = false;

                                    switchMonitor.notifyAll();
                                    logger.info("SWITCH was successful.");
                                }
                            }
                        }

                        while (currentFile != null && streamBuffer.hasRemaining()) {
                            int savedSize = currentFile.write(streamBuffer);

                            bytesStreamed += savedSize;

                            if (stvRecordBufferSize > 0 && stvRecordBufferPos >
                                    stvRecordBufferSize) {

                                currentFile.position(0);
                            }
                            stvRecordBufferPos = currentFile.position();
                        }
                    } else {
                        // Write to null.
                        bytesStreamed += streamBuffer.limit();
                    }
                } catch (IOException e) {
                    logger.error("Raw consumer created an unexpected IO exception => {}", e);
                }
            }
        } catch (InterruptedException e) {
            logger.debug("Raw consumer was closed by an interrupt exception => ", e);
        } catch (Exception e) {
            logger.error("Raw consumer created an unexpected exception => {}", e);
        } finally {
            logger.info("Raw consumer thread is now stopping.");

            bytesStreamed = 0;

            seekableBuffer.clear();

            currentRecordingFilename = null;
            if (currentFile != null && currentFile.isOpen()) {
                try {
                    currentFile.close();
                } catch (IOException e) {
                    logger.debug("Raw consumer created an exception while closing the current file => {}", e);
                } finally {
                    currentFile = null;
                }
            }

            if (mediaServer != null) {
                try {
                    mediaServer.endUpload();
                } catch (IOException e) {
                    logger.debug("Raw consumer created an exception while ending the current upload id session => ", e);
                } finally {
                    mediaServer = null;
                }
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Bytes available to be read = {}", seekableBuffer.readAvailable());
                logger.debug("Space available for writing in bytes = {}", seekableBuffer.writeAvailable());
            }

            logger.info("Raw consumer thread has stopped.");
            running.set(false);
        }
    }

    public void write(byte[] bytes, int offset, int length) throws IOException {
        seekableBuffer.write(bytes, offset, length);
    }

    public void write(ByteBuffer buffer) throws IOException {
        seekableBuffer.write(buffer);
    }

    @Override
    public void clearBuffer() {
        seekableBuffer.close();
        seekableBuffer.clear();
    }

    public void setRecordBufferSize(long bufferSize) {
        this.stvRecordBufferSize = bufferSize;
    }

    public boolean canSwitch() {
        return true;
    }

    public boolean getIsRunning() {
        return running.get();
    }

    public void stopConsumer() {
        seekableBuffer.close();
        if (mediaServer != null) {
            try {
                mediaServer.endUpload();
            } catch (IOException e) {
                logger.debug("There was a problem while disconnecting from Media Server => ", e);
            }
        }
    }

    public long getBytesStreamed() {
        return bytesStreamed;
    }

    public boolean acceptsUploadID() {
        return acceptsUploadID;
    }

    public boolean acceptsFilename() {
        return true;
    }

    public void setEncodingQuality(String encodingQuality) {
        currentRecordingQuality = encodingQuality;
    }

    public boolean consumeToUploadID(String filename, int uploadId, InetAddress inetAddress) {
        logger.entry(filename, uploadId, inetAddress);

        this.currentRecordingFilename = filename;
        this.currentUploadID = uploadId;

        uploadSocket = new InetSocketAddress(inetAddress, uploadIDPort);

        return logger.exit(true);
    }


    public boolean consumeToFilename(String filename) {
        logger.entry(filename);

        try {
            this.currentFileOutputStream = new FileOutputStream(filename);
            this.currentRecordingFilename = filename;
        } catch (FileNotFoundException e) {
            logger.error("Unable to create the recording file '{}'.", filename);
            return logger.exit(false);
        }

        return logger.exit(true);
    }

    public synchronized boolean switchStreamToUploadID(String filename, long bufferSize, int uploadId) {
        logger.entry(filename, bufferSize, uploadId);

        logger.info("SWITCH to '{}' via uploadID '{}' was requested.", filename, uploadId);

        synchronized (switchMonitor) {
            this.switchUploadID = uploadId;
            this.switchRecordingFilename = filename;
            this.switchFile = true;

            while (switchFile && this.getIsRunning()) {
                try {
                    switchMonitor.wait(500);
                } catch (Exception e) {
                    break;
                }
            }
        }

        return logger.exit(false);
    }

    public synchronized boolean switchStreamToFilename(String filename, long bufferSize) {
        logger.entry(filename);

        logger.info("SWITCH to '{}' was requested.", filename);

        try {
            synchronized (switchMonitor) {
                this.switchFileOutputStream = new FileOutputStream(filename);
                this.switchRecordingFilename = filename;
                this.switchFile = true;

                while (switchFile && this.getIsRunning()) {
                    try {
                        switchMonitor.wait(500);
                    } catch (Exception e) {
                        break;
                    }
                }
            }
        } catch (FileNotFoundException e) {
            logger.error("Unable to create the recording file '{}'.", filename);
            return logger.exit(false);
        }

        return logger.exit(true);
    }

    public String getEncoderQuality() {
        return currentRecordingQuality;
    }

    public String getEncoderFilename() {
        return currentRecordingFilename;
    }

    public int getEncoderUploadID() {
        return currentUploadID;
    }

    public void setProgram(int program) {
        desiredProgram = program;
    }

    public int getProgram() {
        return desiredProgram;
    }

    public void consumeToNull(boolean consumeToNull) {
        this.consumeToNull = consumeToNull;
    }

    public String getChannel() {
        return tunedChannel;
    }

    public void setChannel(String tunedChannel) {
        this.tunedChannel = tunedChannel;
    }

    /**
     * This method always returns immediately for the raw consumer because it just streams.
     *
     * @param timeout The amount of time in milliseconds to block until returning even if the stream
     *                has not started.
     * @return <i>true</i> if the consumer is currently streaming.
     */
    public boolean isStreaming(long timeout) {
        try {
            int segments = 5;
            long increment = timeout / segments;

            if (increment < 1000) {
                Thread.sleep(timeout);
            } else {
                while (segments-- > 0 && getBytesStreamed() > 1048576) {
                    Thread.sleep(increment);
                }
            }
        } catch (InterruptedException e) {
            logger.debug("Interrupted while waiting for streaming.");
        }

        return true;
    }

    private final static Map<String, DeviceOption> deviceOptions;

    private static BooleanDeviceOption uploadIdEnabledOpt;
    private static IntegerDeviceOption minTransferSizeOpt;
    private static IntegerDeviceOption maxTransferSizeOpt;
    private static IntegerDeviceOption bufferSizeOpt;
    private static IntegerDeviceOption threadPriorityOpt;
    private static IntegerDeviceOption uploadIdPortOpt;

    private static void initDeviceOptions() {
        while (true) {
            try {
                uploadIdEnabledOpt = new BooleanDeviceOption(
                        Config.getBoolean("consumer.raw.upload_id_enabled", true),
                        false,
                        "Enable Upload ID",
                        "consumer.raw.upload_id_enabled",
                        "This enables the use of upload ID with SageTV for writing out recordings.");

                minTransferSizeOpt = new IntegerDeviceOption(
                        Config.getInteger("consumer.raw.min_transfer_size", 65536),
                        false,
                        "Min Transfer Rate",
                        "consumer.raw.min_transfer_size",
                        "This is the minimum number of bytes to write at one time. This value" +
                                " cannot be less than 16384 bytes and cannot be greater than" +
                                " 262144 bytes.",
                        16384,
                        262144);

                maxTransferSizeOpt = new IntegerDeviceOption(
                        Config.getInteger("consumer.raw.max_transfer_size", 1048476),
                        false,
                        "Max Transfer Rate",
                        "consumer.raw.max_transfer_size",
                        "This is the maximum number of bytes to write at one time. This value" +
                                " cannot be less than 786432 bytes and cannot be greater than" +
                                " 1048576 bytes.",
                        786432,
                        1048576);

                bufferSizeOpt = new IntegerDeviceOption(
                        Config.getInteger("consumer.raw.stream_buffer_size", 2097152),
                        false,
                        "Stream Buffer Size",
                        "consumer.raw.stream_buffer_size",
                        "This is the size of the streaming buffer. If this is not greater than 2" +
                                " * Max Transfer Size, it will be adjusted. This value cannot be" +
                                " less than 2097152 bytes and cannot be greater than 33554432" +
                                " bytes.",
                        2097152,
                        33554432);


                threadPriorityOpt = new IntegerDeviceOption(
                        Config.getInteger("consumer.raw.thread_priority", Thread.MAX_PRIORITY - 1),
                        false,
                        "Raw Thread Priority",
                        "consumer.raw.thread_priority",
                        "This is the priority given to the raw processing thread. A higher" +
                                " number means higher priority. Only change this value for" +
                                " troubleshooting. This value cannot be less than 1 and cannot be" +
                                " greater than 10.",
                        Thread.MIN_PRIORITY,
                        Thread.MAX_PRIORITY);

                uploadIdPortOpt = new IntegerDeviceOption(
                        Config.getInteger("consumer.raw.upload_id_port", 7818),
                        false,
                        "SageTV Upload ID Port",
                        "consumer.raw.upload_id_port",
                        "This is the port number used to communicate with SageTV when using" +
                                " upload ID for recording. You only need to change this value if" +
                                " you have changed it in SageTV. This value cannot be less than" +
                                " 1024 and cannot be greater than 65535.",
                        1024,
                        65535);

            } catch (DeviceOptionException e) {
                logger.warn("Invalid options. Reverting to defaults => ", e);

                Config.setBoolean("consumer.raw.upload_id_enabled", true);
                Config.setInteger("consumer.raw.min_transfer_size", 65536);
                Config.setInteger("consumer.raw.max_transfer_size", 1048476);
                Config.setInteger("consumer.raw.stream_buffer_size", 2097152);
                Config.setInteger("consumer.raw.thread_priority", Thread.MAX_PRIORITY - 2);
                Config.setInteger("consumer.raw.upload_id_port", 7818);
                continue;
            }

            break;
        }

        Config.mapDeviceOptions(
                deviceOptions,
                uploadIdEnabledOpt,
                minTransferSizeOpt,
                maxTransferSizeOpt,
                bufferSizeOpt,
                threadPriorityOpt,
                uploadIdPortOpt
        );
    }

    @Override
    public DeviceOption[] getOptions() {
        return new DeviceOption[] {
                uploadIdEnabledOpt,
                minTransferSizeOpt,
                maxTransferSizeOpt,
                bufferSizeOpt,
                threadPriorityOpt,
                uploadIdPortOpt
        };
    }

    @Override
    public void setOptions(JsonOption... deviceOptions) throws DeviceOptionException {
        for (JsonOption option : deviceOptions) {
            DeviceOption optionReference =
                    RawSageTVConsumerImpl.deviceOptions.get(option.getProperty());

            if (optionReference == null) {
                continue;
            }

            if (optionReference.isArray()) {
                optionReference.setValue(option.getValues());
            } else {
                optionReference.setValue(option.getValue());
            }

            Config.setDeviceOption(optionReference);
        }

        Config.saveConfig();
    }
}

