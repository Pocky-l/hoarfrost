package com.pockyl.hoarfrost.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.sim.CoverChunk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps the snow cover of one dimension on disk, so drifts and footprints stay where they were made. Chunks are grouped into
 * 32x32-chunk region files ({@code r.<x>.<z>.bin}, gzipped). Regions are read on the main thread when first needed
 * (small files) and written by a background thread from a snapshot.
 */
final class CoverStorage implements AutoCloseable {
    /**
     * Bumped when natural seeding or the data changes, so that ground seen before is seeded again (5: sheltered ground
     * under crowns, roofs and in tunnels; 6: square footprints; 7: 8x8 cells per block; 8: no prints).
     */
    private static final int FORMAT = 8;

    private final Path directory;
    private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Hoarfrost snow cover storage");
        thread.setDaemon(true);
        return thread;
    });

    private static final class Region {
        final Long2ObjectOpenHashMap<byte[]> chunks = new Long2ObjectOpenHashMap<>();
        boolean dirty;
    }

    CoverStorage(Path directory) {
        this.directory = directory;
    }

    /** The saved litter of a chunk, or null if it was never saved. */
    CoverChunk load(int chunkX, int chunkZ) {
        byte[] bytes = region(chunkX >> 5, chunkZ >> 5).chunks.get(ChunkPos.asLong(chunkX, chunkZ));
        if (bytes == null) {
            return null;
        }
        try {
            return CoverChunk.read(new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (IOException e) {
            Hoarfrost.LOGGER.warn("Discarding unreadable snow cover of chunk {}, {}", chunkX, chunkZ, e);
            return null;
        }
    }

    void store(CoverChunk chunk) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16384);
        try {
            chunk.write(new DataOutputStream(bytes));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Region region = region(chunk.x >> 5, chunk.z >> 5);
        region.chunks.put(ChunkPos.asLong(chunk.x, chunk.z), bytes.toByteArray());
        region.dirty = true;
    }

    /** Writes changed regions in the background; regions far from the camera are dropped from memory afterwards. */
    void flush(int cameraChunkX, int cameraChunkZ) {
        for (Iterator<Long2ObjectMap.Entry<Region>> it = regions.long2ObjectEntrySet().iterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<Region> entry = it.next();
            Region region = entry.getValue();
            int regionX = ChunkPos.getX(entry.getLongKey());
            int regionZ = ChunkPos.getZ(entry.getLongKey());
            if (region.dirty) {
                region.dirty = false;
                Long2ObjectOpenHashMap<byte[]> snapshot = new Long2ObjectOpenHashMap<>(region.chunks);
                writer.execute(() -> write(regionX, regionZ, snapshot));
            }
            if (Math.abs(regionX - (cameraChunkX >> 5)) > 1 || Math.abs(regionZ - (cameraChunkZ >> 5)) > 1) {
                it.remove();
            }
        }
    }

    @Override
    public void close() {
        flush(Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                Hoarfrost.LOGGER.warn("Snow cover was not saved completely");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Region region(int regionX, int regionZ) {
        long key = ChunkPos.asLong(regionX, regionZ);
        Region region = regions.get(key);
        if (region == null) {
            region = read(regionX, regionZ);
            regions.put(key, region);
        }
        return region;
    }

    private Path file(int regionX, int regionZ) {
        return directory.resolve("r." + regionX + "." + regionZ + ".bin");
    }

    private Region read(int regionX, int regionZ) {
        Region region = new Region();
        Path file = file(regionX, regionZ);
        if (!Files.isRegularFile(file)) {
            return region;
        }
        try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
            if (in.readInt() != FORMAT) {
                return region;
            }
            int chunks = in.readInt();
            for (int n = 0; n < chunks; n++) {
                long key = in.readLong();
                byte[] bytes = new byte[in.readInt()];
                in.readFully(bytes);
                region.chunks.put(key, bytes);
            }
        } catch (IOException e) {
            Hoarfrost.LOGGER.warn("Could not read snow cover from {}", file, e);
        }
        return region;
    }

    private void write(int regionX, int regionZ, Long2ObjectOpenHashMap<byte[]> chunks) {
        Path file = file(regionX, regionZ);
        try {
            Files.createDirectories(directory);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream raw = Files.newOutputStream(temp); DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
                out.writeInt(FORMAT);
                out.writeInt(chunks.size());
                for (Long2ObjectMap.Entry<byte[]> entry : chunks.long2ObjectEntrySet()) {
                    out.writeLong(entry.getLongKey());
                    out.writeInt(entry.getValue().length);
                    out.write(entry.getValue());
                }
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Hoarfrost.LOGGER.warn("Could not save snow cover to {}", file, e);
        }
    }
}
