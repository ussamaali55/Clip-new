package com.clipgenius.ai.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Query("UPDATE projects SET status = 'FROZEN', updatedAt = :now WHERE id != :activeId AND status = 'ACTIVE'")
    suspend fun freezeOtherProjects(activeId: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateProjectStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET currentStage = :stage, updatedAt = :now WHERE id = :id")
    suspend fun updateProjectStage(id: String, stage: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    // --- Caption Presets ---

    @Query("SELECT * FROM caption_presets ORDER BY name ASC")
    fun getAllPresets(): Flow<List<CaptionPresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: CaptionPresetEntity)

    @Query("DELETE FROM caption_presets WHERE id = :id")
    suspend fun deletePresetById(id: String)
}
