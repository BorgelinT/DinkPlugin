package dinkplugin.util;

import dinkplugin.DinkPlugin;
import dinkplugin.DinkPluginConfig;
import dinkplugin.message.DiscordMessageHandler;
import dinkplugin.message.Embed;
import dinkplugin.message.NotificationBody;
import dinkplugin.message.NotificationType;
import dinkplugin.message.templating.Template;
import dinkplugin.notifiers.data.LootNotificationData;
import dinkplugin.notifiers.data.SerializedItemStack;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.DrawManager;
import org.jcodec.api.awt.AWTSequenceEncoder;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Singleton
public class ClipManager {

    private static final int MAX_HEIGHT = 720;
    private static final float JPEG_QUALITY = 0.8f;

    @Inject
    private DinkPluginConfig config;

    @Inject
    private DrawManager drawManager;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private ClientThread clientThread;

    private final Object bufferLock = new Object();
    private byte[][] frameBuffer;
    private volatile int writeIndex;
    private volatile int frameCount;

    @Getter
    private volatile boolean active;
    private final AtomicBoolean clipping = new AtomicBoolean(false);
    private ScheduledExecutorService captureScheduler;
    private ExecutorService processingExecutor;
    private ScheduledFuture<?> captureTask;

    public void start() {
        if (active || captureTask != null || captureScheduler != null || processingExecutor != null || frameBuffer != null) {
            stop();
        }

        if (!config.clipEnabled()) {
            log.debug("Clip recording is disabled");
            return;
        }

        int fps = config.clipFps();
        int bufferSize = fps * (config.clipDurationPre() + config.clipDurationPost());
        synchronized (bufferLock) {
            frameBuffer = new byte[bufferSize][];
            writeIndex = 0;
            frameCount = 0;
        }
        active = true;
        clipping.set(false);

        // Use a dedicated scheduler to request frames at target FPS
        // Each tick requests a single frame from DrawManager (non-blocking)
        captureScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dink-clip-capture");
            t.setDaemon(true);
            return t;
        });
        processingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dink-clip-processing");
            t.setDaemon(true);
            return t;
        });
        long intervalMs = 1000L / fps;
        captureTask = captureScheduler.scheduleAtFixedRate(this::requestFrame, 0, intervalMs, TimeUnit.MILLISECONDS);

        log.debug("Clip recording started: {}fps, {} frame buffer, {}ms interval", fps, bufferSize, intervalMs);
    }

    public void stop() {
        active = false;
        if (captureTask != null) {
            captureTask.cancel(false);
            captureTask = null;
        }
        if (captureScheduler != null) {
            captureScheduler.shutdownNow();
            captureScheduler = null;
        }
        if (processingExecutor != null) {
            processingExecutor.shutdownNow();
            processingExecutor = null;
        }
        synchronized (bufferLock) {
            frameBuffer = null;
            writeIndex = 0;
            frameCount = 0;
        }
        clipping.set(false);
        log.debug("Clip recording stopped");
    }

    public void onConfigChanged(String key) {
        switch (key) {
            case "clipEnabled":
            case "clipFps":
            case "clipDurationPre":
            case "clipDurationPost":
                stop();
                start();
                break;
        }
    }

    public CompletableFuture<byte[]> requestClip(int postEventDelayMs) {
        if (!active || frameBuffer == null) {
            return CompletableFuture.completedFuture(null);
        }

        if (!clipping.compareAndSet(false, true)) {
            log.debug("Clip already in progress, skipping");
            return CompletableFuture.completedFuture(null);
        }

        if (postEventDelayMs <= 0) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return encodeClip();
                } finally {
                    clipping.set(false);
                }
            }, getProcessingExecutor());
        }

        CompletableFuture<byte[]> future = new CompletableFuture<>();
        executor.schedule(() -> {
            CompletableFuture.supplyAsync(this::encodeClip, getProcessingExecutor())
                .whenComplete((mp4, error) -> {
                    try {
                        if (error != null) {
                            future.completeExceptionally(error);
                        } else {
                            future.complete(mp4);
                        }
                    } finally {
                        clipping.set(false);
                    }
                });
        }, postEventDelayMs, TimeUnit.MILLISECONDS);
        return future;
    }

    public CompletableFuture<byte[]> requestClip() {
        return requestClip(config.clipDurationPost() * 1000);
    }

    public boolean shouldSendClip(NotificationBody<?> body) {
        if (!active || !config.clipEnabled()) return false;
        switch (body.getType()) {
            case LOOT:
                int threshold = config.lootClipMinValue();
                if (threshold < 0) return false;
                if (threshold == 0) return true;
                if (body.getExtra() instanceof LootNotificationData) {
                    long totalValue = ((LootNotificationData) body.getExtra()).getItems()
                        .stream().mapToLong(SerializedItemStack::getTotalPrice).sum();
                    return totalValue >= threshold;
                }
                return false;
            case DEATH:
                return config.deathSendClip();
            case PET:
                return config.petSendClip();
            case COLLECTION:
                return config.collectionSendClip();
            default:
                return false;
        }
    }

    public void onCommand(String command, DinkPlugin plugin, DiscordMessageHandler messageHandler) {
        if (!"DinkClip".equalsIgnoreCase(command)) return;

        if (!active) {
            plugin.addChatWarning("Clip recording is not active. Enable it in Video Clips settings.");
            return;
        }

        log.debug("DinkClip command: active={}, frameCount={}, buffer={}", active, frameCount, frameBuffer != null ? frameBuffer.length : "null");
        plugin.addChatSuccess("Capturing clip...");
        requestClip(0).thenAccept(mp4Bytes -> {
            if (mp4Bytes == null || mp4Bytes.length == 0) {
                plugin.addChatWarning("Failed to encode clip (no frames in buffer)");
                return;
            }

            plugin.addChatSuccess(String.format("Clip ready (%d KB). Sending to webhook...", mp4Bytes.length / 1024));

            String webhookUrl = config.primaryWebhook();
            if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
                plugin.addChatWarning("No primary webhook URL configured");
                return;
            }

            NotificationBody<?> testBody = NotificationBody.builder()
                .type(NotificationType.CHAT)
                .text(Template.builder().template("Dink clip test").build())
                .build();

            clientThread.invokeLater(() -> messageHandler.createMessage(webhookUrl, false, testBody, mp4Bytes));
        }).exceptionally(e -> {
            log.warn("Failed to create clip", e);
            plugin.addChatWarning("Failed to encode clip: " + e.getMessage());
            return null;
        });
    }

    private void requestFrame() {
        if (!active) return;

        log.trace("Requesting frame, buffer has {} frames", frameCount);

        // requestNextFrameListener must be called on the client thread
        clientThread.invokeLater(() -> drawManager.requestNextFrameListener(frame -> {
            if (!active) return;
            log.trace("Frame callback fired, frame class: {}", frame.getClass().getName());

            int w = frame.getWidth(null);
            int h = frame.getHeight(null);
            if (w <= 0 || h <= 0) return;

            // Copy pixel data on the render thread (required - frame may not survive after callback)
            BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            g.drawImage(frame, 0, 0, null);
            g.dispose();

            // Heavy work (scale + JPEG encode) off the render thread
            getProcessingExecutor().execute(() -> processFrame(copy));
        }));
    }

    private void processFrame(BufferedImage image) {
        try {
            BufferedImage scaled = scaleToMaxHeight(image);
            byte[] jpeg = encodeJpeg(scaled);

            synchronized (bufferLock) {
                byte[][] buffer = frameBuffer;
                if (buffer == null) return;

                int idx = writeIndex % buffer.length;
                buffer[idx] = jpeg;
                writeIndex = idx + 1;
                if (writeIndex >= buffer.length) writeIndex = 0;
                if (frameCount < buffer.length) frameCount++;
            }
        } catch (Exception e) {
            log.warn("Failed to process frame", e);
        }
    }

    private byte[] encodeClip() {
        List<byte[]> frames;
        synchronized (bufferLock) {
            byte[][] buffer = frameBuffer;
            log.debug("encodeClip: buffer={}, frameCount={}, writeIndex={}", buffer != null ? buffer.length : "null", frameCount, writeIndex);
            if (buffer == null || frameCount == 0) return null;

            int count = frameCount;
            int wIdx = writeIndex;
            frames = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int idx = (wIdx - count + i + buffer.length) % buffer.length;
                byte[] frame = buffer[idx];
                if (frame != null) {
                    frames.add(frame);
                }
            }
        }

        log.debug("encodeClip: collected {} non-null frames", frames.size());
        if (frames.isEmpty()) return null;

        log.debug("Encoding {} frames to MP4", frames.size());
        File tempFile = null;
        try {
            tempFile = File.createTempFile("dink_clip_", ".mp4");
            int fps = config.clipFps();

            AWTSequenceEncoder encoder = AWTSequenceEncoder.createSequenceEncoder(tempFile, fps);
            for (byte[] jpegBytes : frames) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpegBytes));
                if (img != null) {
                    // jcodec needs TYPE_3BYTE_BGR
                    BufferedImage compatible = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
                    Graphics2D g = compatible.createGraphics();
                    g.drawImage(img, 0, 0, null);
                    g.dispose();
                    encoder.encodeImage(compatible);
                }
            }
            encoder.finish();

            byte[] mp4Bytes = Files.readAllBytes(tempFile.toPath());
            log.debug("Clip encoded: {} KB", mp4Bytes.length / 1024);

            if (mp4Bytes.length > Embed.MAX_VIDEO_SIZE) {
                log.warn("Clip exceeds max size ({} KB > {} KB), discarding", mp4Bytes.length / 1024, Embed.MAX_VIDEO_SIZE / 1024);
                return null;
            }

            return mp4Bytes;
        } catch (Exception e) {
            log.warn("Failed to encode clip to MP4", e);
            return null;
        } finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    private BufferedImage scaleToMaxHeight(BufferedImage input) {
        int w = input.getWidth();
        int h = input.getHeight();

        if (h > MAX_HEIGHT) {
            double scale = (double) MAX_HEIGHT / h;
            w = (int) (w * scale);
            h = MAX_HEIGHT;
        }

        // jcodec H.264 (YUV420) requires even dimensions
        w = w & ~1;
        h = h & ~1;

        if (w == input.getWidth() && h == input.getHeight()) return input;

        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(input, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private ExecutorService getProcessingExecutor() {
        ExecutorService current = processingExecutor;
        return current != null ? current : executor;
    }

}
