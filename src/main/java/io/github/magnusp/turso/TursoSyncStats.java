package io.github.magnusp.turso;

/**
 * Statistics for one embedded replica.
 *
 * @param cdcOperations local change operations written since the last pull
 * @param mainWalSize size of the main WAL file in bytes
 * @param revertWalSize size of the revert WAL file in bytes
 * @param lastPullUnixTime unix timestamp of the last successful pull, or zero
 * @param lastPushUnixTime unix timestamp of the last successful push, or zero
 * @param networkSentBytes bytes sent by push and pull operations
 * @param networkReceivedBytes bytes received by push and pull operations
 * @param revision opaque remote revision; do not parse it
 */
public record TursoSyncStats(
    long cdcOperations,
    long mainWalSize,
    long revertWalSize,
    long lastPullUnixTime,
    long lastPushUnixTime,
    long networkSentBytes,
    long networkReceivedBytes,
    String revision) {}
