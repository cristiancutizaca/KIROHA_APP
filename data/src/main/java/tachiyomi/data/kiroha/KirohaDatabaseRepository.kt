package tachiyomi.data.kiroha
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull

import data.Kiroha_download_record
import tachiyomi.data.Database
import kiroha.core.DeviceCapabilities
import kiroha.core.DeviceRecord
import kiroha.core.DownloadAvailability
import kiroha.core.DownloadRecord
import kiroha.core.SyncEntity
import kiroha.core.SyncEvent
import kiroha.core.SyncOperation
import kiroha.core.SyncCursor
import kiroha.core.SyncSpace

class KirohaDatabaseRepository(
    private val database: Database,
) {
    suspend fun upsertDevice(device: DeviceRecord) {
        database.kiroha_deviceQueries.upsertKirohaDevice(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            platform = device.platform.name,
            createdAt = device.createdAt,
            lastSeen = device.lastSeen,
            appVersion = device.appVersion,
            capabilities = encodeCapabilities(device.capabilities),
        )
    }

    suspend fun insertEvent(event: SyncEvent) {
        database.kiroha_sync_eventQueries.insertKirohaSyncEvent(
            eventId = event.eventId,
            entity = event.entity.name,
            entityId = event.entityId,
            operation = event.operation.name,
            revision = event.revision,
            timestamp = event.timestamp,
            deviceId = event.deviceId,
            payload = event.payload,
        )
    }

    suspend fun insertRemoteEvent(event: SyncEvent) {
        database.kiroha_sync_eventQueries.insertRemoteKirohaSyncEvent(
            eventId = event.eventId,
            entity = event.entity.name,
            entityId = event.entityId,
            operation = event.operation.name,
            revision = event.revision,
            timestamp = event.timestamp,
            deviceId = event.deviceId,
            payload = event.payload,
        )
    }

    suspend fun pendingEvents(): List<SyncEvent> = database.kiroha_sync_eventQueries.getPendingKirohaSyncEvents()
        .awaitAsList()
        .map { row ->
            SyncEvent(
                eventId = row.event_id,
                entity = SyncEntity.valueOf(row.entity),
                entityId = row.entity_id,
                operation = SyncOperation.valueOf(row.operation),
                revision = row.revision,
                timestamp = row.timestamp,
                deviceId = row.device_id,
                payload = row.payload,
            )
        }

    suspend fun acknowledgeEvent(eventId: String) {
        database.kiroha_sync_eventQueries.acknowledgeKirohaSyncEvent(eventId)
    }

    suspend fun upsertSpace(space: SyncSpace) {
        database.kiroha_sync_spaceQueries.upsertKirohaSyncSpace(
            spaceId = space.spaceId,
            name = space.name,
            createdAt = space.createdAt,
            globalRevision = space.globalRevision,
        )
    }

    suspend fun upsertCursor(cursor: SyncCursor) {
        database.kiroha_sync_cursorQueries.upsertKirohaSyncCursor(
            spaceId = cursor.spaceId,
            deviceId = cursor.deviceId,
            revision = cursor.revision,
            updatedAt = cursor.updatedAt,
        )
    }

    suspend fun cursor(spaceId: String, deviceId: String): SyncCursor? =
        database.kiroha_sync_cursorQueries.getKirohaSyncCursor(spaceId, deviceId)
            .awaitAsOneOrNull()
            ?.let { SyncCursor(it.space_id, it.device_id, it.revision, it.updated_at) }

    suspend fun upsertDownload(record: DownloadRecord) {
        database.kiroha_download_recordQueries.upsertKirohaDownload(
            id = "${record.itemId}:${record.deviceId}:${record.storageId}",
            itemId = record.itemId,
            deviceId = record.deviceId,
            storageId = record.storageId,
            state = record.state.name,
            downloadedEver = record.downloadedEver,
            localPath = record.localPath,
            size = record.size,
            checksum = record.checksum,
            downloadedAt = record.downloadedAt,
            lastVerifiedAt = record.lastVerifiedAt,
            deletedAt = record.deletedAt,
            chapterId = record.chapterId,
            sourceNamespace = record.sourceNamespace,
            canonicalId = record.canonicalId,
        )
    }

    suspend fun upsertDownloads(records: Iterable<DownloadRecord>) {
        database.transaction {
            records.forEach { record ->
                database.kiroha_download_recordQueries.upsertKirohaDownload(
                    id = "${record.itemId}:${record.deviceId}:${record.storageId}",
                    itemId = record.itemId,
                    deviceId = record.deviceId,
                    storageId = record.storageId,
                    state = record.state.name,
                    downloadedEver = record.downloadedEver,
                    localPath = record.localPath,
                    size = record.size,
                    checksum = record.checksum,
                    downloadedAt = record.downloadedAt,
                    lastVerifiedAt = record.lastVerifiedAt,
                    deletedAt = record.deletedAt,
                    chapterId = record.chapterId,
                    sourceNamespace = record.sourceNamespace,
                    canonicalId = record.canonicalId,
                )
            }
        }
    }

    suspend fun downloadsForItem(itemId: String): List<DownloadRecord> =
        database.kiroha_download_recordQueries.getKirohaDownloadsForItem(itemId).awaitAsList().map(::toDownloadRecord)

    suspend fun downloads(): List<DownloadRecord> =
        database.kiroha_download_recordQueries.getKirohaDownloads().awaitAsList().map(::toDownloadRecord)

    private fun toDownloadRecord(row: Kiroha_download_record): DownloadRecord = DownloadRecord(
        itemId = row.item_id,
        deviceId = row.device_id,
        storageId = row.storage_id,
        state = DownloadAvailability.valueOf(row.state),
        downloadedEver = row.downloaded_ever,
        localPath = row.local_path,
        size = row.size,
        checksum = row.checksum,
        downloadedAt = row.downloaded_at,
        lastVerifiedAt = row.last_verified_at,
        deletedAt = row.deleted_at,
        chapterId = row.chapter_id,
        sourceNamespace = row.source_namespace,
        canonicalId = row.canonical_id,
    )

    private fun encodeCapabilities(capabilities: DeviceCapabilities): String = listOf(
        capabilities.canRead,
        capabilities.canDownload,
        capabilities.canHostStorage,
        capabilities.canSync,
    ).joinToString(",")

    companion object {
        fun decodeCapabilities(value: String): DeviceCapabilities {
            val parts = value.split(',').map(String::toBoolean)
            return DeviceCapabilities(
                canRead = parts.getOrElse(0) { true },
                canDownload = parts.getOrElse(1) { true },
                canHostStorage = parts.getOrElse(2) { true },
                canSync = parts.getOrElse(3) { true },
            )
        }
    }
}
