package com.example.data

import kotlinx.coroutines.flow.Flow

class RecordingRepository(private val recordingDao: RecordingDao) {
    val allRecordings: Flow<List<Recording>> = recordingDao.getAllRecordings()

    suspend fun insert(recording: Recording): Long {
        return recordingDao.insert(recording)
    }

    suspend fun delete(recording: Recording) {
        recordingDao.delete(recording)
    }

    suspend fun deleteById(id: Int) {
        recordingDao.deleteById(id)
    }
}
